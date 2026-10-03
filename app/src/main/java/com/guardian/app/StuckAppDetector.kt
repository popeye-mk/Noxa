package com.guardian.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.11 "this app seems blocked". Blocking an app's TRACKERS doesn't break
 * it; blocking the app's OWN server does (seen with Disney+, whose own
 * disneystreaming.com was blocked). So the signal is: Noxa blocked a site
 * that belongs to the app itself — its name matches the app's package
 * (com.viber.voip -> *.viber.com) — at least twice in a few minutes, while
 * nothing else from the app got through. Then one offer to fix it, at most
 * once a week per app; the phone's own system apps are never nagged about.
 *
 * Only blocks Noxa made on its own judgement count (tracker lists, CNAME
 * uncloaking, strict mode). The user's own choices (firewall, "My blocked
 * sites") and real dangers (scam, malware, spyware, fake sites) never
 * trigger an "allow it?" offer.
 */
object StuckAppDetector {
    private const val WINDOW_MS = 3 * 60_000L
    private const val MIN_OWN_BLOCKS = 2
    private const val WEEK = 7L * 24 * 60 * 60 * 1000
    private const val CHANNEL_ID = "guardian_help"
    private const val NOTIF_BASE = 400

    private class Hit(val t: Long, val own: Boolean, val blocked: Boolean)

    private val recent = ConcurrentHashMap<String, ArrayDeque<Hit>>()
    private val lastOffer = ConcurrentHashMap<String, Long>()

    /** Labels that mean "dangerous" or "the user chose this": never offer to allow. */
    private val NEVER_OFFER = listOf("Stalkerware", "Dangerous site", "Blocked by you", "DNS-over-HTTPS")

    /** Package-name parts too generic to identify an app's own site. */
    private val GENERIC = setOf("com", "org", "net", "app", "apps", "android", "mobile", "free", "pro",
        "lite", "the", "main", "client", "official", "www", "inc", "ltd", "global", "games", "game")

    /** Pure, unit-tested: does [domain] look like [pkg]'s own site? */
    fun isOwnSite(pkg: String, domain: String): Boolean {
        val site = LiveActivity.siteOf(domain).substringBefore('.').lowercase()
        if (site.length < 4) return false
        return pkg.lowercase().split('.', '_').filter { it.length >= 4 && it !in GENERIC }
            .any { part -> site == part || site.contains(part) || part.contains(site) }
    }

    /** Feed every lookup. Returns true when [pkg] just started looking stuck. */
    fun observe(pkg: String, domain: String, verdict: LiveLog.Verdict, label: String,
                now: Long = System.currentTimeMillis()): Boolean {
        if (pkg == AppStats.UNKNOWN) return false
        val counts = when (verdict) {
            LiveLog.Verdict.ALLOWED -> true
            LiveLog.Verdict.BLOCKED, LiveLog.Verdict.CLOAKED -> NEVER_OFFER.none { label.contains(it) }
            else -> false                                    // FIREWALL / MINE: user's choice
        }
        if (!counts) return false
        val blocked = verdict != LiveLog.Verdict.ALLOWED
        val q = recent.getOrPut(pkg) { ArrayDeque() }
        synchronized(q) {
            q.addLast(Hit(now, blocked && isOwnSite(pkg, domain), blocked))
            while (q.isNotEmpty() && now - q.first().t > WINDOW_MS) q.removeFirst()
            if (q.any { !it.blocked }) return false          // something got through: not stuck
            if (q.count { it.own } < MIN_OWN_BLOCKS) return false
            val last = lastOffer[pkg]
            if (last != null && now - last < WEEK) return false
            lastOffer[pkg] = now
            q.clear()
            return true
        }
    }

    fun offer(ctx: Context, pkg: String) {
        try {
            val ai = try { ctx.packageManager.getApplicationInfo(pkg, 0) } catch (_: Exception) { return }
            if (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) return   // phone's own apps: never nag
            val name = try { ctx.packageManager.getApplicationLabel(ai).toString() } catch (_: Exception) { return }
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Help & tips", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(ctx, NOTIF_BASE + (pkg.hashCode() and 0x3F),
                Intent(ctx, AppsActivity::class.java).putExtra(AppsActivity.EXTRA_FIX_PKG, pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val text = "Noxa blocked $name's own server, and nothing else it tried got through. " +
                "If $name isn't working, tap to allow just what it needs."
            mgr.notify(NOTIF_BASE + (pkg.hashCode() and 0x3F), Notification.Builder(ctx, CHANNEL_ID)
                .setContentTitle("$name may not be working")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (_: Exception) {}
    }
}
