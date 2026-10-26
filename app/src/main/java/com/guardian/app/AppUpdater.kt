package com.guardian.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
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

    /**
     * SHA-256 of the certificate that signs the APKs on our GitHub Releases
     * (as printed by `apksigner verify --print-certs`). Only a copy signed with
     * THIS key can install a GitHub APK as an update. Builds signed by anyone
     * else — F-Droid (which builds from source and signs with its own key), the
     * "Noxa TEST" debug builds — must never point the user at a GitHub APK:
     * Android would refuse it ("package conflicts with an existing package").
     * Those copies get their updates from wherever they were installed.
     */
    const val RELEASE_CERT_SHA256 =
        "cd214e2306b44306adf3bab3328c2aef1ab4275b720d8d01ff45a7b65c3af685"

    /** True when this installed copy is signed with our GitHub release key. */
    fun signedWithReleaseKey(ctx: Context): Boolean = try {
        matchesReleaseCert(installedCertSha256s(ctx), RELEASE_CERT_SHA256)
    } catch (e: Exception) { Log.w(TAG, "cert check: $e"); false }   // unsure → stay quiet

    /** Lower-case hex SHA-256 of each signing certificate of this app. */
    private fun installedCertSha256s(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val sigs = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val si = info.signingInfo ?: return emptyList()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        return sigs?.map { sha256Hex(it.toByteArray()) } ?: emptyList()
    }

    /** Pure, unit-tested: does any installed cert equal the expected one?
     *  Accepts "AB:CD:…" (keytool style) or plain hex, any case. */
    fun matchesReleaseCert(installed: List<String>, expected: String): Boolean {
        fun norm(s: String) = s.replace(":", "").replace(" ", "").lowercase()
        val want = norm(expected)
        if (want.length != 64 || !want.all { it in "0123456789abcdef" }) return false
        return installed.any { norm(it) == want }
    }

    /** Pure, unit-tested (audit of 26 Oct): may this downloaded APK go to the installer? Android already refuses a
     *  copy signed with another key over Noxa — but an APK with another package name would install as a second app
     *  through this trusted button, and the .sha256 comes from the same release as the APK. So: our package, newer
     *  than this copy, signed with our release key. */
    fun apkAcceptable(pkg: String?, versionCode: Long, certs: List<String>, ourPkg: String, ourVersion: Long): Boolean =
        pkg == ourPkg && versionCode > ourVersion && matchesReleaseCert(certs, RELEASE_CERT_SHA256)

    /** Package, version code and signing-cert SHA-256s of an APK file that is not installed (null when unreadable). */
    fun archiveInfo(ctx: Context, path: String): Triple<String, Long, List<String>>? = try {
        val pm = ctx.packageManager
        val info = if (android.os.Build.VERSION.SDK_INT >= 28)
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        else @Suppress("DEPRECATION") pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        if (info == null) null else {
            val sigs = if (android.os.Build.VERSION.SDK_INT >= 28) {
                val si = info.signingInfo
                if (si == null) null else if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            } else @Suppress("DEPRECATION") info.signatures
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
                       else @Suppress("DEPRECATION") info.versionCode.toLong()
            Triple(info.packageName, code, sigs?.map { sha256Hex(it.toByteArray()) } ?: emptyList())
        }
    } catch (e: Exception) { Log.w(TAG, "apk check: $e"); null }

    /** This installed copy's version code. */
    fun installedVersionCode(ctx: Context): Long = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: Exception) { Long.MAX_VALUE }        // unsure → nothing counts as newer

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Quiet once-a-day check. Safe to call often. */
    fun autoCheck(ctx: Context) {
        if (!signedWithReleaseKey(ctx)) return   // F-Droid / test build: not our updates
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
        if (!signedWithReleaseKey(ctx))
            return "This copy of Noxa gets its updates from where you installed it (e.g. F-Droid)."
        val latest = fetchLatest() ?: return "Couldn't reach the releases page."
        val mine = installedVersion(ctx)
        return if (isNewer(latest.version, mine)) {
            notify(ctx, latest)
            "Noxa ${latest.version} is available (you have $mine) — see the notification."
        } else "You have the latest version ($mine)."
    }

    class Release(val tag: String, val version: String, val url: String,
                  val apkUrl: String = "", val shaUrl: String = "")

    /** Pure, unit-tested: the APK and its .sha256 among a release's assets ("" when absent). */
    fun assetUrls(release: JSONObject): Pair<String, String> {
        var apk = ""; var sha = ""
        val assets = release.optJSONArray("assets") ?: return apk to sha
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name"); val url = a.optString("browser_download_url")
            if (!url.startsWith("https://github.com/popeye-mk/Noxa/releases/download/")) continue
            if (name.startsWith("noxa-") && name.endsWith(".apk")) apk = url
            if (name.startsWith("noxa-") && name.endsWith(".apk.sha256")) sha = url
        }
        return apk to sha
    }

    /** Pure, unit-tested: a published SHA-256 must exist and match the downloaded file. */
    fun shaMatches(expected: String, actual: String): Boolean =
        expected.length == 64 && expected.equals(actual, ignoreCase = true)

    /** A small text file over HTTPS (the .sha256 next to the APK). */
    fun fetchText(url: String): String? = try {
        val c = URL(url).openConnection() as HttpsURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        c.instanceFollowRedirects = true
        if (c.responseCode != 200) { c.errorStream?.close(); null }
        else c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
    } catch (e: Exception) { Log.w(TAG, "text fetch: $e"); null }

    private fun fetchLatest(): Release? = try {
        val c = URL(LATEST_URL).openConnection() as HttpsURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode != 200) { c.errorStream?.close(); null }
        else {
            val o = JSONObject(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
            val tag = o.optString("tag_name", "")
            if (tag.isEmpty() || o.optBoolean("prerelease", false) || o.optBoolean("draft", false)) null
            else {
                val (apk, sha) = assetUrls(o)
                Release(tag, tag.removePrefix("v"), o.optString("html_url", "https://github.com/popeye-mk/Noxa/releases/latest"), apk, sha)
            }
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
            // v1.14 one-tap update: when the release carries its APK and its .sha256, the tap downloads,
            // checks and opens Android's installer (UpdateActivity); otherwise the download page as before.
            val oneTap = r.apkUrl.isNotEmpty() && r.shaUrl.isNotEmpty()
            val target = if (oneTap) Intent(ctx, UpdateActivity::class.java)
                .putExtra(UpdateActivity.EXTRA_URL, r.apkUrl).putExtra(UpdateActivity.EXTRA_SHA_URL, r.shaUrl)
                .putExtra(UpdateActivity.EXTRA_VERSION, r.version)
            else Intent(Intent.ACTION_VIEW, Uri.parse(r.url))
            val open = PendingIntent.getActivity(ctx, NOTIF_ID, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            mgr.notify(NOTIF_ID, Notification.Builder(ctx, CHANNEL_ID)
                .setContentTitle("Noxa ${r.version} is available")
                .setContentText(if (oneTap) "Tap to download and install." else "Tap to open the download page.")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        } catch (e: Exception) { Log.w(TAG, "update notify: $e") }
    }
}
