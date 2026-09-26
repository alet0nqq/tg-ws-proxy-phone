package com.tgwsproxy.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tgwsproxy.core.ProxyServer
import com.tgwsproxy.core.Stats
import com.tgwsproxy.core.TlsSettings
import java.net.BindException
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/** Foreground service that owns the running [ProxyServer]. */
class ProxyService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var destroyed = false
    private val notificationUpdater = object : Runnable {
        override fun run() {
            if (server?.isRunning == true) {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
                handler.postDelayed(this, NOTIFICATION_REFRESH_MS)
            }
        }
    }

    /** Wi-Fi <-> mobile switches leave pooled sockets dead and cooldowns meaningless. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private var current: Network? = null

        override fun onAvailable(network: Network) {
            val previous = current
            current = network
            if (previous != null && previous != network) server?.onNetworkChanged()
        }
    }
    private var networkCallbackRegistered = false

    override fun onCreate() {
        super.onCreate()
        networkCallbackRegistered = runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        }.isSuccess
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Settings(this).wantRunning = false
            stopProxy()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        ensureChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (starting || server?.isRunning == true) return START_STICKY

        val settings = Settings(this)
        settings.wantRunning = true
        LogBuffer.verbose = settings.verbose
        starting = true
        lastError = null
        notifyStateChanged(this)
        thread(name = "tgws-start") {
            val error = try {
                val cfg = settings.toConfig()
                val s = ProxyServer(cfg, LogBuffer)
                s.start()
                val orphaned = synchronized(ProxyService) {
                    if (!destroyed) server = s
                    destroyed
                }
                if (orphaned) s.stop()
                null
            } catch (e: BindException) {
                getString(R.string.error_port_busy, settings.port)
            } catch (e: IllegalArgumentException) {
                e.message ?: e.toString()
            } catch (e: Exception) {
                e.toString()
            }
            handler.post {
                starting = false
                if (destroyed) {
                    notifyStateChanged(this)
                    return@post
                }
                if (error != null) {
                    lastError = error
                    LogBuffer.e(error)
                    settings.wantRunning = false
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    handler.removeCallbacks(notificationUpdater)
                    handler.post(notificationUpdater)
                }
                notifyStateChanged(this)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        synchronized(ProxyService) { destroyed = true }
        if (networkCallbackRegistered) {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        }
        handler.removeCallbacksAndMessages(null)
        starting = false
        stopProxy()
        super.onDestroy()
    }

    private fun stopProxy() {
        val s = synchronized(ProxyService) { server.also { server = null } }
        if (s != null) thread(name = "tgws-stop") { s.stop() }
        notifyStateChanged(this)
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val s = server
        val text = if (s != null) {
            getString(
                R.string.notification_stats,
                s.stats.connectionsActive.get(),
                Stats.humanBytes(s.stats.bytesUp.get()),
                Stats.humanBytes(s.stats.bytesDown.get()),
            )
        } else getString(R.string.status_starting)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title, Settings(this).port))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_START = "com.tgwsproxy.phone.START"
        const val ACTION_STOP = "com.tgwsproxy.phone.STOP"
        private const val CHANNEL_ID = "proxy"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_REFRESH_MS = 5_000L

        init {
            // Conscrypt: explicit hostname check instead of JDK endpoint identification.
            TlsSettings.default = TlsSettings(
                useEndpointIdentification = false,
                hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
            )
        }

        @Volatile
        var server: ProxyServer? = null
            private set

        @Volatile
        var starting = false
            private set

        @Volatile
        var lastError: String? = null
            private set

        val isRunning: Boolean get() = server?.isRunning == true

        private val listeners = mutableSetOf<() -> Unit>()

        fun addListener(l: () -> Unit) = synchronized(listeners) { listeners.add(l) }
        fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners.remove(l) }

        private fun notifyStateChanged(context: Context) {
            synchronized(listeners) { listeners.toList() }.forEach { it() }
            runCatching {
                TileService.requestListeningState(context, ComponentName(context, ProxyTileService::class.java))
            }
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, ProxyService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            Settings(context).wantRunning = false
            // stopService is allowed from the background too (unlike startService); onDestroy stops the proxy.
            context.stopService(Intent(context, ProxyService::class.java))
        }
    }
}
