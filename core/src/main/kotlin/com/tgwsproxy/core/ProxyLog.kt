package com.tgwsproxy.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

fun interface ProxyLog {
    fun log(level: LogLevel, message: String)

    fun d(msg: String) = log(LogLevel.DEBUG, msg)
    fun i(msg: String) = log(LogLevel.INFO, msg)
    fun w(msg: String) = log(LogLevel.WARN, msg)
    fun e(msg: String) = log(LogLevel.ERROR, msg)

    companion object {
        fun console(minLevel: LogLevel = LogLevel.INFO): ProxyLog {
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
            return ProxyLog { level, message ->
                if (level >= minLevel) {
                    val line = "${synchronized(fmt) { fmt.format(Date()) }}  ${level.name.padEnd(5)}  ${censorDomains(message)}"
                    if (level >= LogLevel.WARN) System.err.println(line) else println(line)
                }
            }
        }

        private val DOMAIN = Regex("""(?<![\w-])(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}(?![\w-])""")

        /** Hides non-Telegram domains (e.g. CF proxy mirrors) so logs are safe to share. */
        fun censorDomains(message: String): String = DOMAIN.replace(message) { m ->
            val domain = m.value
            val norm = domain.lowercase().trimEnd('.')
            if (norm == "telegram.org" || norm.endsWith(".telegram.org")) return@replace domain
            val parts = domain.split('.')
            parts.mapIndexed { i, p ->
                if (i == parts.size - 1) p else p.substring(0, p.length / 2) + "*".repeat(p.length - p.length / 2)
            }.joinToString(".")
        }
    }
}
