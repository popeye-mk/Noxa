package com.guardian.app

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.widget.Toast
import org.json.JSONArray
import java.util.Calendar

/**
 * v1.14 bedtime (quiet hours) — OFF by default (More settings → Bedtime).
 *
 * A parent picks a time window ("21:00 to 07:00") and either every app or a
 * few chosen ones (games, video, social). In that window those apps get no
 * internet: their lookups are sinkholed on the same pipeline as the per-app
 * firewall, labelled "Blocked by you · Bedtime" so the child sees why in the
 * live feed. Calls and texts don't use DNS, so they keep working. Changing
 * bedtime asks the parent PIN when the parent lock is on.
 *
 * The hot path costs one minute-of-day comparison, recomputed once a minute.
 */
object Bedtime {
    const val PREFS = "guardian_bedtime"
    private const val KEY_ON = "on"
    private const val KEY_START = "start"       // minutes of the day
    private const val KEY_END = "end"
    private const val KEY_ALL = "all_apps"
    private const val KEY_APPS = "apps"         // JSON array of package names
    const val LABEL = "Blocked by you · Bedtime"

    @Volatile private var on = false
    @Volatile private var start = 21 * 60
    @Volatile private var end = 7 * 60
    @Volatile private var all = false
    @Volatile private var apps: Set<String> = emptySet()
    @Volatile private var cachedMinute = -1L
    @Volatile private var cachedActive = false

    /** Pure: is [nowMinutes] (0..1439) inside the window? A window may cross midnight. */
    fun active(nowMinutes: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> nowMinutes in start until end
        else -> nowMinutes >= start || nowMinutes < end
    }

    /** Pure: is this app offline now? System/unknown lookups are never touched. */
    fun blocks(pkg: String, active: Boolean, all: Boolean, chosen: Set<String>): Boolean =
        active && pkg != AppStats.UNKNOWN && (all || pkg in chosen)

    /** Pure: "21:00 to 07:00, all apps" for the menu. */
    fun describe(on: Boolean, start: Int, end: Int, all: Boolean, chosen: Int): String =
        if (!on) "off" else "${hhmm(start)}–${hhmm(end)}, " + (if (all) "all apps" else "$chosen app(s)")

    fun hhmm(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        on = p.getBoolean(KEY_ON, false)
        start = p.getInt(KEY_START, 21 * 60); end = p.getInt(KEY_END, 7 * 60)
        all = p.getBoolean(KEY_ALL, false)
        apps = try {
            val a = JSONArray(p.getString(KEY_APPS, "[]") ?: "[]")
            (0 until a.length()).map { a.getString(it) }.toSet()
        } catch (_: Exception) { emptySet() }
        cachedMinute = -1
    }

    fun isOn(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun summary(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val n = try { JSONArray(p.getString(KEY_APPS, "[]") ?: "[]").length() } catch (_: Exception) { 0 }
        return describe(p.getBoolean(KEY_ON, false), p.getInt(KEY_START, 21 * 60), p.getInt(KEY_END, 7 * 60),
            p.getBoolean(KEY_ALL, false), n)
    }

    /** The hot-path check: once a minute the window is recomputed, then it's a set lookup. */
    fun blocksNow(pkg: String, now: Long = System.currentTimeMillis()): Boolean {
        if (!on) return false
        val minute = now / 60_000L
        if (minute != cachedMinute) {
            val c = Calendar.getInstance().apply { timeInMillis = now }
            cachedActive = active(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), start, end)
            cachedMinute = minute
        }
        return blocks(pkg, cachedActive, all, apps)
    }

