package com.guardian.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Step 2 — keep the blocklist fresh without reinstalling the app.
 *
 * The app downloads a newer prebuilt filter (`guardian-default.gbf`) from a
 * PUBLIC url (host it on GitHub once the project is public — GitHub serves it,
 * we run no server of our own). It's a one-way download: nothing about the user
 * is uploaded, so the zero-telemetry story holds. Downloaded filters land in
 * filesDir and are preferred over the bundled asset (see BloomFilter.loadCurrent).
 */
object FilterUpdater {

    private const val TAG = "Guardian"

    // Public raw path of the Noxa repo — GitHub hosts the filter, we run no server.
    private const val BASE = "https://raw.githubusercontent.com/popeye-mk/Noxa/main/app/src/main/assets"
    private const val MANIFEST_URL = "$BASE/blocklist-manifest.json"
    private const val FILTER_URL = "$BASE/guardian-default.gbf"

    private const val PREFS = "guardian_filter"
    private const val KEY_BUILT_AT = "filter_built_at"
    private const val KEY_LAST_CHECK = "filter_last_check"
    const val FILTER_FILE = "guardian-default.gbf"

    /** The built date of the filter currently in use (downloaded, or bundled). */
    fun currentBuiltAt(ctx: Context): String {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BUILT_AT, null)?.let { return it }
        return bundledBuiltAt(ctx)
    }

    private fun bundledBuiltAt(ctx: Context): String = try {
        val txt = ctx.assets.open("blocklist-manifest.json").bufferedReader().use { it.readText() }
        JSONObject(txt).optString("built_at", "")
    } catch (e: Exception) { "" }

    /** True only if a *downloaded* filter is genuinely newer than the one bundled
     *  in this APK. Prevents a stale download from overriding a fresh install. */
    fun downloadedIsNewer(ctx: Context): Boolean {
        val stored = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BUILT_AT, null) ?: return false
        return stored > bundledBuiltAt(ctx)   // ISO-ish timestamps compare as text
    }

    /** Check the remote manifest; if newer, download + verify + swap the filter.
     *  Returns a human-readable result. MUST be called off the main thread. */
    fun checkAndUpdate(ctx: Context): String {
        return try {
            val manifest = httpGet(MANIFEST_URL) ?: return "Couldn't reach the update source."
            val remote = JSONObject(String(manifest, Charsets.UTF_8)).optString("built_at", "")
            if (remote.isEmpty()) return "No update info available."
            val current = currentBuiltAt(ctx)
            // ISO-ish "YYYY-MM-DD HH:MM:SS" sorts correctly as text.
            if (remote <= current) return "Already up to date ($current)."

            val gbf = httpGet(FILTER_URL) ?: return "Couldn't download the new list."
            if (gbf.size < 24 || String(gbf, 0, 4, Charsets.US_ASCII) != "GBF1")
                return "Downloaded file was invalid — kept the current list."

            val tmp = File(ctx.filesDir, "$FILTER_FILE.tmp")
            tmp.writeBytes(gbf)
            val dst = File(ctx.filesDir, FILTER_FILE)
            if (!tmp.renameTo(dst)) { tmp.copyTo(dst, overwrite = true); tmp.delete() }

            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_BUILT_AT, remote).apply()
            "Updated to $remote — turn protection off and on to apply."
        } catch (e: Exception) {
            Log.w(TAG, "update failed: $e")
            "Update check failed: ${e.message}"
        }
    }

    /** Quiet once-a-day check, fired on service start. Applies on next restart. */
    fun autoCheck(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = p.getLong(KEY_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last < 24L * 60 * 60 * 1000) return
        p.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        Thread { try { checkAndUpdate(ctx) } catch (_: Exception) {} }.start()
    }

    private fun httpGet(url: String): ByteArray? = try {
        val c = URL(url).openConnection() as HttpsURLConnection
        c.connectTimeout = 8000; c.readTimeout = 20000
        if (c.responseCode != 200) { c.errorStream?.close(); null }
        else c.inputStream.use { it.readBytes() }
    } catch (e: Exception) { null }
}
