package com.tgwsproxy.phone

import android.content.Context
import android.content.SharedPreferences
import com.tgwsproxy.core.ProxyConfig

/** Persistent user settings (SharedPreferences) -> [ProxyConfig]. */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(v) = prefs.edit().putInt(KEY_PORT, v).apply()

    /** Generated once and kept, so the proxy added to Telegram stays valid. */
    var secret: String
        get() = prefs.getString(KEY_SECRET, null)?.takeIf(ProxyConfig::isValidSecret)
            ?: ProxyConfig.randomSecret().also { secret = it }
        set(v) = prefs.edit().putString(KEY_SECRET, v.lowercase()).apply()

    var dcIps: String
        get() = prefs.getString(KEY_DC_IPS, null) ?: ProxyConfig.formatDcIpList(ProxyConfig.DEFAULT_DC_REDIRECTS)
        set(v) = prefs.edit().putString(KEY_DC_IPS, v).apply()

    var cfProxy: Boolean
        get() = prefs.getBoolean(KEY_CF_PROXY, true)
        set(v) = prefs.edit().putBoolean(KEY_CF_PROXY, v).apply()

    var cfDomains: String
        get() = prefs.getString(KEY_CF_DOMAINS, "") ?: ""
        set(v) = prefs.edit().putString(KEY_CF_DOMAINS, v).apply()

    var cfWorkerDomains: String
        get() = prefs.getString(KEY_CF_WORKERS, "") ?: ""
        set(v) = prefs.edit().putString(KEY_CF_WORKERS, v).apply()

    var poolSize: Int
        get() = prefs.getInt(KEY_POOL, 2)
        set(v) = prefs.edit().putInt(KEY_POOL, v).apply()

    var autostart: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTOSTART, v).apply()

    var verbose: Boolean
        get() = prefs.getBoolean(KEY_VERBOSE, false)
        set(v) = prefs.edit().putBoolean(KEY_VERBOSE, v).apply()

    /** Whether the user wanted the proxy on (restored after reboot / app update). */
    var wantRunning: Boolean
        get() = prefs.getBoolean(KEY_WANT_RUNNING, false)
        set(v) = prefs.edit().putBoolean(KEY_WANT_RUNNING, v).apply()

    /** Throws IllegalArgumentException with a readable message on bad input. */
    fun toConfig(): ProxyConfig = ProxyConfig(
        host = "127.0.0.1",
        port = port,
        secret = secret,
        dcRedirects = ProxyConfig.parseDcIpList(dcIps),
        poolSize = poolSize,
        fallbackCfProxy = cfProxy,
        cfProxyUserDomains = ProxyConfig.parseDomainList(cfDomains),
        cfProxyWorkerDomains = ProxyConfig.parseDomainList(cfWorkerDomains),
    ).also { it.validate() }

    companion object {
        const val DEFAULT_PORT = 1443
        private const val KEY_PORT = "port"
        private const val KEY_SECRET = "secret"
        private const val KEY_DC_IPS = "dc_ips"
        private const val KEY_CF_PROXY = "cf_proxy"
        private const val KEY_CF_DOMAINS = "cf_domains"
        private const val KEY_CF_WORKERS = "cf_workers"
        private const val KEY_POOL = "pool_size"
        private const val KEY_AUTOSTART = "autostart"
        private const val KEY_VERBOSE = "verbose"
        private const val KEY_WANT_RUNNING = "want_running"
    }
}
