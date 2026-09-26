package com.tgwsproxy.core

import java.net.Inet4Address
import java.net.InetAddress

data class ProxyConfig(
    val host: String = "127.0.0.1",
    val port: Int = 1443,
    /** 32 hex chars. */
    val secret: String = randomSecret(),
    /** DC -> IP that serves kwsN.web.telegram.org. DCs not listed go straight to fallback. */
    val dcRedirects: Map<Int, String> = DEFAULT_DC_REDIRECTS,
    val bufferSize: Int = 256 * 1024,
    /** Warm WS connections kept per DC (and per media flag). 0 disables the pool. */
    val poolSize: Int = 2,
    val fallbackCfProxy: Boolean = true,
    val cfProxyUserDomains: List<String> = emptyList(),
    val cfProxyWorkerDomains: List<String> = emptyList(),
    /** Use port 80 (no TLS) for CF proxy / CF worker. */
    val disableSecure: Boolean = false,
    val forceTestDc: Boolean = false,
    /**
     * TLS SNI to try when a direct TLS connection to Telegram's WS IP fails (DPI filtering by SNI).
     * Empty disables fronting.
     */
    val frontingSni: String = DEFAULT_FRONTING_SNI,
    /** Download the fresh CF proxy domain list from GitHub. */
    val refreshCfDomains: Boolean = true,
    // Testing knobs: where kwsN.web.telegram.org lives.
    val wsPort: Int = 443,
    val wsSecure: Boolean = true,
    val tcpFallbackPort: Int = 443,
) {
    val secretBytes: ByteArray get() = hexToBytes(secret)

    /** Link that adds this proxy to Telegram (dd- = "random padding" secret). */
    fun telegramLink(linkHost: String = if (host == "0.0.0.0") "127.0.0.1" else host): String =
        "tg://proxy?server=$linkHost&port=$port&secret=dd$secret"

    fun httpsLink(linkHost: String = if (host == "0.0.0.0") "127.0.0.1" else host): String =
        "https://t.me/proxy?server=$linkHost&port=$port&secret=dd$secret"

    fun validate() {
        require(port in 0..65535) { "port must be 0..65535" }
        require(isValidSecret(secret)) { "secret must be 32 hex chars" }
    }

    companion object {
        const val DEFAULT_FRONTING_SNI = "sprinthost.ru"
        val DEFAULT_DC_REDIRECTS: Map<Int, String> = mapOf(2 to "149.154.167.220", 4 to "149.154.167.220")

        fun randomSecret(): String = ByteArray(16).also { MtProto.random.nextBytes(it) }.toHex()

        fun isValidSecret(s: String): Boolean =
            s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        /** Parses lines / comma separated entries like "2:149.154.167.220". */
        fun parseDcIpList(text: String): Map<Int, String> {
            val result = LinkedHashMap<Int, String>()
            for (entry in text.split(',', ';', '\n', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }) {
                val parts = entry.split(':', limit = 2)
                require(parts.size == 2) { "Invalid DC:IP entry '$entry'" }
                val dc = parts[0].trim().toIntOrNull()
                val ip = parts[1].trim()
                require(dc != null && isIpv4(ip)) { "Invalid DC:IP entry '$entry'" }
                result[dc] = ip
            }
            return result
        }

        fun formatDcIpList(map: Map<Int, String>): String =
            map.entries.joinToString("\n") { "${it.key}:${it.value}" }

        fun parseDomainList(text: String): List<String> {
            val seen = HashSet<String>()
            return text.split(',', ';', '\n', ' ', '\t')
                .map { it.trim() }
                .filter { it.isNotEmpty() && seen.add(it.lowercase()) }
        }

        fun isIpv4(s: String): Boolean {
            val parts = s.split('.')
            if (parts.size != 4) return false
            if (!parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() <= 255 }) return false
            return runCatching { InetAddress.getByName(s) is Inet4Address }.getOrDefault(false)
        }

        fun hexToBytes(s: String): ByteArray =
            ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
