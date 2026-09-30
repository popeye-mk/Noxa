package com.guardian.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.widget.RemoteViews

/**
 * v1.6 home-screen widget: "Protected · 1,234 blocked today" plus an on/off
 * button — protection at a glance without opening the app.
 *
 * Tapping the button acts directly (start/stop the service). When the user
 * must decide something first (VPN permission, tunnel mode), it opens the app.
 * Refreshed by the service on start/stop and every 30 s while running.
 */
class NoxaWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        DailyStats.load(ctx)
        for (id in ids) mgr.updateAppWidget(id, views(ctx))
    }

    companion object {
        fun refreshAll(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx) ?: return
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, NoxaWidget::class.java))
                if (ids.isEmpty()) return            // no widget placed: zero cost
                val v = views(ctx)
                for (id in ids) mgr.updateAppWidget(id, v)
            } catch (_: Exception) {}
        }

        private fun views(ctx: Context): RemoteViews {
            val on = GuardianVpnService.isRunning.get()
            val tunnel = TunnelController.isUp
            val paused = !on && GuardianVpnService.isPaused(ctx)
            val v = RemoteViews(ctx.packageName, R.layout.widget_noxa)
            v.setTextViewText(R.id.widget_status, when {
                tunnel -> "Protected · tunnel"
                on -> "Protected"
                paused -> "Paused"
                else -> "Off"
            })
            v.setTextColor(R.id.widget_status, Color.parseColor(when {
                tunnel || on -> "#4CC38A"
                paused -> "#E5A84D"
                else -> "#8FA0BC"
            }))
            v.setTextViewText(R.id.widget_count,
                "%,d blocked today".format(DailyStats.today()))
            v.setTextViewText(R.id.widget_button, if (on || tunnel) "Turn off" else "Turn on")

            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val openApp = PendingIntent.getActivity(ctx, 10, Intent(ctx, MainActivity::class.java), flags)
            v.setOnClickPendingIntent(R.id.widget_root, openApp)

            val svc = Intent(ctx, GuardianVpnService::class.java)
            val button: PendingIntent = when {
                // The user must see something first -> the app handles it.
                tunnel || VpnService.prepare(ctx) != null -> openApp
                on -> PendingIntent.getService(ctx, 11,
                    svc.setAction(GuardianVpnService.ACTION_STOP), flags)
                Build.VERSION.SDK_INT >= 26 -> PendingIntent.getForegroundService(ctx, 12,
                    svc.setAction(GuardianVpnService.ACTION_START), flags)
                else -> PendingIntent.getService(ctx, 12,
                    svc.setAction(GuardianVpnService.ACTION_START), flags)
            }
            v.setOnClickPendingIntent(R.id.widget_button, button)
            return v
        }
    }
}
