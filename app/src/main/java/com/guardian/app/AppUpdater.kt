package com.guardian.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * v1.8 "a newer Noxa is available". Noxa isn't in an app store, so nobody
 * tells users about new versions — this does, once a day, by reading the
 * public GitHub Releases page (the same host the blocklist already comes
 * from). One anonymous GET; nothing about the user is sent. Tapping the
 * notification opens the release page in the browser; the user installs
 * it themselves — Noxa never downloads or installs code by itself.
 */
object AppUpdater {
    private const val TAG = "Guardian"
    private const val LATEST_URL = "https://api.github.com/repos/popeye-mk/Noxa/releases/latest"
    private const val PREFS = "guardian_update"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_NOTIFIED = "notified_tag"
    private const val CHANNEL_ID = "guardian_updates"
    private const val NOTIF_ID = 3

    /** Quiet once-a-day check. Safe to call often. */
    fun autoCheck(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - p.getLong(KEY_LAST_CHECK, 0L) < 24L * 60 * 60 * 1000) return
        p.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        Thread {
            try {
                val latest = fetchLatest() ?: return@Thread
                if (isNewer(latest.version, installedVersion(ctx)) && p.getString(KEY_NOTIFIED, "") != latest.tag) {
                    p.edit().putString(KEY_NOTIFIED, latest.tag).apply()
                    notify(ctx, latest)
                }
            } catch (e: Exception) { Log.w(TAG, "update check: $e") }
        }.start()
    }

    /** Manual check from the tools menu; returns a message. Off the main thread. */
    fun checkNow(ctx: Context): String {
        val latest = fetchLatest() ?: return "Couldn't reach the releases page."
        val mine = installedVersion(ctx)
        return if (isNewer(latest.version, mine)) {
            notify(ctx, latest)
            "Noxa ${latest.version} is available (you have $mine) — see the notification."
        } else "You have the latest version ($mine)."
    }

    class Release(val tag: String, val version: String, val url: String)

    private fun fetchLatest(): Release? = try {
        val c = URL(LATEST_URL).openConnection() as HttpsURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode != 200) { c.errorStream?.close(); null }
        else {
            val o = JSONObject(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
            val tag = o.optString("tag_name", "")
            if (tag.isEmpty() || o.optBoolean("prerelease", false) || o.optBoolean("draft", false)) null
            else Release(tag, tag.removePrefix("v"), o.optString("html_url", "https://github.com/popeye-mk/Noxa/releases/latest"))
        }
    } catch (e: Exception) { Log.w(TAG, "releases fetch: $e"); null }

    fun installedVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (_: Exception) { "0" }

    /** "1.10" > "1.9"; ignores suffixes like "-test". */
    fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun notify(ctx: Context, r: Release) {
        try {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val open = PendingIntent.getActivity(ctx, NOTIF_ID,
                Intent(Intent.ACTION_VIEW, Uri.parse(r.url)), PendingIntent.FLAG_IMMUTABLE)
            mgr.notify(NOTIF_ID, Notification.Builder(ctx, CHANNEL_ID)
                .setContentTitle("Noxa ${r.version} is available")
                .setContentText("Tap to open the download page.")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        } catch (e: Exception) { Log.w(TAG, "update notify: $e") }
    }
}
