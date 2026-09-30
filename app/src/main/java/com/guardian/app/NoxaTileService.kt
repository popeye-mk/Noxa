package com.guardian.app

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * v1.5 Quick Settings tile: turn protection on/off from the pull-down shade,
 * without opening the app. Anything that needs the user's attention (first
 * VPN permission, tunnel mode) opens the app instead of guessing.
 */
class NoxaTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        when {
            // Tunnel mode or no VPN permission yet: the app explains and asks.
            TunnelController.isUp || VpnService.prepare(this) != null -> openApp()
            GuardianVpnService.isRunning.get() -> {
                startService(Intent(this, GuardianVpnService::class.java)
                    .setAction(GuardianVpnService.ACTION_STOP))
                show(on = false, sub = "Off")
            }
            else -> {
                val svc = Intent(this, GuardianVpnService::class.java)
                    .setAction(GuardianVpnService.ACTION_START)
                try {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
                    show(on = true, sub = "Protected")
                } catch (_: Exception) {
                    openApp()     // the OS refused a background start — let the app do it
                }
            }
        }
    }

    private fun refresh() {
        when {
            TunnelController.isUp -> show(on = true, sub = "Tunnel mode")
            GuardianVpnService.isRunning.get() -> show(on = true, sub = "Protected")
            GuardianVpnService.isPaused(this) -> show(on = false, sub = "Paused")
            else -> show(on = false, sub = "Off")
        }
    }

    private fun show(on: Boolean, sub: String) {
        val t = qsTile ?: return
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Noxa"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = sub
        t.updateTile()
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
