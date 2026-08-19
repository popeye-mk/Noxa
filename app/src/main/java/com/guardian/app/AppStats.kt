package com.guardian.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 2 — per-app data, shared between GuardianVpnService (the writer) and the
 * UI (the reader). Two things live here, both riding the SAME Phase-1 pipeline:
 *
 *   - stats:    how many DNS lookups Guardian blocked / allowed, per app
 *   - firewall: the set of apps the user has chosen to block entirely
 *
 * Persisted to SharedPreferences as JSON so it survives the app being killed.
 * Apps are keyed by package name; the UI resolves the human label from that.
 */
object AppStats {
    private const val PREFS = "guardian_app_stats"
    private const val KEY_BLOCKED = "blocked"
    private const val KEY_ALLOWED = "allowed"
    private const val KEY_FIREWALL = "firewall"
    private const val KEY_COMPANIES = "companies"
    private const val KEY_USER_ALLOW = "user_allow"
    private const val KEY_COMPAT_SEEDED = "compat_seeded_v1"

    /** Used when we can't attribute a lookup to a specific app (e.g. system). */
    const val UNKNOWN = "(system / unknown)"

    val blocked = ConcurrentHashMap<String, Long>()
    val allowed = ConcurrentHashMap<String, Long>()
    /** app -> ("Company · Category" -> count) — the Phase 3 who/why breakdown. */
    val companiesByApp = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val firewall = ConcurrentHashMap<String, Boolean>()
    /** User's personal "never block this" list — overrides the tracker filter. */
    private val userAllow = ConcurrentHashMap<String, Boolean>()

    /** True if [host] (or a parent domain) is on the user's allowlist. */
    fun isUserAllowed(host: String): Boolean {
        if (userAllow.isEmpty()) return false
        var h = host.trim().lowercase().removeSuffix(".")
        while (h.contains('.')) {
            if (userAllow.containsKey(h)) return true
            h = h.substring(h.indexOf('.') + 1)
        }
        return false
    }

    fun userAllowList(): List<String> = userAllow.keys.sorted()

    fun addUserAllow(ctx: Context, domain: String) {
        val d = domain.trim().lowercase()
            .removePrefix("http://").removePrefix("https://")
            .substringBefore('/').substringBefore(':').removeSuffix(".")
        if (d.contains('.')) { userAllow[d] = true; save(ctx) }
    }

    fun removeUserAllow(ctx: Context, domain: String) {
        userAllow.remove(domain); save(ctx)
    }

    fun recordBlocked(pkg: String, label: String) {
        blocked.compute(pkg) { _, v -> (v ?: 0L) + 1L }
        companiesByApp.computeIfAbsent(pkg) { ConcurrentHashMap() }
            .compute(label) { _, v -> (v ?: 0L) + 1L }
    }
    fun recordAllowed(pkg: String) { allowed.compute(pkg) { _, v -> (v ?: 0L) + 1L } }

    /** The "Company · Category" -> count breakdown for one app. */
    fun companyCounts(pkg: String): Map<String, Long> = companiesByApp[pkg] ?: emptyMap()

