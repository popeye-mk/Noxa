package com.guardian.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.Toast

/**
 * v1.14 safe search — OFF by default (More settings → Safe search).
 *
 * Google, YouTube, Bing, DuckDuckGo and Yandex each publish a special address
 * that answers only in safe / restricted mode (forcesafesearch.google.com,
 * restrict.youtube.com, strict.bing.com, safe.duckduckgo.com,
 * familysearch.yandex.ru). Family DNS services point the search sites at those
 * addresses; Noxa does the same on the device: a lookup of www.google.be is
 * answered with a CNAME to forcesafesearch.google.com plus that host's real
 * addresses, so the browser lands on the filtered search. Nothing is blocked,
 * nothing is sent anywhere new. Changing it asks the parent PIN when the lock
 * is on.
 *
 * Honest limits: it works for lookups that pass through Noxa (an app with its
 * own encrypted DNS slips past, as with all DNS-level filtering), and a search
 * site not on this list isn't covered.
 */
object SafeSearch {
    const val PREFS = "guardian_safesearch"
    private const val KEY_ON = "on"
    const val LABEL = "Safe search (set by a parent)"

    @Volatile var isOn = false
        private set

    private val YOUTUBE = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtubei.googleapis.com",
        "youtube.googleapis.com", "www.youtube-nocookie.com")
    private val BING = setOf("bing.com", "www.bing.com")
    private val DUCK = setOf("duckduckgo.com", "www.duckduckgo.com", "start.duckduckgo.com")
    private val YANDEX = setOf("yandex.ru", "www.yandex.ru", "yandex.com", "www.yandex.com")

    fun load(ctx: Context) { isOn = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false) }

    fun set(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        isOn = on
    }

    /** The safe-search host to answer with, while the feature is on; null = resolve normally. */
    fun target(host: String): String? = if (isOn) targetFor(host) else null

    /** Pure, unit-tested: the safe-search host for a search site, else null. */
    fun targetFor(host: String): String? {
        val h = host.trim().lowercase().removeSuffix(".")
        val labels = h.split('.')
        val google = (labels.size in 3..4 && labels[0] == "www" && labels[1] == "google") ||
            (labels.size in 2..3 && labels[0] == "google") ||
            (labels.size in 3..4 && labels[0] in setOf("encrypted", "ipv6", "ipv4") && labels[1] == "google")
        return when {
            google && !h.endsWith("googleapis.com") -> "forcesafesearch.google.com"
            h in YOUTUBE -> "restrict.youtube.com"
            h in BING -> "strict.bing.com"
            h in DUCK -> "safe.duckduckgo.com"
            h in YANDEX -> "familysearch.yandex.ru"
            else -> null
        }
    }

    /** More settings → Safe search (behind the parent lock). */
    fun manage(activity: Activity, done: () -> Unit = {}) {
        load(activity)
        ParentLock.gate(activity, "change safe search") {
            AlertDialog.Builder(activity)
                .setTitle("🔎 Safe search: " + (if (isOn) "ON" else "off"))
                .setMessage("Google, YouTube, Bing, DuckDuckGo and Yandex are forced into their safe / restricted " +
                    "mode on this device: explicit results are filtered, YouTube shows its restricted mode. " +
                    "Works in every browser and app that uses the device's normal DNS; nothing is blocked outright.")
                .setPositiveButton(if (isOn) "Turn off" else "Turn on") { _, _ ->
                    set(activity, !isOn)
                    Toast.makeText(activity, if (isOn) "Safe search is on." else "Safe search is off.", Toast.LENGTH_SHORT).show()
                    done()
                }
                .setNegativeButton("Back") { _, _ -> done() }
                .show()
        }
    }
}
