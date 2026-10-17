package com.guardian.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * v1.14 family filter — OFF by default (More settings → Family filter).
 *
 * A second, optional Bloom filter with the adult, gambling and dating
 * categories (UT1's adult, porn, mixed_adult, lingerie, gambling, arjel and
 * dating lists, minus Noxa's never-block allowlist). It is NOT in the APK: it
 * is big (several million domains), so the weekly rebuild publishes it as a
 * rolling GitHub release asset, and Noxa downloads it only when a parent
 * switches the filter on — on Wi-Fi, verified against the SHA-256 in the
 * blocklist manifest, swapped in live. A blocked lookup shows as
 * "Family filter · Adult" in the live feed. The user's own Allowed sites win
 * (changing those needs the parent PIN when the lock is on).
 *
 * Honest limits: the list is a list — it misses sites and it may be wrong
 * about some; education sites (UT1's sexual_education) are left alone on
 * purpose. Like all DNS filtering it covers lookups that pass through Noxa.
 */
object FamilyFilter {
    const val FILE = "family.gbf"
    const val LABEL = "Family filter · Adult"
    const val URL_ = "https://github.com/popeye-mk/Noxa/releases/download/family-filter/family.gbf"
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/popeye-mk/Noxa/main/app/src/main/assets/blocklist-manifest.json"
    const val PREFS = "guardian_family"
    private const val KEY_ON = "on"
    private const val KEY_SHA = "sha256"
    private const val KEY_LAST_CHECK = "last_check"
    private const val TAG = "Guardian"
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

    @Volatile var isOn = false
        private set
    @Volatile private var filter: BloomFilter? = null

    val items: Long get() = filter?.items ?: 0L

    /** Pure: the hash the manifest publishes for the family filter ("" = none published yet). */
    fun manifestSha(manifestJson: String): String =
        try { JSONObject(manifestJson).optString("family_sha256", "") } catch (_: Exception) { "" }

    /** Pure: download again when the published filter differs from the one on the device. */
    fun needsDownload(storedSha: String, publishedSha: String, fileExists: Boolean): Boolean =
        publishedSha.isNotEmpty() && (!fileExists || !storedSha.equals(publishedSha, ignoreCase = true))

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        isOn = p.getBoolean(KEY_ON, false)
        if (!isOn) { filter = null; return }
        filter = try {
            val f = File(ctx.filesDir, FILE)
            if (f.exists() && f.length() > 24) BloomFilter.load(f.inputStream()) else null
        } catch (e: Exception) { Log.w(TAG, "family filter unavailable: $e"); null }
        Log.i(TAG, "family filter: $items domains")
    }

    /** Only while on and loaded; a parent domain match counts, like the main filter. */
    fun matches(host: String): Boolean = isOn && (filter?.matchesHostOrParent(host) ?: false)

    fun hasFile(ctx: Context): Boolean = File(ctx.filesDir, FILE).let { it.exists() && it.length() > 24 }

    /** Fetch (or refresh) the filter. Returns plain words. MUST be called off the main thread. */
    fun download(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val manifest = httpGet(MANIFEST_URL)?.let { String(it, Charsets.UTF_8) } ?: return "Couldn't reach the update source."
        val sha = manifestSha(manifest)
        if (sha.isEmpty()) return "The family filter isn't published yet — try again after the next weekly rebuild."
        if (!needsDownload(p.getString(KEY_SHA, "") ?: "", sha, hasFile(ctx))) return "Already up to date."
        val bytes = httpGet(URL_) ?: return "Couldn't download the family filter."
        if (!BloomFilter.isValidFile(bytes)) return "The downloaded file was invalid — nothing changed."
        if (!sha256Hex(bytes).equals(sha, ignoreCase = true)) return "The download failed its integrity check — nothing changed."
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeBytes(bytes)
        val dst = File(ctx.filesDir, FILE)
        if (!tmp.renameTo(dst)) { tmp.copyTo(dst, overwrite = true); tmp.delete() }
        p.edit().putString(KEY_SHA, sha).putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        if (isOn) load(ctx)
        return "Family filter ready: %,d domains.".format(BloomFilter.load(dst.inputStream()).items)
    }

    /** Once a week while on: pick up the rebuilt list. Quiet. */
    fun autoCheck(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_ON, false)) return
        if (System.currentTimeMillis() - p.getLong(KEY_LAST_CHECK, 0L) < WEEK_MS && hasFile(ctx)) return
        p.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        Thread { try { download(ctx) } catch (_: Exception) {} }.start()
    }

    fun setOn(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        load(ctx)
    }

    /** More settings → Family filter (behind the parent lock). Turning on downloads the list first. */
    fun manage(activity: Activity, done: () -> Unit = {}) {
        ParentLock.gate(activity, "change the family filter") {
            val on = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)
            AlertDialog.Builder(activity)
                .setTitle("👨‍👩‍👧 Family filter: " + (if (on) "ON" else "off"))
                .setMessage("Blocks adult, gambling and dating sites on this device (a weekly-rebuilt list of several " +
                    "million domains, downloaded on Wi-Fi when you switch it on — about 10 MB). Your own Allowed sites " +
                    "still win. A list can miss sites or be wrong about one; education sites are left alone.")
                .setPositiveButton(if (on) "Turn off" else "Turn on") { _, _ ->
                    if (on) {
                        setOn(activity, false)
                        Toast.makeText(activity, "Family filter is off.", Toast.LENGTH_SHORT).show()
                        done()
                    } else {
                        Toast.makeText(activity, "Downloading the family filter…", Toast.LENGTH_SHORT).show()
                        Thread {
                            val msg = try { download(activity) } catch (e: Exception) { "Couldn't download: ${e.message}" }
                            val ok = hasFile(activity)
                            if (ok) setOn(activity, true)
                            activity.runOnUiThread {
                                Toast.makeText(activity, if (ok) "Family filter is on. $msg" else msg, Toast.LENGTH_LONG).show()
                                done()
                            }
                        }.start()
                    }
                }
                .setNegativeButton("Back") { _, _ -> done() }
                .show()
        }
    }

    private fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun httpGet(url: String): ByteArray? = try {
        val c = URL(url).openConnection() as HttpsURLConnection
        c.connectTimeout = 8000; c.readTimeout = 60000
        c.instanceFollowRedirects = true
        if (c.responseCode != 200) { c.errorStream?.close(); null }
        else c.inputStream.use { it.readBytes() }
    } catch (e: Exception) { Log.w(TAG, "family filter download: $e"); null }
}
