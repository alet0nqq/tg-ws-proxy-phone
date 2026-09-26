package com.tgwsproxy.phone

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: one tap to toggle the proxy. */
class ProxyTileService : TileService() {
    private val listener: () -> Unit = { refresh() }

    override fun onStartListening() {
        ProxyService.addListener(listener)
        refresh()
    }

    override fun onStopListening() {
        ProxyService.removeListener(listener)
    }

    override fun onClick() {
        if (ProxyService.isRunning || ProxyService.starting) {
            ProxyService.stop(this)
        } else {
            try {
                ProxyService.start(this)
            } catch (e: Exception) {
                // Background FGS start refused by the system: open the app instead.
                openApp()
            }
        }
        refresh()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_START, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = if (ProxyService.isRunning || ProxyService.starting) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.updateTile()
    }
}
