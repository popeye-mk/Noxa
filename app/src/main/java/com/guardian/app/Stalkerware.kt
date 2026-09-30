package com.guardian.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.8 stalkerware alert. Stalkerware is spyware someone installs on another
 * person's phone (a partner, a parent, an employer) to read messages, track
 * location, record calls. It phones home to a small set of known servers —
 * UT1's `stalkerware` category, ~525 domains, bundled as `stalkerware.txt`
 * and refreshed by the weekly rebuild.
 *
 * Those domains are in the main filter, so the connection is ALREADY blocked.
 * What the main filter can't do is tell the user something is wrong — this
 * does: a one-time-per-app-per-day notification naming the app that tried.
 *
 * Honest wording matters: a browser visiting a spy-app's marketing site
 * trips this too, so the alert says so and tells the user what to look at.
 * Nothing leaves the device; the check is a local set lookup.
 */
object Stalkerware {
    const val FILE = "stalkerware.txt"
    const val LABEL = "Stalkerware · Spyware"
    private const val TAG = "Guardian"
    private const val CHANNEL_ID = "guardian_alerts"
    private const val NOTIF_BASE = 100
    private const val ALERT_COOLDOWN_MS = 24L * 60 * 60 * 1000

    @Volatile private var domains: Set<String> = emptySet()
    private val lastAlert = ConcurrentHashMap<String, Long>()   // pkg -> time

    val size: Int get() = domains.size

    /** Prefer a downloaded list (refreshed weekly by FilterUpdater) over the
     *  one bundled in the APK, using the same "only if newer" rule as the
     *  main filter. Safe to call again at any time (live swap). */
    fun load(ctx: Context) {
        val f = File(ctx.filesDir, FILE)
        val text = try {
            if (f.exists() && f.length() > 0 && FilterUpdater.downloadedIsNewer(ctx)) f.readText()
            else ctx.assets.open(FILE).bufferedReader().use { it.readText() }
        } catch (e: Exception) { Log.w(TAG, "stalkerware list unavailable: $e"); "" }
        domains = parse(text)
        Log.i(TAG, "stalkerware list: ${domains.size} domains")
    }

    fun parse(text: String): Set<String> =
        text.lineSequence().map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('.') }
            .toHashSet()

    /** True if [host] or a parent domain is a known stalkerware server. */
    fun matches(host: String): Boolean = matchesIn(domains, host)

    fun matchesIn(set: Set<String>, host: String): Boolean {
        if (set.isEmpty()) return false
        val h = host.trim().lowercase().removeSuffix(".")
        var start = 0
        while (true) {
            val dot = h.indexOf('.', start)
            if (dot < 0) return false
            if (set.contains(if (start == 0) h else h.substring(start))) return true
            start = dot + 1
        }
    }

    /** Show the warning, at most once per app per day. */
    fun alert(ctx: Context, pkg: String, domain: String) {
        val now = System.currentTimeMillis()
        val last = lastAlert[pkg] ?: 0L
        if (now - last < ALERT_COOLDOWN_MS) return
        lastAlert[pkg] = now
        try {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Security alerts", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val app = appName(ctx, pkg)
            val isBrowser = looksLikeBrowser(ctx, pkg)
            val unknown = pkg == AppStats.UNKNOWN
            val body = if (unknown)
                "Something on this phone reached $domain, which is on the stalkerware " +
                "(spyware) list — Noxa blocked it but couldn't tell which app asked. " +
                "If you were just browsing, that's probably it. If not, open " +
                "\"Watch it live\" and see which app repeats it."
            else if (isBrowser)
                "$app reached $domain, which is on the stalkerware (spyware) list. " +
                "For a browser this usually just means a page you visited — nothing to do " +
                "unless you didn't expect it. Noxa blocked the connection."
            else
                "$app tried to contact $domain, a known stalkerware (spyware) server. " +
                "Noxa blocked it. If you don't recognise this app or didn't install it, " +
                "someone may be monitoring this phone: check Settings → Apps, and consider " +
                "uninstalling it or asking someone you trust for help."
            val open = PendingIntent.getActivity(ctx, NOTIF_BASE, Intent(ctx, AppsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, CHANNEL_ID)
                .setContentTitle(if (isBrowser || unknown) "Spyware server reached (blocked)" else "⚠ Possible spyware on this phone")
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            mgr.notify(NOTIF_BASE + (pkg.hashCode() and 0x7FFF), n)
        } catch (e: Exception) { Log.w(TAG, "stalkerware alert failed: $e") }
    }

    private fun appName(ctx: Context, pkg: String): String =
        if (pkg == AppStats.UNKNOWN) "An app (couldn't tell which)"
        else try {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }

    /** Best effort: does this package handle http(s) links (i.e. is a browser)? */
    private fun looksLikeBrowser(ctx: Context, pkg: String): Boolean = try {
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com/"))
        ctx.packageManager.queryIntentActivities(i, 0).any { it.activityInfo.packageName == pkg }
    } catch (_: Exception) { false }
}
