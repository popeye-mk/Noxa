package com.guardian.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * v1.11: the check-up runs by itself. The watchdog ticks every ~15 min; each
 * tick (and each protection start) calls [check], which notifies only when
 * something actually went wrong — at most once a day per problem. Nobody has
 * to open the check-up screen to find out protection got weaker.
 *
 * It also learns whether the phone's battery manager kills Noxa: every
 * unexpected restart is recorded, so "allowed to run in the background" can
 * be judged from what really happens instead of an API that OEM phones
 * (Xiaomi, Huawei, ...) don't report honestly.
 */
object HealthMonitor {
    private const val PREFS = "guardian_health"
    private const val KEY_FIRST = "first_protected"
    private const val KEY_LAST_KILL = "last_kill"
    private const val KEY_KILLS = "kill_times"          // comma-separated, last 24 h
    private const val KEY_AO_SEEN = "always_on_seen"
    private const val KEY_NOTIFIED = "notified_"         // + issue id -> time
    private const val DAY = 24L * 60 * 60 * 1000
    private const val CHANNEL_ID = "guardian_alerts"
    private const val NOTIF_BASE = 300

    /** Protection started (normal start). */
    fun onProtectionStarted(ctx: Context, now: Long = System.currentTimeMillis()) {
        val p = prefs(ctx)
        if (p.getLong(KEY_FIRST, 0L) == 0L) p.edit().putLong(KEY_FIRST, now).apply()
    }

    /** Protection was wanted but found dead (OS kill / battery manager). */
    fun recordKill(ctx: Context, now: Long = System.currentTimeMillis()) {
        val p = prefs(ctx)
        val recent = parseKills(p.getString(KEY_KILLS, "") ?: "").filter { now - it < DAY } + now
        p.edit().putLong(KEY_LAST_KILL, now).putString(KEY_KILLS, recent.joinToString(",")).apply()
    }

    fun killsLastDay(ctx: Context, now: Long = System.currentTimeMillis()): Int =
        parseKills(prefs(ctx).getString(KEY_KILLS, "") ?: "").count { now - it < DAY }

    /** True once protection has run 3+ days with no unexpected stop in the
     *  last 3 days: proof the battery manager leaves Noxa alone. */
    fun batteryProvenOk(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        val p = prefs(ctx)
        return batteryProvenOk(p.getLong(KEY_FIRST, 0L), p.getLong(KEY_LAST_KILL, 0L), now)
    }

    /** Pure rule, unit-tested. */
    fun batteryProvenOk(first: Long, lastKill: Long, now: Long): Boolean =
        first != 0L && now - first >= 3 * DAY && (lastKill == 0L || now - lastKill >= 3 * DAY)

    /** Look for problems and notify about new ones. Cheap; safe to call often. */
    fun check(ctx: Context, now: Long = System.currentTimeMillis()) {
        if (!GuardianVpnService.wantsProtection(ctx)) return     // user turned it off: nothing to nag about
        val p = prefs(ctx)

        // 1. Always-on VPN was on, and now it's off.
        GuardianVpnService.liveAlwaysOn()?.let { (on, _) ->
            if (on) p.edit().putBoolean(KEY_AO_SEEN, true).apply()
            else if (p.getBoolean(KEY_AO_SEEN, false)) {
                p.edit().putBoolean(KEY_AO_SEEN, false).apply()
                notifyOnce(ctx, "always_on", now,
                    "Always-on VPN was switched off",
                    "Noxa won't restart by itself after a reboot until it's back on. Tap to fix.")
            }
        }

        // 2. The phone keeps stopping Noxa.
        if (killsLastDay(ctx, now) >= 3 && !batteryOkBySystem(ctx))
            notifyOnce(ctx, "killed", now,
                "Your phone keeps stopping Noxa",
                "It was stopped ${killsLastDay(ctx, now)} times today to save battery. " +
                "Tap, then set Noxa's battery option to \"No restrictions\".")

        // 3. The blocklist hasn't updated for two weeks (try once more first).
        val age = CheckupActivity.filterAgeDays(FilterUpdater.currentBuiltAt(ctx), now)
        if (age > 14) {
            FilterUpdater.autoCheck(ctx)
            notifyOnce(ctx, "stale", now,
                "Blocklist is $age days old",
                "Noxa couldn't download the weekly update. It keeps trying; tap to check your connection.")
        }
    }

    private fun batteryOkBySystem(ctx: Context): Boolean = try {
        ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (_: Exception) { false }

    private fun notifyOnce(ctx: Context, id: String, now: Long, title: String, text: String) {
        val p = prefs(ctx)
        if (now - p.getLong(KEY_NOTIFIED + id, 0L) < DAY) return
        p.edit().putLong(KEY_NOTIFIED + id, now).apply()
        try {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Security alerts", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(ctx, NOTIF_BASE + id.hashCode() % 50,
                Intent(ctx, CheckupActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            mgr.notify(NOTIF_BASE + (id.hashCode() and 0x3F), Notification.Builder(ctx, CHANNEL_ID)
                .setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (_: Exception) {}
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun parseKills(s: String): List<Long> = s.split(',').mapNotNull { it.trim().toLongOrNull() }
}
