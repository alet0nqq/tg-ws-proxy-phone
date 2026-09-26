package com.tgwsproxy.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the proxy after reboot / app update if autostart is on and it was running. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = Settings(context)
        if (settings.autostart && settings.wantRunning) {
            runCatching { ProxyService.start(context) }
                .onFailure { LogBuffer.w("Autostart failed: $it") }
        }
    }
}
