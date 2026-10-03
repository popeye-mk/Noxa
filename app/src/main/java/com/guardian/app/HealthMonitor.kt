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

        // 4. v1.11: once a day, quietly look for a pop-up ad app; Sunday evening, the weekly summary.
        dailyAdwareScan(ctx, now)
        weeklySummary(ctx, now)
        newAppReport(ctx, now)
        SpyAppWatch.daily(ctx, now)                 // v1.13
    }

    // --- v1.12: new-app report ("what did it do in its first day?") ---------
    private const val KEY_NEWAPP_CHECK = "newapp_checked"
    private const val KEY_NEWAPP_TOLD = "newapp_told"
    private const val NEWAPP_CHANNEL = "guardian_newapp"

    /** Pure, unit-tested: report an app once it has had a full day (and at
     *  most 3 days), and only if it arrived after Noxa started protecting —
     *  older apps weren't watched from their first minute. */
    fun newAppDue(installed: Long, firstProtected: Long, now: Long): Boolean =
        firstProtected != 0L && installed > firstProtected && now - installed in DAY until 3 * DAY

    /** Pure, unit-tested: (tracking attempts, companies) from one app's
     *  "Company · Category" counts. The user's own blocks don't count. */
    fun trackerSummary(counts: Map<String, Long>): Pair<Long, Int> {
        val t = counts.filterKeys { !it.startsWith("Blocked by you") }
        return t.values.sum() to t.keys.map { Trackers.companyOf(it) }.toSet().size
    }

    fun newAppReport(ctx: Context, now: Long = System.currentTimeMillis()) {
        val p = prefs(ctx)
        if (now - p.getLong(KEY_NEWAPP_CHECK, 0L) < 6 * 60 * 60 * 1000L) return   // every 6 h is plenty
        p.edit().putLong(KEY_NEWAPP_CHECK, now).apply()
        val first = p.getLong(KEY_FIRST, 0L)
        Thread {
            try {
                AppStats.load(ctx)
                val told = (p.getString(KEY_NEWAPP_TOLD, "") ?: "").split(',').filter { it.isNotEmpty() }.toMutableSet()
                val pm = ctx.packageManager
                val fresh = pm.getInstalledPackages(0).filter { pi ->
                    val ai = pi.applicationInfo ?: return@filter false
                    pi.packageName != ctx.packageName && pi.packageName !in told &&
                        ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 &&
                        newAppDue(pi.firstInstallTime, first, now)
                }
                for (pi in fresh) {
                    told += pi.packageName
                    val (n, companies) = trackerSummary(AppStats.companyCounts(pi.packageName))
                    if (n == 0L) continue                     // a clean app: nothing to say
                    val name = pi.applicationInfo?.loadLabel(pm)?.toString() ?: pi.packageName
                    val top = AppStats.companyCounts(pi.packageName)
                        .filterKeys { !it.startsWith("Blocked by you") }
                        .entries.groupBy { Trackers.companyOf(it.key) }
                        .mapValues { e -> e.value.sumOf { it.value } }
                        .entries.sortedByDescending { it.value }.take(3).joinToString(", ") { it.key }
                    val text = "In its first day, $name tried to contact trackers %,d time(s), from $companies ".format(n) +
                        (if (companies == 1) "company" else "companies") + ": $top. Noxa blocked them all."
                    val mgr = ctx.getSystemService(NotificationManager::class.java)
                    if (Build.VERSION.SDK_INT >= 26) mgr.createNotificationChannel(
                        NotificationChannel(NEWAPP_CHANNEL, "New app reports", NotificationManager.IMPORTANCE_LOW))
                    val id = NOTIF_BASE + 70 + (pi.packageName.hashCode() and 0x1F)
                    val open = PendingIntent.getActivity(ctx, id, Intent(ctx, AppsActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE)
                    mgr.notify(id, Notification.Builder(ctx, NEWAPP_CHANNEL)
                        .setContentTitle("$name: first-day report")
                        .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                        .setSmallIcon(android.R.drawable.ic_lock_lock)
                        .setContentIntent(open).setAutoCancel(true).build())
                }
                // Keep the "already told" list small: only apps still inside the window matter.
                p.edit().putString(KEY_NEWAPP_TOLD, told.toList().takeLast(200).joinToString(",")).apply()
            } catch (_: Exception) {}
        }.start()
    }

    // --- v1.11: weekly summary (Sunday evening) ---------------------------
    private const val KEY_WEEKLY = "weekly_sent"
    private const val WEEKLY_CHANNEL = "guardian_weekly"

    /** Pure rule, unit-tested: Sunday 19:00 or later, and not sent in 6 days. */
    fun weeklyDue(dayOfWeek: Int, hour: Int, lastSent: Long, now: Long): Boolean =
        dayOfWeek == java.util.Calendar.SUNDAY && hour >= 19 && now - lastSent > 6 * DAY

    fun weeklySummary(ctx: Context, now: Long = System.currentTimeMillis()) {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val p = prefs(ctx)
        if (!weeklyDue(c.get(java.util.Calendar.DAY_OF_WEEK), c.get(java.util.Calendar.HOUR_OF_DAY),
                p.getLong(KEY_WEEKLY, 0L), now)) return
        DailyStats.load(ctx)
        val days = DailyStats.lastDays(7)
        val total = days.sumOf { it.second }
        if (total == 0L) return
        p.edit().putLong(KEY_WEEKLY, now).apply()
        AppStats.load(ctx)
        val g = AppStats.globalCompanyCounts()
        val danger = g.filterKeys { it.startsWith("Dangerous site") || it.startsWith("Stalkerware") }.values.sum()
        val top = g.filterKeys { !it.startsWith("Dangerous") && !it.startsWith("Stalkerware") && !it.startsWith("Blocked by you") }
            .entries.groupBy { Trackers.companyOf(it.key) }.mapValues { e -> e.value.sumOf { it.value } }
            .maxByOrNull { it.value }?.key
        val busiest = days.maxByOrNull { it.second }!!
        val text = "Noxa blocked %,d tracking attempts this week".format(total) +
            (if (top != null) " — most from $top" else "") + ". Busiest day: ${busiest.first}." +
            (if (danger > 0) " Also stopped $danger scam/spyware connection(s) this month." else "")
        try {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) mgr.createNotificationChannel(
                NotificationChannel(WEEKLY_CHANNEL, "Weekly summary", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(ctx, NOTIF_BASE + 60, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)
            mgr.notify(NOTIF_BASE + 60, Notification.Builder(ctx, WEEKLY_CHANNEL)
                .setContentTitle("Your week with Noxa")
                .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (_: Exception) {}
    }

    // --- v1.11: automatic pop-up ad check (once a day) ----------------------
    private const val KEY_ADWARE_SCAN = "adware_scanned"
    private const val KEY_ADWARE_TOLD = "adware_told"     // pkgs already reported
    const val ADWARE_AUTO_THRESHOLD = 5                    // stricter than the manual screen (3)

    fun dailyAdwareScan(ctx: Context, now: Long = System.currentTimeMillis()) {
        val p = prefs(ctx)
        if (now - p.getLong(KEY_ADWARE_SCAN, 0L) < DAY) return
        p.edit().putLong(KEY_ADWARE_SCAN, now).apply()
        Thread {
            try {
                AppStats.load(ctx)
                val told = (p.getString(KEY_ADWARE_TOLD, "") ?: "").split(',').filter { it.isNotEmpty() }.toSet()
                val fresh = AdwareScan.scan(ctx).filter { it.score >= ADWARE_AUTO_THRESHOLD && it.pkg !in told }
                if (fresh.isEmpty()) return@Thread
                p.edit().putString(KEY_ADWARE_TOLD, (told + fresh.map { it.pkg }).joinToString(",")).apply()
                val s = fresh.first()
                val more = if (fresh.size > 1) " (and ${fresh.size - 1} more)" else ""
                val text = "${s.name}$more looks like it could be showing pop-up ads: " +
                    s.reasons.take(2).joinToString("; ").lowercase() + ". Tap to see why and remove it if you don't need it."
                val mgr = ctx.getSystemService(NotificationManager::class.java)
                if (Build.VERSION.SDK_INT >= 26) mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Security alerts", NotificationManager.IMPORTANCE_HIGH))
                val open = PendingIntent.getActivity(ctx, NOTIF_BASE + 61, Intent(ctx, AdwareActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE)
                mgr.notify(NOTIF_BASE + 61, Notification.Builder(ctx, CHANNEL_ID)
                    .setContentTitle("Possible pop-up ad app found")
                    .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentIntent(open).setAutoCancel(true).build())
            } catch (_: Exception) {}
        }.start()
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
