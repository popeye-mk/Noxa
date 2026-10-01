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
 * v1.8 dangerous-site alert. `threats.gbf` is a second Bloom filter holding
 * only the phishing / scam / malware / cryptomining part of the blocklist.
 * Everything in it is ALSO in the main filter — the connection is blocked
 * either way. This one exists to tell the user WHY, at the moment it
 * matters: "that link is a known scam site, don't type your password".
 *
 * Alerts are rate-limited (once per domain per day, at most a few per
 * hour) so a page stuffed with malicious ads can't flood the shade.
 * Nothing leaves the device.
 */
object Threats {
    const val FILE = "threats.gbf"
    const val LABEL = "Dangerous site · Scam/malware"
    private const val TAG = "Guardian"
    private const val CHANNEL_ID = "guardian_alerts"
    private const val NOTIF_BASE = 200
    private const val PER_DOMAIN_MS = 24L * 60 * 60 * 1000
    private const val MAX_PER_HOUR = 4

    @Volatile private var filter: BloomFilter? = null
    private val lastByDomain = ConcurrentHashMap<String, Long>()
    private val recent = ArrayDeque<Long>()

    val items: Long get() = filter?.items ?: 0L

    /** Downloaded copy (weekly refresh) if newer, else the bundled asset. */
    fun load(ctx: Context) {
        filter = try {
            val f = File(ctx.filesDir, FILE)
            if (f.exists() && f.length() > 24 && FilterUpdater.downloadedIsNewer(ctx))
                runCatching { BloomFilter.load(f.inputStream()) }.getOrNull()
                    ?: BloomFilter.load(ctx.assets.open(FILE))
            else BloomFilter.load(ctx.assets.open(FILE))
        } catch (e: Exception) { Log.w(TAG, "threat filter unavailable: $e"); null }
        Log.i(TAG, "threat filter: ${items} domains")
    }

    /** Only meaningful for a domain the MAIN filter already blocked. */
    fun matches(host: String): Boolean = filter?.matchesHostOrParent(host) ?: false

    /** Should we show an alert for this domain now? (rate limits) */
    fun shouldAlert(domain: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(recent) {
        val key = LiveActivity.siteOf(domain)
        val last = lastByDomain[key]                     // null = never alerted
        if (last != null && now - last < PER_DOMAIN_MS) return false
        while (recent.isNotEmpty() && now - recent.first() > 60L * 60 * 1000) recent.removeFirst()
        if (recent.size >= MAX_PER_HOUR) return false
        lastByDomain[key] = now
        recent.addLast(now)
        true
    }

    fun alert(ctx: Context, pkg: String, domain: String) {
        if (!shouldAlert(domain)) return
        try {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Security alerts", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val app = appName(ctx, pkg)
            val browser = pkg == AppStats.UNKNOWN || looksLikeBrowser(ctx, pkg)
            val title = if (browser) "Dangerous site blocked" else "⚠ $app reached a dangerous server"
            val body = if (browser)
                "$domain is a known scam, phishing or malware site. Noxa blocked it. " +
                "Don't enter passwords or card details there — if you got this link in a " +
                "message or email, it's likely a scam."
            else
                "$app tried to contact $domain, a known scam/malware server, and Noxa blocked it. " +
                "Apps you trust rarely do this. If it keeps happening, consider removing the app " +
                "(Per-app details shows what else it reaches)."
            val open = PendingIntent.getActivity(ctx, NOTIF_BASE, Intent(ctx, LiveActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)
            mgr.notify(NOTIF_BASE + (domain.hashCode() and 0x7FFF), Notification.Builder(ctx, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        } catch (e: Exception) { Log.w(TAG, "threat alert failed: $e") }
    }

    private fun appName(ctx: Context, pkg: String): String =
        if (pkg == AppStats.UNKNOWN) "An app" else try {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }

    private fun looksLikeBrowser(ctx: Context, pkg: String): Boolean = try {
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com/"))
        ctx.packageManager.queryIntentActivities(i, 0).any { it.activityInfo.packageName == pkg }
    } catch (_: Exception) { false }
}
