package com.tgwsproxy.core

import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloudflare-proxied mirrors of kwsN.web.telegram.org maintained by the
 * upstream tg-ws-proxy project (used as a fallback when direct WS fails).
 */
object CfProxyDomains {
    const val DOMAINS_URL =
        "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"
    private const val MIN_VALID_DOMAINS = 3

    private val ENCODED = listOf(
        "virkgj.com", "vmmzovy.com", "mkuosckvso.com", "zaewayzmplad.com", "twdmbzcm.com",
        "awzwsldi.com", "clngqrflngqin.com", "tjacxbqtj.com", "bxaxtxmrw.com", "dmohrsgmohcrwb.com",
        "vwbmtmoi.com", "khgrre.com", "ulihssf.com", "tmhqsdqmfpmk.com", "xwuwoqbm.com",
        "orgcnunpj.com", "zhkuldz.com", "zypoljnslxa.com", "efabnxaowuzs.com", "zaftuzsftqdq.com",
    )
    private val SUFFIX = intArrayOf(46, 99, 111, 46, 117, 107).map { it.toChar() }.joinToString("")

    val defaults: List<String> by lazy { ENCODED.map(::decode) }

    /** Same obfuscation as upstream `_dd`: Caesar shift by the letter count, `.com` -> real suffix. */
    fun decode(s: String): String {
        if (!s.endsWith(".com")) return s
        val p = s.dropLast(4)
        val n = p.count { it.isLetter() }
        return p.map { c ->
            if (!c.isLetter()) c else {
                val base = if (c > '`') 'a'.code else 'A'.code
                (Math.floorMod(c.code - base - n, 26) + base).toChar()
            }
        }.joinToString("") + SUFFIX
    }

    fun fetch(log: ProxyLog): List<String>? = try {
        val nonce = (1..7).map { ('a'..'z').random() }.joinToString("")
        val conn = URL("$DOMAINS_URL?$nonce").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "tg-ws-proxy")
        val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val pool = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map(::decode)
            .map { it.lowercase() }
            .filter(::isValidDomain)
            .distinct()
        if (pool.size >= MIN_VALID_DOMAINS) pool else {
            log.w("Ignoring fetched CF proxy domain list (${pool.size} valid domains)")
            null
        }
    } catch (e: Exception) {
        log.w("Failed to fetch CF proxy domain list: $e")
        null
    }

    fun isValidDomain(domain: String): Boolean {
        if (domain.isEmpty() || domain.length > 253 || domain.startsWith('.') || domain.endsWith('.')) return false
        val labels = domain.split('.')
        if (labels.size < 2) return false
        for (l in labels) {
            if (l.isEmpty() || l.length > 63 || l.first() == '-' || l.last() == '-') return false
            if (!l.all { it.isLetterOrDigit() || it == '-' }) return false
        }
        val tld = labels.last()
        return tld.length >= 2 && tld.any { it.isLetter() }
    }
}

/** Remembers which CF domain worked for each DC (port of balancer.py). */
class CfBalancer {
    @Volatile
    private var domains: List<String> = emptyList()
    private val dcToDomain = java.util.concurrent.ConcurrentHashMap<Int, String>()

    @Synchronized
    fun updateDomains(list: List<String>) {
        if (list.sorted() == domains.sorted()) return
        domains = list.toList()
        dcToDomain.clear()
        if (domains.isNotEmpty()) for (dc in listOf(1, 2, 3, 4, 5, 203)) dcToDomain[dc] = domains.random()
    }

    fun update(dc: Int, domain: String): Boolean = dcToDomain.put(dc, domain) != domain

    fun domainsFor(dc: Int): List<String> {
        val current = dcToDomain[dc]
        val rest = domains.filter { it != current }.shuffled()
        return if (current != null) listOf(current) + rest else rest
    }

    val size: Int get() = domains.size
}
