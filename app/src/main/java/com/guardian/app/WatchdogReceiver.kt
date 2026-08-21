package com.guardian.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.SystemClock

/**
 * Keep-alive watchdog (v1.2). Aggressive OEM battery managers (MIUI/HyperOS,
 * EMUI, ColorOS...) sometimes kill the VPN service silently — protection stops
 * and the user never knows. This receiver runs every ~15 minutes and after
 * boot: if the user wants protection ON but the service is dead, it restarts
 * it. The VPN permission survives kills and reboots, so no user interaction
 * is needed. OEMs kill services readily but rarely block alarms, so a kill
 * becomes a ≤15-minute gap instead of silent permanent death.
 *
 * Privacy note: this alarm runs entirely on-device and does nothing but check
 * a boolean and restart our own service. Nothing is sent anywhere, ever.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) schedule(ctx)
        if (!GuardianVpnService.wantsProtection(ctx)) return   // user turned it off
        if (GuardianVpnService.isRunning.get()) return          // alive — nothing to do
        if (VpnService.prepare(ctx) != null) return             // permission revoked — needs the app UI
        val svc = Intent(ctx, GuardianVpnService::class.java)
            .setAction(GuardianVpnService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc)
            else ctx.startService(svc)
        } catch (_: Exception) { /* try again on the next tick */ }
    }

    companion object {
        private const val REQ = 1001
        private const val INTERVAL_MS = 15L * 60 * 1000

        private fun pending(ctx: Context): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, REQ, Intent(ctx, WatchdogReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        /** Inexact repeating alarm: battery-friendly, no special permission. */
        fun schedule(ctx: Context) {
            ctx.getSystemService(AlarmManager::class.java).setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + INTERVAL_MS, INTERVAL_MS,
                pending(ctx)
            )
        }

        fun cancel(ctx: Context) {
            ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx))
        }
    }
}
