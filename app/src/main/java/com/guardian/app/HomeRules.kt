package com.guardian.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v1.14 rules from the home server — only when "Report to my home server" is on.
 *
 * Each report's answer may carry rules the home server (a parent) set for this
 * device: sites to block, the family filter, safe search and bedtime, plus the
 * version of the server's own "home list" of domains. Noxa applies them on the
 * device: blocked sites win even over the device's Allowed sites; a flag the
 * server sets is shown as "set by your home server" in its menu. The home list
 * is fetched only when its version changed. Nothing here talks to anything but
 * the home server the user chose (docs/HOME-SERVER.md).
 */
object HomeRules {
    const val PREFS = "guardian_home_rules"
    private const val KEY_RULES = "rules"
    private const val KEY_LIST_VERSION = "list_version"
    private const val LIST_FILE = "home-list.txt"
    const val LABEL = "Blocked by your home · Site"
    const val LIST_PATH = "/api/home/list"
    private const val MAX_LIST_REPLY = 1024 * 1024
    private const val TAG = "Guardian"

    class Rules(val blockedSites: List<String>, val familyFilter: Boolean?, val safeSearch: Boolean?, val bedtime: JSONObject?)

    @Volatile private var sites: Set<String> = emptySet()
    @Volatile private var list: Set<String> = emptySet()
    @Volatile private var managed: Set<String> = emptySet()

    /** Pure, unit-tested: the server's rules object → what to apply (null = no rule, keep the device's own). */
    fun parse(json: JSONObject?): Rules {
        if (json == null) return Rules(emptyList(), null, null, null)
        val arr = json.optJSONArray("blocked_sites") ?: JSONArray()
        val blocked = (0 until minOf(arr.length(), 500)).mapNotNull { AppStats.cleanDomain(arr.optString(it)) }.distinct()
        fun flag(k: String): Boolean? = if (json.has(k) && !json.isNull(k)) json.optBoolean(k) else null
        val bed = json.optJSONObject("bedtime")?.takeIf { it.has("start") && it.has("end") }
        return Rules(blocked, flag("family_filter"), flag("safe_search"), bed)
    }

    /** Pure, unit-tested: [host] or a parent domain in [set]? */
    fun matches(set: Set<String>, host: String): Boolean {
        if (set.isEmpty()) return false
        var h = host.trim().lowercase().removeSuffix(".")
        while (h.contains('.')) {
            if (set.contains(h)) return true
            h = h.substring(h.indexOf('.') + 1)
        }
        return false
    }

    fun isBlocked(host: String): Boolean = matches(sites, host) || matches(list, host)
    fun isManaged(feature: String): Boolean = feature in managed
    /** The features the home server sets on this device (for the report, 1.14.4). */
    fun managedFeatures(): List<String> = managed.sorted()
    fun blockedCount(): Int = sites.size + list.size
    fun summary(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains(KEY_RULES)) return ""
        val parts = ArrayList<String>()
        if (sites.isNotEmpty()) parts += "${sites.size} site(s) blocked"
        if (list.isNotEmpty()) parts += "home list ${list.size}"
        managed.forEach { parts += it.replace('_', ' ') }
        return "Rules from your home server: " + (if (parts.isEmpty()) "none" else parts.joinToString(", ")) + "."
    }

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val r = parse(try { JSONObject(p.getString(KEY_RULES, "") ?: "") } catch (_: Exception) { null })
        sites = r.blockedSites.toHashSet()
        managed = managedOf(r)
        list = try {
            val f = File(ctx.filesDir, LIST_FILE)
            if (f.exists()) f.readLines().map { it.trim().lowercase() }.filter { it.contains('.') }.toHashSet() else emptySet()
        } catch (_: Exception) { emptySet() }
    }

    private fun managedOf(r: Rules): Set<String> {
        val m = HashSet<String>()
        if (r.familyFilter != null) m += "family_filter"
        if (r.safeSearch != null) m += "safe_search"
        if (r.bedtime != null) m += "bedtime"
        return m
    }

    /** Apply what a report's answer carried. Flags are changed only when they differ; the list only when newer. */
    fun apply(ctx: Context, rulesJson: JSONObject?, listVersion: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (rulesJson == null) { p.edit().remove(KEY_RULES).apply(); load(ctx); return }
        p.edit().putString(KEY_RULES, rulesJson.toString()).apply()
        val r = parse(rulesJson)
        sites = r.blockedSites.toHashSet()
        managed = managedOf(r)
        try {
            r.safeSearch?.let { if (it != SafeSearch.isOn) SafeSearch.set(ctx, it) }
            r.familyFilter?.let { want ->
                val have = ctx.getSharedPreferences(FamilyFilter.PREFS, Context.MODE_PRIVATE).getBoolean("on", false)
                if (want != have) {
                    if (!want) FamilyFilter.setOn(ctx, false)
                    else Thread { try { FamilyFilter.download(ctx) } catch (_: Exception) {}; if (FamilyFilter.hasFile(ctx)) FamilyFilter.setOn(ctx, true) }.start()
                }
            }
            r.bedtime?.let { b ->
                val apps = b.optJSONArray("apps") ?: JSONArray()
                Bedtime.applyRule(ctx, b.optBoolean("on"), b.optString("start"), b.optString("end"), b.optBoolean("all"),
                    (0 until apps.length()).map { apps.optString(it) })
            }
        } catch (e: Exception) { Log.w(TAG, "home rules: $e") }
        if (listVersion.isNotEmpty() && listVersion != (p.getString(KEY_LIST_VERSION, "") ?: ""))
            Thread { try { fetchList(ctx, listVersion) } catch (e: Exception) { Log.w(TAG, "home list: $e") } }.start()
    }

    /** The server's own list of domains (its scam lists + the parent's list), written to a file, loaded live. */
    fun fetchList(ctx: Context, version: String) {
        val target = HomeReport.parseAddress(HomeReport.address(ctx)) ?: return
        val body = JSONObject().apply { put("device", JSONObject().put("id", HomeReport.installId(ctx))) }
            .toString().toByteArray(Charsets.UTF_8)
        val reply = HomeReport.exchangeWith(target, HomeReport.request(target.host, target.port, HomeReport.key(ctx), body, LIST_PATH), MAX_LIST_REPLY)
        if (HomeReport.statusOf(reply) != 200) return
        val json = JSONObject(HomeReport.bodyOf(reply))
        val arr = json.optJSONArray("domains") ?: return
        val domains = (0 until minOf(arr.length(), 50_000)).mapNotNull { AppStats.cleanDomain(arr.optString(it)) }
        val tmp = File(ctx.filesDir, "$LIST_FILE.tmp")
        tmp.writeText(domains.joinToString("\n"))
        val dst = File(ctx.filesDir, LIST_FILE)
        if (!tmp.renameTo(dst)) { tmp.copyTo(dst, overwrite = true); tmp.delete() }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LIST_VERSION, json.optString("version", version)).apply()
        list = domains.toHashSet()
        Log.i(TAG, "home list: ${domains.size} domains")
    }

    /** "Report to my home server" switched off: the rules go with it. */
    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        try { File(ctx.filesDir, LIST_FILE).delete() } catch (_: Exception) {}
        sites = emptySet(); list = emptySet(); managed = emptySet()
    }
}
