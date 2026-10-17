package com.guardian.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * v1.14: the home-server report has its own hourly alarm, independent of the
 * protection watchdog. The watchdog's alarm is cancelled when the user switches
 * protection off — and exactly then the home server should hear "protection is
 * off on this device" instead of silence. Inexact, battery-friendly, no
 * permission; set when the report is switched on and after a reboot, cancelled
 * when it is switched off. Nothing is sent unless the report is on (HomeReport.tick).
 */
class HomeReportReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        HomeReport.tick(ctx)
    }

    companion object {
        private const val REQ = 1101
        private const val INTERVAL_MS = 60L * 60 * 1000

        private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, REQ, Intent(ctx, HomeReportReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun schedule(ctx: Context) {
            if (!HomeReport.isOn(ctx)) return
            ctx.getSystemService(AlarmManager::class.java).setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + INTERVAL_MS, INTERVAL_MS, pending(ctx))
        }

        fun cancel(ctx: Context) {
            ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx))
        }
    }
}