    /** v1.14: set by the home server (HomeRules). "21:00" / "07:00"; a bad time is ignored. */
    fun applyRule(ctx: Context, on: Boolean, startHhmm: String, endHhmm: String, allApps: Boolean, chosen: List<String>) {
        fun minutes(t: String): Int? {
            val m = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").find(t.trim()) ?: return null
            return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        }
        val s = minutes(startHhmm) ?: return; val e = minutes(endHhmm) ?: return
        save(ctx) {
            putBoolean(KEY_ON, on); putInt(KEY_START, s); putInt(KEY_END, e); putBoolean(KEY_ALL, allApps)
            putString(KEY_APPS, JSONArray(chosen.filter { it.matches(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")) }).toString())
        }
    }

    private fun save(ctx: Context, edit: android.content.SharedPreferences.Editor.() -> Unit) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply(edit).apply()
        load(ctx)
    }

    // --- the settings dialog (More settings → Bedtime) -------------------------
    fun manage(activity: Activity, done: () -> Unit = {}) {
        ParentLock.gate(activity, "change bedtime") { menu(activity, done) }
    }

    private fun menu(activity: Activity, done: () -> Unit) {
        load(activity)
        val items = arrayOf(
            "Bedtime: " + (if (on) "ON" else "off"),
            "From ${hhmm(start)}",
            "Until ${hhmm(end)}",
            "Which apps: " + (if (all) "all apps" else "${apps.size} chosen…"),
        )
        AlertDialog.Builder(activity)
            .setTitle("🌙 Bedtime")
            .setMessage("In the quiet hours the chosen apps get no internet on this device. Calls and texts still work." +
                (if (HomeRules.isManaged("bedtime")) "\n\nSet by your home server — a change here comes back at the next report." else ""))
            .setItems(items) { _, i ->
                when (i) {
                    0 -> { save(activity) { putBoolean(KEY_ON, !on) }
                           Toast.makeText(activity, if (on) "Bedtime is on: ${summary(activity)}." else "Bedtime is off.", Toast.LENGTH_LONG).show()
                           menu(activity, done) }
                    1 -> pick(activity, start) { m -> save(activity) { putInt(KEY_START, m) }; menu(activity, done) }
                    2 -> pick(activity, end) { m -> save(activity) { putInt(KEY_END, m) }; menu(activity, done) }
                    3 -> whichApps(activity) { menu(activity, done) }
                }
            }
            .setNegativeButton("Back") { _, _ -> done() }
            .show()
    }

    private fun pick(activity: Activity, minutes: Int, chosen: (Int) -> Unit) {
        TimePickerDialog(activity, { _, h, m -> chosen(h * 60 + m) }, minutes / 60, minutes % 60, true).show()
    }

    private fun whichApps(activity: Activity, back: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("Which apps go offline at bedtime?")
            .setItems(arrayOf("All apps", "Only the apps I choose…")) { _, i ->
                if (i == 0) { save(activity) { putBoolean(KEY_ALL, true) }; back() }
                else chooseApps(activity, back)
            }
            .setNegativeButton("Back") { _, _ -> back() }
            .show()
    }

    private fun chooseApps(activity: Activity, back: () -> Unit) {
        val pm = activity.packageManager
        val pkgs = (pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0) +
                    pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0))
            .map { it.activityInfo.packageName }.distinct().filter { it != activity.packageName }
        val named = pkgs.map { pkg ->
            (try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }) to pkg
        }.sortedBy { it.first.lowercase() }
        val checked = BooleanArray(named.size) { named[it].second in apps }
        AlertDialog.Builder(activity)
            .setTitle("Offline at bedtime")
            .setMultiChoiceItems(named.map { it.first }.toTypedArray(), checked) { _, i, isChecked -> checked[i] = isChecked }
            .setPositiveButton("Save") { _, _ ->
                val chosen = named.indices.filter { checked[it] }.map { named[it].second }
                save(activity) { putBoolean(KEY_ALL, false); putString(KEY_APPS, JSONArray(chosen).toString()) }
                Toast.makeText(activity, "${chosen.size} app(s) go offline at bedtime.", Toast.LENGTH_SHORT).show()
                back()
            }
            .setNegativeButton("Cancel") { _, _ -> back() }
            .show()
    }
}
