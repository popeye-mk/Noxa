package com.guardian.app

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.10 "Strict scam protection" — an optional switch, OFF by default.
 *
 * Most scam, phishing and malware sites use a small set of cheap web endings
 * (.cam, .loan, .bond, .cfd, ...). When the user switches this on, Noxa blocks
 * every site under those endings. Strong, but strict: a real shop on one of
 * them is blocked too — the user can allow it under "Allowed sites", and the
 * alert says so.
 *
 * (Blocking "brand-new websites" was considered instead: the free lists of
 * new domains are 2–3 million names per week — far too big for a phone — and
 * the main ones stopped updating. Web endings catch much of the same scams.)
 *
 * The list is HaGeZi's "Most Abused TLDs", bundled as risky-tlds.txt and
 * refreshed weekly with the blocklist. Everyday endings (.com, .nl, .mk...)
 * can never be in it — the builder and verify_build.py both refuse them.
 */
object ScamShield {
    const val FILE = "risky-tlds.txt"
    const val LABEL = "Dangerous site · Risky web ending"
    private const val TAG = "Guardian"
    private const val KEY_ON = "strict_scam_protection"

    private val on = AtomicBoolean(false)
    @Volatile private var endings: Set<String> = emptySet()

    val isOn: Boolean get() = on.get()
    val size: Int get() = endings.size

    fun load(ctx: Context) {
        on.set(ctx.getSharedPreferences(GuardianVpnService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ON, false))
        val f = File(ctx.filesDir, FILE)
        val text = try {
            if (f.exists() && f.length() > 0 && FilterUpdater.downloadedIsNewer(ctx)) f.readText()
            else ctx.assets.open(FILE).bufferedReader().use { it.readText() }
        } catch (e: Exception) { Log.w(TAG, "risky endings unavailable: $e"); "" }
        endings = parse(text)
        Log.i(TAG, "strict scam protection: ${if (isOn) "on" else "off"}, ${endings.size} endings")
    }

    fun setOn(ctx: Context, value: Boolean) {
        on.set(value)
        ctx.getSharedPreferences(GuardianVpnService.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ON, value).apply()
        if (endings.isEmpty()) load(ctx)
    }

    /** One ending per line; comments and anything odd are skipped. */
    fun parse(text: String): Set<String> =
        text.lineSequence().map { it.trim().lowercase().removePrefix(".") }
            .filter { it.isNotEmpty() && !it.startsWith("#") &&
                it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' } }
            .toHashSet()

    /** The risky ending [host] uses, or null (also null while switched off). */
    fun riskyEnding(host: String): String? = if (!on.get()) null else endingIn(endings, host)

    /** v1.13 link check: the risky ending even while strict mode is off. */
    fun riskyEndingAlways(host: String): String? = endingIn(endings, host)

    /** Pure, unit-tested: the ending in [set] that [host] ends with, if any.
     *  "shop.example.cam" -> "cam"; "evil.us.com" -> "us.com"; "cam" alone -> null. */
    fun endingIn(set: Set<String>, host: String): String? {
        if (set.isEmpty()) return null
        val h = host.trim().lowercase().removeSuffix(".")
        var dot = h.indexOf('.')
        while (dot >= 0) {
            val suffix = h.substring(dot + 1)
            if (suffix in set) return suffix
            dot = h.indexOf('.', dot + 1)
        }
        return null
    }
}