    /** Company breakdown summed across every app. */
    fun globalCompanyCounts(): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (m in companiesByApp.values) for ((k, v) in m) out[k] = (out[k] ?: 0L) + v
        return out
    }

    /** Wipe the counters for a fresh stats period. Keeps firewall choices. */
    fun clearAll() {
        blocked.clear(); allowed.clear(); companiesByApp.clear()
    }

    fun isFirewalled(pkg: String): Boolean = firewall.containsKey(pkg)

    fun setFirewalled(ctx: Context, pkg: String, on: Boolean) {
        if (on) firewall[pkg] = true else firewall.remove(pkg)
        save(ctx)
    }

    /** Every app we've seen (blocked or allowed), for the dashboard list. */
    fun seenApps(): Set<String> = (blocked.keys + allowed.keys).toSet()

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        readInto(p.getString(KEY_BLOCKED, "{}"), blocked)
        readInto(p.getString(KEY_ALLOWED, "{}"), allowed)
        firewall.clear()
        try {
            val arr = JSONArray(p.getString(KEY_FIREWALL, "[]"))
            for (i in 0 until arr.length()) firewall[arr.getString(i)] = true
        } catch (_: Exception) {}
        userAllow.clear()
        try {
            val arr = JSONArray(p.getString(KEY_USER_ALLOW, "[]"))
            for (i in 0 until arr.length()) userAllow[arr.getString(i)] = true
        } catch (_: Exception) {}
        // App-compatibility defaults — SEEDED ONCE into the user's allowlist.
        // Some apps hard-refuse to start when their startup beacon is blocked
        // (verified on real devices: Disney+ error 142 — its first-party-looking
        // subdomains CNAME to an analytics partner, our uncloaking catches it,
        // and the app won't run without it). Seeding (not hardcoding) keeps the
        // user in charge: entries are visible in "Allowed sites" and can be
        // deleted there — we never re-add them once seeded.
        if (!p.getBoolean(KEY_COMPAT_SEEDED, false)) {
            userAllow["disneystreaming.com"] = true        // Disney+ (error 142)
            userAllow["device-metrics-us.amazon.com"] = true   // Prime Video on TV
            userAllow["device-metrics-us-2.amazon.com"] = true
            userAllow["bam.nr-data.net"] = true            // Disney+ startup beacon
            p.edit().putBoolean(KEY_COMPAT_SEEDED, true).apply()
            save(ctx)
        }
        companiesByApp.clear()
        try {
            val o = JSONObject(p.getString(KEY_COMPANIES, "{}"))
            val apps = o.keys()
            while (apps.hasNext()) {
                val app = apps.next()
                val inner = o.getJSONObject(app)
                val m = ConcurrentHashMap<String, Long>()
                val labels = inner.keys()
                while (labels.hasNext()) { val l = labels.next(); m[l] = inner.getLong(l) }
                companiesByApp[app] = m
            }
        } catch (_: Exception) {}
    }

    fun save(ctx: Context) {
        val companies = JSONObject()
        for ((app, m) in companiesByApp) {
            val inner = JSONObject()
            for ((k, v) in m) inner.put(k, v)
            companies.put(app, inner)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BLOCKED, toJson(blocked))
            .putString(KEY_ALLOWED, toJson(allowed))
            .putString(KEY_FIREWALL, JSONArray(firewall.keys.toList()).toString())
            .putString(KEY_USER_ALLOW, JSONArray(userAllow.keys.toList()).toString())
            .putString(KEY_COMPANIES, companies.toString())
            .apply()
    }

    /** Full per-app table as CSV, most-blocked first (used by Export). */
    fun exportCsv(): String {
        val sb = StringBuilder("app_package,blocked,allowed,firewalled,top_company\n")
        for (pkg in seenApps().sortedByDescending { blocked[it] ?: 0L }) {
            val top = companyCounts(pkg).maxByOrNull { it.value }?.key ?: ""
            sb.append(pkg).append(',')
                .append(blocked[pkg] ?: 0L).append(',')
                .append(allowed[pkg] ?: 0L).append(',')
                .append(if (isFirewalled(pkg)) "yes" else "no").append(',')
                .append('"').append(top.replace('"', '\'')).append('"').append('\n')
        }
        return sb.toString()
    }

    private fun readInto(json: String?, map: ConcurrentHashMap<String, Long>) {
        map.clear()
        try {
            val o = JSONObject(json ?: "{}")
            val it = o.keys()
            while (it.hasNext()) { val k = it.next(); map[k] = o.getLong(k) }
        } catch (_: Exception) {}
    }

    private fun toJson(map: ConcurrentHashMap<String, Long>): String {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        return o.toString()
    }
}
