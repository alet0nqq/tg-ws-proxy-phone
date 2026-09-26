package com.tgwsproxy.phone

import android.util.Log
import com.tgwsproxy.core.LogLevel
import com.tgwsproxy.core.ProxyLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory ring buffer of recent proxy log lines, shown on the main screen. */
object LogBuffer : ProxyLog {
    private const val MAX_LINES = 500
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    @Volatile
    var verbose = false

    /** Bumped on every change so the UI can cheaply detect updates. */
    @Volatile
    var version = 0L
        private set

    override fun log(level: LogLevel, message: String) {
        if (level == LogLevel.DEBUG && !verbose) return
        val text = ProxyLog.censorDomains(message)
        when (level) {
            LogLevel.DEBUG -> Log.d(TAG, text)
            LogLevel.INFO -> Log.i(TAG, text)
            LogLevel.WARN -> Log.w(TAG, text)
            LogLevel.ERROR -> Log.e(TAG, text)
        }
        val line = "${fmt.format(Date())} ${level.name.first()} $text"
        synchronized(this) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            version++
        }
    }

    fun snapshot(): String = synchronized(this) { lines.joinToString("\n") }

    fun clear() = synchronized(this) {
        lines.clear()
        version++
    }

    private const val TAG = "tg-ws-proxy"
}
