package com.guardian.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.16 firmware-malware watch (security review of 19 Nov 2026).
 *
 * A cheap tablet's own software kept contacting scam and spam servers: malware
 * in the firmware, which no app store scan sees and no uninstall removes. Noxa
 * blocked it, but said so only with the same gentle alert any app gets. Now an
 * app that CAME WITH THE DEVICE (a system app, not a browser) and reaches
 * known scam / malware / spyware servers on several different days is named
 * plainly, with one tap to cut it off the internet completely.
 *
 * Only days are kept (which days, per app) — never the sites.
 */
object FirmwareWatch {
    private const val PREFS = "guardian_firmware"
    private const val KEY_DAYS = "days"            // {pkg: [dayNumber, ...]}
    private const val KEY_TOLD = "told_"           // + pkg -> time of the last alert
    private const val DAY = 24L * 60 * 60 * 1000
    private const val WINDOW_DAYS = 14L
    const val MIN_DAYS = 3
    private const val RETELL_MS = 7 * DAY
    private const val CHANNEL_ID = "guardian_alerts"
    private const val NOTIF_BASE = 520

    private val system = ConcurrentHashMap<String, Boolean>()     // pkg -> came with the device, not a browser
    private val seenToday = ConcurrentHashMap<String, Long>()     // pkg -> day already recorded (no disk write per hit)

    fun dayOf(ms: Long): Long = ms / DAY

    /** Pure, unit-tested: [days] plus [today], without duplicates, last 30 days only. */
    fun note(days: List<Long>, today: Long): List<Long> =
        (days + today).filter { today - it in 0L..29L }.distinct().sorted()

    /** Pure, unit-tested: dangerous servers on [MIN_DAYS] different days within two weeks. */
    fun suspicious(days: Collection<Long>, today: Long): Boolean =
        days.count { today - it in 0L until WINDOW_DAYS } >= MIN_DAYS

    /** Pure, unit-tested: tell again only after a week. */
    fun shouldTell(lastTold: Long, now: Long): Boolean = now - lastTold >= RETELL_MS

    /** Called when [pkg] was just stopped from reaching a scam / malware / spyware server. Cheap. */
    fun record(ctx: Context, pkg: String, now: Long = System.currentTimeMillis()) {
        if (pkg == AppStats.UNKNOWN || pkg == ctx.packageName) return
        val today = dayOf(now)
        if (seenToday[pkg] == today) return
        if (!cameWithDevice(ctx, pkg)) return
        seenToday[pkg] = today
        try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val all = JSONObject(p.getString(KEY_DAYS, "{}") ?: "{}")
            val days = note(longs(all.optJSONArray(pkg)), today)
            all.put(pkg, JSONArray(days))
            p.edit().putString(KEY_DAYS, all.toString()).apply()
            if (suspicious(days, today) && shouldTell(p.getLong(KEY_TOLD + pkg, 0L), now)) {
                p.edit().putLong(KEY_TOLD + pkg, now).apply()
                tell(ctx, pkg, days.count { today - it in 0L until WINDOW_DAYS })
            }
        } catch (_: Exception) {}
    }

    /** (package, days with dangerous servers in the last two weeks) for the check-up and the home report. */
    fun suspects(ctx: Context, now: Long = System.currentTimeMillis()): List<Pair<String, Int>> = try {
        val today = dayOf(now)
        val all = JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DAYS, "{}") ?: "{}")
        val out = ArrayList<Pair<String, Int>>()
        val keys = all.keys()
        while (keys.hasNext()) {
            val pkg = keys.next()
            val days = longs(all.optJSONArray(pkg))
            if (suspicious(days, today)) out += pkg to days.count { d -> today - d in 0L until WINDOW_DAYS }
        }
        out.sortedByDescending { s -> s.second }
    } catch (_: Exception) { emptyList() }

    private fun longs(a: JSONArray?): List<Long> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optLong(it) }

    /** A system app that is not a browser (a browser only shows what the user opened). */
    private fun cameWithDevice(ctx: Context, pkg: String): Boolean = system.getOrPut(pkg) {
        try {
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && !isBrowser(ctx, pkg)
        } catch (_: Exception) { false }
    }

    private fun isBrowser(ctx: Context, pkg: String): Boolean = try {
        val view = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")).setPackage(pkg)
        ctx.packageManager.queryIntentActivities(view, 0).isNotEmpty()
    } catch (_: Exception) { false }

    private fun tell(ctx: Context, pkg: String, days: Int) {
        try {
            val pm = ctx.packageManager
            val name = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
            val hidden = pm.getLaunchIntentForPackage(pkg) == null && pm.getLeanbackLaunchIntentForPackage(pkg) == null
            val text = "\"$name\" came with this device and tried to reach known scam or malware servers on $days " +
                "different days. Noxa blocked every attempt. " +
                (if (hidden) "It has no icon and runs in the background — that is how malware built into a device behaves. "
                 else "If it is a mail or chat app it may only have shown you a scam link; if you don't know it, don't trust it. ") +
                "Tap to cut it off the internet completely."
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Security alerts", NotificationManager.IMPORTANCE_HIGH))
            val id = NOTIF_BASE + (pkg.hashCode() and 0x3F)
            val open = PendingIntent.getActivity(ctx, id,
                Intent(ctx, AppsActivity::class.java).putExtra(AppsActivity.EXTRA_BLOCK_PKG, pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            mgr.notify(id, Notification.Builder(ctx, CHANNEL_ID)
                .setContentTitle("⚠ This device's own software keeps reaching scam servers")
                .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (_: Exception) {}
    }
}
