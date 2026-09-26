package com.tgwsproxy.core

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local MTProto proxy: the Telegram app connects to it with a dd-secret, the
 * traffic is re-encrypted and tunnelled to Telegram over WebSocket
 * (wss://kwsN.web.telegram.org/apiws), with CF proxy / plain TCP fallback.
 *
 * Port of Flowseal/tg-ws-proxy (proxy/tg_ws_proxy.py + bridge.py) to blocking JVM IO.
 */
class ProxyServer(
    val config: ProxyConfig,
    private val log: ProxyLog = ProxyLog.console(),
    val stats: Stats = Stats(),
) {
    private val threadIds = AtomicInteger()
    private val threadFactory = ThreadFactory { r ->
        Thread(r, "tgws-${threadIds.incrementAndGet()}").apply { isDaemon = true }
    }
    private val executor: ExecutorService = Executors.newCachedThreadPool(threadFactory)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(threadFactory)
    private val pool = WsPool(config, stats, log, executor, scheduler) { ip, domain, path, timeout ->
        connectTelegramWs(ip, domain, path, timeout)
    }
    private val balancer = CfBalancer()
    private val secret = config.secretBytes

    private val clients: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val wsBlacklist: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val dcFailUntil = ConcurrentHashMap<String, Long>()
    private val ipFailUntil = ConcurrentHashMap<String, Long>()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    var isRunning = false
        private set

    /** Actual bound port (useful when config.port == 0 in tests). */
    val localPort: Int get() = serverSocket?.localPort ?: -1

    @Synchronized
    fun start() {
        check(!isRunning) { "already running" }
        config.validate()
        stats.reset()
        serverSocket = bind()
        isRunning = true

        val acceptor = Thread({ acceptLoop() }, "tgws-accept").apply { isDaemon = true }
        acceptor.start()

        if (config.cfProxyUserDomains.isNotEmpty()) {
            balancer.updateDomains(config.cfProxyUserDomains)
        } else {
            balancer.updateDomains(CfProxyDomains.defaults)
            if (config.fallbackCfProxy && config.refreshCfDomains) {
                scheduler.scheduleWithFixedDelay({
                    executor.execute {
                        CfProxyDomains.fetch(log)?.let {
                            balancer.updateDomains(it)
                            log.i("CF proxy domain pool updated (${it.size} domains)")
                        }
                    }
                }, 0, 1, TimeUnit.HOURS)
            }
        }
        scheduler.scheduleWithFixedDelay({
            val bl = wsBlacklist.sorted().joinToString { "DC$it" }.ifEmpty { "none" }
            log.i("stats: ${stats.summary()} | ws_bl: $bl")
        }, 60, 60, TimeUnit.SECONDS)

        printBanner()
        pool.start()
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        pool.shutdown()
        scheduler.shutdownNow()
        executor.shutdownNow()
        log.i("Proxy stopped. Final stats: ${stats.summary()}")
    }

    private fun bind(): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        runCatching { receiveBufferSize = config.bufferSize }
        bind(InetSocketAddress(InetAddress.getByName(config.host), config.port), 128)
    }

    private fun acceptLoop() {
        while (isRunning) {
            val ss = serverSocket ?: return
            try {
                val s = ss.accept()
                clients.add(s)
                executor.execute {
                    try {
                        handleClient(s)
                    } finally {
                        clients.remove(s)
                        runCatching { s.close() }
                    }
                }
            } catch (e: Exception) {
                if (!isRunning) return
                log.w("Listening socket error: $e, restarting listener")
                runCatching { ss.close() }
                Thread.sleep(1000)
                try {
                    serverSocket = bind()
                    log.w("Server restored, listening on ${config.host}:${config.port}")
                } catch (e2: Exception) {
                    log.e("Failed to restart server: $e2")
                    Thread.sleep(4000)
                }
            }
        }
    }

    private fun printBanner() {
        log.i("=".repeat(56))
        log.i("  Telegram MTProto WS Bridge Proxy")
        log.i("  Listening on   ${config.host}:$localPort")
        log.i("  Target DC IPs:")
        for ((dc, ip) in config.dcRedirects.toSortedMap()) log.i("    DC$dc: $ip")
        if (config.fallbackCfProxy) {
            val d = config.cfProxyUserDomains.joinToString().ifEmpty { "auto" }
            log.i("  CF proxy:      enabled ($d)")
        }
        if (config.cfProxyWorkerDomains.isNotEmpty()) {
            log.i("  CF worker:     enabled (${config.cfProxyWorkerDomains.joinToString()})")
        }
        log.i("=".repeat(56))
    }

    // ------------------------------------------------------------ telegram WS

    /** Whether TLS with the fronting SNI worked last time (then it is tried first). */
    @Volatile
    private var frontingFirst = false

    /**
     * Opens wss://[domain][path] on [target]. When the plain attempt fails at the network level
     * (DPI dropping the Telegram SNI), retries with [ProxyConfig.frontingSni] as TLS SNI.
     */
    internal fun connectTelegramWs(target: String, domain: String, path: String, timeoutMs: Int): RawWebSocket {
        val sni = config.frontingSni.trim().takeIf { it.isNotEmpty() && config.wsSecure }
        val order = when {
            sni == null -> listOf(null)
            frontingFirst -> listOf(sni, null)
            else -> listOf(null, sni)
        }
        var last: Exception? = null
        for (s in order) {
            try {
                val ws = RawWebSocket.connect(
                    target, domain, path = path, timeoutMs = timeoutMs,
                    secure = config.wsSecure, port = config.wsPort, bufferSize = config.bufferSize, sni = s,
                )
                if (sni != null && frontingFirst != (s != null)) {
                    frontingFirst = s != null
                    log.i(if (s != null) "SNI fronting works, using it first" else "Direct TLS works again")
                }
                if (s != null) stats.connectionsFronting.incrementAndGet()
                return ws
            } catch (e: WsHandshakeError) {
                throw e // TLS got through, the server answered: fronting would not change that
            } catch (e: Exception) {
                last = e
            }
        }
        throw last!!
    }

    /** Call when the device switches networks: cached failures and pooled sockets are stale. */
    fun onNetworkChanged() {
        if (!isRunning) return
        ipFailUntil.clear()
        dcFailUntil.clear()
        wsBlacklist.clear()
        frontingFirst = false
        pool.flush()
        log.i("Network changed: reset cooldowns and WS pool")
    }

    // ---------------------------------------------------------------- client

    private fun handleClient(sock: Socket) {
        stats.connectionsTotal.incrementAndGet()
        stats.connectionsActive.incrementAndGet()
        val label = "${sock.inetAddress.hostAddress}:${sock.port}"
        try {
            RawWebSocket.configureSocket(sock, config.bufferSize)
            sock.soTimeout = 10_000
            val input = sock.getInputStream()
            val output = sock.getOutputStream()

            val handshake = readExactly(input, MtProto.HANDSHAKE_LEN) ?: run {
                log.d("[$label] client disconnected before handshake")
                return
            }
            val hs = MtProto.parseClientHandshake(handshake, secret)
            if (hs == null) {
                stats.connectionsBad.incrementAndGet()
                log.w("[$label] bad handshake (wrong secret or proto)")
                runCatching { drain(input) }
                return
            }
            sock.soTimeout = 0

            var dc = hs.dc
            val isTestDc = config.forceTestDc || dc >= 10000
            if (dc >= 10000) {
                log.i("[$label] test DC$dc -> DC${dc - 10000}")
                dc -= 10000
            }
            val isMedia = hs.isMedia
            val mediaTag = if (isMedia) " media" else ""
            log.d("[$label] handshake ok: DC$dc$mediaTag proto=0x%08X".format(hs.proto))

            val relayInit = MtProto.generateRelayInit(hs.protoTag, if (isMedia) -dc else dc)
            val ctx = MtProto.buildCryptoCtx(hs.prekeyAndIv, secret, relayInit)
            val conn = ClientConn(label, sock, input, output, relayInit, ctx, hs.proto, dc, isTestDc, isMedia)

            val dcKey = "$dc${if (isTestDc) "t" else ""}${if (isMedia) "m" else ""}"
            val now = nowMs()
            val wsPath = if (isTestDc) Endpoints.WS_PATH_TEST else Endpoints.WS_PATH
            val target = config.dcRedirects[dc]
            val anyCf = config.fallbackCfProxy || config.cfProxyWorkerDomains.isNotEmpty()
            val domains = Endpoints.wsDomains(dc, isMedia)

            // Pooled sockets can be dead (e.g. after switching Wi-Fi <-> mobile): the relay init
            // is the first write, so a failure there is safe to retry on another connection.
            fun takePooled(): RawWebSocket? {
                if (isTestDc || target == null) return null
                while (true) {
                    val pooled = pool.get(dc, isMedia, target, domains) ?: return null
                    try {
                        pooled.send(relayInit)
                        return pooled
                    } catch (e: IOException) {
                        log.d("[$label] DC$dc$mediaTag pooled WS was dead ($e), dropping it")
                        pooled.close()
                    }
                }
            }

            var ws: RawWebSocket? = null
            if (target == null || dcKey in wsBlacklist || (now < (ipFailUntil[target] ?: 0) && anyCf)) {
                when {
                    target == null -> log.i("[$label] DC$dc not in config -> fallback")
                    dcKey in wsBlacklist -> log.i("[$label] DC$dc$mediaTag WS blacklisted -> fallback")
                    else -> {
                        ws = takePooled()
                        if (ws == null) log.i("[$label] DC$dc$mediaTag WS to $target timed out recently -> fallback")
                        else log.i("[$label] DC$dc$mediaTag WS to $target timed out recently, but pool hit -> WS")
                    }
                }
                if (ws == null) {
                    if (!doFallback(conn)) log.w("[$label] DC$dc$mediaTag no fallback available")
                    return
                }
            }
            target!!

            val wsTimeout = if (now < (dcFailUntil[dcKey] ?: 0)) 2_000 else 5_000
            var failedRedirect = false
            var timedOut = false
            var allRedirects = true

            if (ws == null) ws = takePooled()
            if (ws != null) {
                log.i("[$label] DC$dc$mediaTag -> pool hit via $target")
            } else {
                for (domain in domains) {
                    log.i("[$label] DC$dc$mediaTag -> wss://$domain$wsPath via $target")
                    try {
                        val fresh = connectTelegramWs(target, domain, wsPath, wsTimeout)
                        try {
                            fresh.send(relayInit)
                        } catch (e: IOException) {
                            fresh.close()
                            throw e
                        }
                        ws = fresh
                        allRedirects = false
                        break
                    } catch (e: WsHandshakeError) {
                        stats.wsErrors.incrementAndGet()
                        if (e.isRedirect) {
                            failedRedirect = true
                            log.w("[$label] DC$dc$mediaTag got ${e.statusCode} from $domain -> ${e.location ?: "?"}")
                        } else {
                            allRedirects = false
                            log.w("[$label] DC$dc$mediaTag WS handshake: ${e.statusLine}")
                        }
                    } catch (e: Exception) {
                        stats.wsErrors.incrementAndGet()
                        if (RawWebSocket.isTimeout(e)) {
                            timedOut = true
                            log.w("[$label] DC$dc$mediaTag WS connect timed out via $domain")
                            break
                        }
                        allRedirects = false
                        log.w("[$label] DC$dc$mediaTag WS connect failed: $e")
                    }
                }
            }

            if (ws == null) {
                if (timedOut) {
                    ipFailUntil[target] = now + IP_FAIL_COOLDOWN_MS
                    log.i("[$label] DC$dc$mediaTag WS to $target timed out, cooldown ${IP_FAIL_COOLDOWN_MS / 1000}s")
                }
                if (failedRedirect && allRedirects) {
                    wsBlacklist.add(dcKey)
                    log.w("[$label] DC$dc$mediaTag blacklisted for WS (all redirects)")
                } else {
                    dcFailUntil[dcKey] = now + DC_FAIL_COOLDOWN_MS
                    if (!failedRedirect) log.i("[$label] DC$dc$mediaTag WS failed for ${DC_FAIL_COOLDOWN_MS / 1000}s")
                }
                if (doFallback(conn)) log.i("[$label] DC$dc$mediaTag fallback closed")
                else log.w("[$label] DC$dc$mediaTag no fallback available")
                return
            }

            ipFailUntil.remove(target)
            pool.reportSuccess(dc, isMedia)
            stats.connectionsWs.incrementAndGet()
            bridgeWs(conn, ws, MsgSplitter(relayInit, hs.proto))
        } catch (e: EOFException) {
            log.d("[$label] client disconnected")
        } catch (e: SocketException) {
            log.d("[$label] connection closed: ${e.message}")
        } catch (e: IOException) {
            if (RawWebSocket.isTimeout(e)) log.w("[$label] timeout during handshake")
            else log.w("[$label] IO error: $e")
        } catch (e: Exception) {
            log.e("[$label] unexpected: $e")
        } finally {
            stats.connectionsActive.decrementAndGet()
            runCatching { sock.close() }
        }
    }

    private class ClientConn(
        val label: String,
        val sock: Socket,
        val input: InputStream,
        val output: OutputStream,
        val relayInit: ByteArray,
        val ctx: MtProto.CryptoCtx,
        val proto: Int,
        val dc: Int,
        val isTestDc: Boolean,
        val isMedia: Boolean,
    ) {
        val mediaTag get() = if (isMedia) " media" else ""
    }

    // -------------------------------------------------------------- fallback

    private fun doFallback(c: ClientConn): Boolean {
        val fallbackDst = (if (c.isTestDc) Endpoints.DC_TEST_IPS else Endpoints.DC_DEFAULT_IPS)[c.dc]
        if (fallbackDst != null && config.cfProxyWorkerDomains.isNotEmpty()) {
            if (cfWorkerFallback(c, fallbackDst)) return true
        }
        if (config.fallbackCfProxy && !c.isTestDc) {
            if (cfProxyFallback(c)) return true
        }
        if (fallbackDst != null) {
            log.i("[${c.label}] DC${c.dc}${c.mediaTag} -> TCP fallback to $fallbackDst:${config.tcpFallbackPort}")
            if (tcpFallback(c, fallbackDst)) return true
        }
        return false
    }

    private fun cfWorkerFallback(c: ClientConn, fallbackDst: String): Boolean {
        val path = "/apiws?dst=${URLEncoder.encode(fallbackDst, "UTF-8")}&dc=${c.dc}"
        for (worker in config.cfProxyWorkerDomains.shuffled()) {
            log.i("[${c.label}] DC${c.dc}${c.mediaTag} -> trying CF worker $worker for $fallbackDst")
            val ws = try {
                RawWebSocket.connect(
                    worker, worker, path = path, timeoutMs = 10_000,
                    secure = !config.disableSecure, bufferSize = config.bufferSize,
                )
            } catch (e: Exception) {
                log.w("[${c.label}] DC${c.dc}${c.mediaTag} CF worker $worker failed: $e")
                continue
            }
            stats.connectionsCfProxy.incrementAndGet()
            ws.send(c.relayInit)
            bridgeWs(c, ws, null)
            return true
        }
        return false
    }

    private fun cfProxyFallback(c: ClientConn): Boolean {
        log.i("[${c.label}] DC${c.dc}${c.mediaTag} -> trying CF proxy")
        for (base in balancer.domainsFor(c.dc).take(MAX_CF_ATTEMPTS)) {
            val domain = "kws${c.dc}.$base"
            val ws = try {
                RawWebSocket.connect(
                    domain, domain, timeoutMs = 10_000,
                    secure = !config.disableSecure, bufferSize = config.bufferSize,
                )
            } catch (e: Exception) {
                log.w("[${c.label}] DC${c.dc}${c.mediaTag} CF proxy failed: $e")
                continue
            }
            if (balancer.update(c.dc, base)) log.i("[${c.label}] Switched active CF domain")
            stats.connectionsCfProxy.incrementAndGet()
            ws.send(c.relayInit)
            bridgeWs(c, ws, MsgSplitter(c.relayInit, c.proto))
            return true
        }
        return false
    }

    private fun tcpFallback(c: ClientConn, dst: String): Boolean {
        val remote = Socket()
        try {
            RawWebSocket.configureSocket(remote, config.bufferSize)
            remote.connect(InetSocketAddress(dst, config.tcpFallbackPort), 10_000)
        } catch (e: Exception) {
            runCatching { remote.close() }
            log.w("[${c.label}] TCP fallback to $dst failed: $e")
            return false
        }
        stats.connectionsTcpFallback.incrementAndGet()
        remote.use {
            val rOut = remote.getOutputStream()
            rOut.write(c.relayInit)
            rOut.flush()
            bridgeTcp(c, remote)
        }
        return true
    }

    // ---------------------------------------------------------------- bridge

    private fun bridgeWs(c: ClientConn, ws: RawWebSocket, splitter: MsgSplitter?) {
        val dcTag = "DC${c.dc}${if (c.isMedia) "m" else ""}"
        val start = nowMs()
        var upBytes = 0L
        var downBytes = 0L
        val reason = java.util.concurrent.atomic.AtomicReference("normal")

        val up = executor.submit {
            val buf = ByteArray(65536)
            try {
                while (true) {
                    val n = c.input.read(buf)
                    if (n < 0) {
                        splitter?.flush()?.firstOrNull()?.let { ws.send(it) }
                        break
                    }
                    if (n == 0) continue
                    stats.bytesUp.addAndGet(n.toLong())
                    upBytes += n
                    val data = c.ctx.tgEnc.update(c.ctx.cltDec.update(buf, 0, n))
                    if (splitter != null) {
                        val parts = splitter.split(data)
                        when (parts.size) {
                            0 -> Unit
                            1 -> ws.send(parts[0])
                            else -> ws.sendBatch(parts)
                        }
                    } else {
                        ws.send(data)
                    }
                }
            } catch (e: Exception) {
                reason.compareAndSet("normal", "client: ${e.javaClass.simpleName}")
            } finally {
                ws.close()
            }
        }

        try {
            while (true) {
                val data = ws.recv()
                if (data == null) {
                    reason.compareAndSet("normal", "upstream: ws_close")
                    break
                }
                stats.bytesDown.addAndGet(data.size.toLong())
                downBytes += data.size
                c.output.write(c.ctx.cltEnc.update(c.ctx.tgDec.update(data)))
                c.output.flush()
            }
        } catch (e: Exception) {
            reason.compareAndSet("normal", "upstream: ${e.javaClass.simpleName}")
        } finally {
            ws.close()
            runCatching { c.sock.close() }
            runCatching { up.get(5, TimeUnit.SECONDS) }
            val elapsed = (nowMs() - start) / 1000.0
            log.i(
                "[${c.label}] $dcTag WS session closed (${reason.get()}): " +
                    "^${Stats.humanBytes(upBytes)} v${Stats.humanBytes(downBytes)} in ${"%.1f".format(elapsed)}s",
            )
        }
    }

    private fun bridgeTcp(c: ClientConn, remote: Socket) {
        val rIn = remote.getInputStream()
        val rOut = remote.getOutputStream()
        val up = executor.submit {
            val buf = ByteArray(65536)
            try {
                while (true) {
                    val n = c.input.read(buf)
                    if (n < 0) break
                    stats.bytesUp.addAndGet(n.toLong())
                    rOut.write(c.ctx.tgEnc.update(c.ctx.cltDec.update(buf, 0, n)))
                    rOut.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { remote.close() }
            }
        }
        val buf = ByteArray(65536)
        try {
            while (true) {
                val n = rIn.read(buf)
                if (n < 0) break
                stats.bytesDown.addAndGet(n.toLong())
                c.output.write(c.ctx.cltEnc.update(c.ctx.tgDec.update(buf, 0, n)))
                c.output.flush()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { remote.close() }
            runCatching { c.sock.close() }
            runCatching { up.get(5, TimeUnit.SECONDS) }
        }
    }

    // --------------------------------------------------------------- helpers

    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) return null
            off += r
        }
        return buf
    }

    private fun drain(input: InputStream) {
        val buf = ByteArray(4096)
        while (input.read(buf) >= 0) Unit
    }

    private fun nowMs() = System.nanoTime() / 1_000_000

    companion object {
        const val IP_FAIL_COOLDOWN_MS = 3_600_000L
        const val DC_FAIL_COOLDOWN_MS = 60_000L
        const val MAX_CF_ATTEMPTS = 6
    }
}
