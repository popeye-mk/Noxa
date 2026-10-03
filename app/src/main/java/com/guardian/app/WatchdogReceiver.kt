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
        HealthMonitor.check(ctx)                                // v1.11: the check-up runs by itself
        if (GuardianVpnService.isRunning.get()) return          // alive — nothing to do
        if (GuardianVpnService.isPaused(ctx)) return            // "Pause 5 min" still running
        if (TunnelController.isUp) return   // the user's TUNNEL holds the VPN slot — never steal it
        if (VpnService.prepare(ctx) != null) {                  // permission revoked — needs the app UI
            notifyStopped(ctx)                                  // v1.8: never a silent gap
            return
        }
        val svc = Intent(ctx, GuardianVpnService::class.java)
            .setAction(GuardianVpnService.ACTION_START)
            // A planned resume (end of "Pause 5 min", self-heal) or a reboot isn't a kill.
            .putExtra(GuardianVpnService.EXTRA_FROM_WATCHDOG,
                !intent.getBooleanExtra(EXTRA_RESUME, false) && intent.action != Intent.ACTION_BOOT_COMPLETED)
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc)
            else ctx.startService(svc)
        } catch (_: Exception) { /* try again on the next tick */ }
    }

    /** Protection is wanted but can't restart by itself: say so, once. */
    private fun notifyStopped(ctx: Context) {
        try {
            val mgr = ctx.getSystemService(android.app.NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                mgr.createNotificationChannel(android.app.NotificationChannel(
                    "guardian_alerts", "Security alerts", android.app.NotificationManager.IMPORTANCE_HIGH))
            }
            val open = PendingIntent.getActivity(ctx, 5, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            mgr.notify(5, android.app.Notification.Builder(ctx, "guardian_alerts")
                .setContentTitle("Noxa protection stopped")
                .setContentText("Android removed Noxa's VPN permission. Tap to turn protection back on.")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (_: Exception) {}
    }

    companion object {
        private const val REQ = 1001
        private const val REQ_RESUME = 1002
        private const val EXTRA_RESUME = "resume"
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

        /** v1.5 "Pause 5 min": one extra alarm that ends the pause on time
         *  (the 15-min watchdog alone could leave it off for up to 20 min). */
        fun scheduleResume(ctx: Context, afterMs: Long, wakeup: Boolean = true) {
            val pi = PendingIntent.getBroadcast(
                ctx, REQ_RESUME, Intent(ctx, WatchdogReceiver::class.java).putExtra(EXTRA_RESUME, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val at = SystemClock.elapsedRealtime() + afterMs + 1000   // just past the pause
            val am = ctx.getSystemService(AlarmManager::class.java)
            // wakeup=true: the user asked for a timed resume (Pause 5 min) — honour it.
            // wakeup=false: self-heal — fire when the phone is next awake anyway,
            // never wake the CPU just for this.
            val type = if (wakeup) AlarmManager.ELAPSED_REALTIME_WAKEUP else AlarmManager.ELAPSED_REALTIME
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(type, at, pi)
            else am.set(type, at, pi)
        }

        fun cancel(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            am.cancel(pending(ctx))
            am.cancel(PendingIntent.getBroadcast(
                ctx, REQ_RESUME, Intent(ctx, WatchdogReceiver::class.java).putExtra(EXTRA_RESUME, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
    }
}
