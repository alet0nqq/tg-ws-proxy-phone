package com.tgwsproxy.core

import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Keeps a few pre-opened WebSocket connections per DC so new Telegram
 * connections do not pay for TCP+TLS+HTTP upgrade.
 *
 * Phone-friendly difference from upstream: a DC's pool is only refilled while
 * that DC was used recently, so an idle phone does not churn TLS sessions.
 */
internal class WsPool(
    private val config: ProxyConfig,
    private val stats: Stats,
    private val log: ProxyLog,
    private val executor: ExecutorService,
    private val scheduler: ScheduledExecutorService,
    private val connector: (ip: String, domain: String, path: String, timeoutMs: Int) -> RawWebSocket,
) {
    private data class Key(val dc: Int, val isMedia: Boolean)
    private class Entry(val ws: RawWebSocket, val created: Long)
    private class Target(val ip: String, val domains: List<String>)

    private val idle = HashMap<Key, ArrayDeque<Entry>>()
    private val targets = HashMap<Key, Target>()
    private val refilling = HashSet<Key>()
    private val failures = HashMap<Key, Int>()
    private val refillAfter = HashMap<Key, Long>()
    private val lastUsed = HashMap<Key, Long>()
    @Volatile
    private var stopped = false

    fun start() {
        if (config.poolSize <= 0) return
        val now = now()
        synchronized(this) {
            for ((dc, ip) in config.dcRedirects) for (media in listOf(false, true)) {
                val key = Key(dc, media)
                targets[key] = Target(ip, Endpoints.wsDomains(dc, media))
                lastUsed[key] = now
            }
        }
        scheduler.scheduleWithFixedDelay({ runCatching { maintain() } }, 0, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)
        log.i("WS pool warmup started for ${config.dcRedirects.size} DC(s)")
    }

    fun get(dc: Int, isMedia: Boolean, targetIp: String, domains: List<String>): RawWebSocket? {
        if (config.poolSize <= 0) return null
        val key = Key(dc, isMedia)
        val now = now()
        val stale = ArrayList<RawWebSocket>()
        var found: RawWebSocket? = null
        synchronized(this) {
            targets[key] = Target(targetIp, domains)
            lastUsed[key] = now
            val bucket = idle[key]
            while (bucket != null && bucket.isNotEmpty()) {
                val e = bucket.removeFirst()
                if (now - e.created > MAX_AGE_MS || !e.ws.looksAlive) {
                    stale.add(e.ws)
                    continue
                }
                found = e.ws
                break
            }
        }
        stale.forEach { quietClose(it) }
        if (found != null) {
            stats.poolHits.incrementAndGet()
            reportSuccess(dc, isMedia)
        } else {
            stats.poolMisses.incrementAndGet()
        }
        scheduleRefill(key)
        return found
    }

    fun reportSuccess(dc: Int, isMedia: Boolean) {
        synchronized(this) {
            val key = Key(dc, isMedia)
            failures.remove(key)
            refillAfter.remove(key)
        }
    }

    /** Drop all idle connections and refill backoffs (network changed). */
    fun flush() {
        val all = synchronized(this) {
            failures.clear()
            refillAfter.clear()
            val list = idle.values.flatMap { q -> q.map { it.ws } }
            idle.clear()
            list
        }
        all.forEach { quietClose(it) }
        synchronized(this) { targets.keys.toList() }.forEach { scheduleRefill(it) }
    }

    fun shutdown() {
        stopped = true
        val all = synchronized(this) {
            val list = idle.values.flatMap { q -> q.map { it.ws } }
            idle.clear()
            list
        }
        all.forEach { quietClose(it) }
    }

    private fun maintain() {
        if (stopped) return
        val now = now()
        val stale = ArrayList<RawWebSocket>()
        val toRefill = ArrayList<Key>()
        synchronized(this) {
            for ((key, bucket) in idle) {
                val it = bucket.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    if (now - e.created >= MAX_AGE_MS || !e.ws.looksAlive) {
                        stale.add(e.ws)
                        it.remove()
                    }
                }
            }
            for (key in targets.keys) {
                if (now - (lastUsed[key] ?: 0) < IDLE_KEEP_MS) toRefill.add(key)
            }
        }
        stale.forEach { quietClose(it) }
        toRefill.forEach { scheduleRefill(it) }
    }

    private fun scheduleRefill(key: Key) {
        if (stopped) return
        val target: Target
        synchronized(this) {
            if (key in refilling || now() < (refillAfter[key] ?: 0)) return
            target = targets[key] ?: return
            if ((idle[key]?.size ?: 0) >= config.poolSize) return
            refilling.add(key)
        }
        executor.execute { refill(key, target) }
    }

    private fun refill(key: Key, target: Target) {
        try {
            val needed = synchronized(this) { config.poolSize - (idle[key]?.size ?: 0) }
            if (needed <= 0) return
            val futures = (0 until needed).map { executor.submit<RawWebSocket?> { connectOne(target) } }
            var connected = 0
            for (f in futures) {
                val ws = runCatching { f.get() }.getOrNull() ?: continue
                if (stopped) {
                    quietClose(ws)
                    continue
                }
                synchronized(this) { idle.getOrPut(key) { ArrayDeque() }.addLast(Entry(ws, now())) }
                connected++
            }
            val tag = "DC${key.dc}${if (key.isMedia) "m" else ""}"
            if (connected > 0) {
                reportSuccess(key.dc, key.isMedia)
                log.d("WS pool refilled $tag: +$connected")
            } else {
                val delay = synchronized(this) {
                    val n = (failures[key] ?: 0) + 1
                    failures[key] = n
                    val d = minOf(BACKOFF_INITIAL_MS shl minOf(n - 1, 12), BACKOFF_MAX_MS)
                    refillAfter[key] = now() + d
                    d
                }
                log.i("WS pool refill failed for $tag, retry in ${delay / 1000}s")
            }
        } finally {
            synchronized(this) { refilling.remove(key) }
        }
    }

    private fun connectOne(target: Target): RawWebSocket? {
        for (domain in target.domains) {
            try {
                return connector(target.ip, domain, Endpoints.WS_PATH, 8_000)
            } catch (e: WsHandshakeError) {
                if (e.isRedirect) continue
                return null
            } catch (e: Exception) {
                return null
            }
        }
        return null
    }

    private fun quietClose(ws: RawWebSocket) {
        executor.execute { runCatching { ws.close() } }
    }

    private fun now() = System.nanoTime() / 1_000_000

    companion object {
        const val MAX_AGE_MS = 120_000L
        const val CHECK_INTERVAL_MS = 5_000L
        const val IDLE_KEEP_MS = 10 * 60_000L
        const val BACKOFF_INITIAL_MS = 1_000L
        const val BACKOFF_MAX_MS = 3_600_000L
    }
}
