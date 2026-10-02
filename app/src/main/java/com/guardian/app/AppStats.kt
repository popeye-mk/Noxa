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
    private const val KEY_NO_FILTER = "no_filter_apps"
    private const val KEY_USER_BLOCK = "user_block"

    /** Used when we can't attribute a lookup to a specific app (e.g. system). */
    const val UNKNOWN = "(system / unknown)"

    val blocked = ConcurrentHashMap<String, Long>()
    val allowed = ConcurrentHashMap<String, Long>()
    /** app -> ("Company · Category" -> count) — the Phase 3 who/why breakdown. */
    val companiesByApp = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val firewall = ConcurrentHashMap<String, Boolean>()
    /** Apps EXCLUDED from Noxa entirely (VpnService addDisallowedApplication).
     *  For apps that refuse to run when they detect a VPN (Disney+ shows "no
     *  internet", some banking apps too). Their traffic bypasses Noxa: no
     *  blocking, no stats — but the app works. Applied on next VPN start. */
    private val noFilter = ConcurrentHashMap<String, Boolean>()
    /** User's personal "never block this" list — overrides the tracker filter. */
    private val userAllow = ConcurrentHashMap<String, Boolean>()
    /** v1.5: user's personal "always block this" list (e.g. from the Live feed). */
    private val userBlock = ConcurrentHashMap<String, Boolean>()

    /** True if [host] (or a parent domain) is on the user's allowlist. */
    fun isUserAllowed(host: String): Boolean = matchesSet(userAllow, host)

    /** True if [host] (or a parent domain) is on the user's own block list. */
    fun isUserBlocked(host: String): Boolean = matchesSet(userBlock, host)

    private fun matchesSet(set: ConcurrentHashMap<String, Boolean>, host: String): Boolean {
        if (set.isEmpty()) return false
        var h = host.trim().lowercase().removeSuffix(".")
        while (h.contains('.')) {
            if (set.containsKey(h)) return true
            h = h.substring(h.indexOf('.') + 1)
        }
        return false
    }

    /** "https://Ads.Example.com/x" -> "ads.example.com"; null if not a domain. */
    fun cleanDomain(domain: String): String? {
        val d = domain.trim().lowercase()
            .removePrefix("http://").removePrefix("https://")
            .substringBefore('/').substringBefore(':').removeSuffix(".")
        return if (d.contains('.')) d else null
    }

    fun userAllowList(): List<String> = userAllow.keys.sorted()

    fun addUserAllow(ctx: Context, domain: String) {
        val d = cleanDomain(domain) ?: return
        userAllow[d] = true; userBlock.remove(d); save(ctx)
    }

    fun removeUserAllow(ctx: Context, domain: String) {
        userAllow.remove(domain); save(ctx)
    }

    fun userBlockList(): List<String> = userBlock.keys.sorted()

    fun addUserBlock(ctx: Context, domain: String) {
        val d = cleanDomain(domain) ?: return
        userBlock[d] = true; userAllow.remove(d); save(ctx)
    }

    fun removeUserBlock(ctx: Context, domain: String) {
        userBlock.remove(domain); save(ctx)
    }

    // --- "Don't filter this app" (VPN exclusion) -----------------------------
    fun isNoFilter(pkg: String): Boolean = noFilter.containsKey(pkg)
    fun noFilterList(): List<String> = noFilter.keys.toList()
    fun setNoFilter(ctx: Context, pkg: String, on: Boolean) {
        if (on) noFilter[pkg] = true else noFilter.remove(pkg)
        save(ctx)
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

    /** v1.9.2: which apps hit labels starting with [prefix] (e.g. "Stalkerware"),
     *  biggest first — so the check-up can NAME the app instead of just a count. */
    fun appsHitting(prefix: String): List<Pair<String, Long>> =
        companiesByApp.mapNotNull { (pkg, m) ->
            val n = m.filterKeys { it.startsWith(prefix) }.values.sum()
            if (n > 0) pkg to n else null
        }.sortedByDescending { it.second }

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

    @Volatile private var loaded = false

    /** Read from disk ONCE per process. The service and every screen share this
     *  object in-process, so after the first load memory is the live truth —
     *  re-reading (each screen used to) silently dropped counts the service
     *  hadn't saved yet. */
    fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loadFromDisk(ctx)
            loaded = true
        }
    }

    private fun loadFromDisk(ctx: Context) {
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
        noFilter.clear()
        try {
            val arr = JSONArray(p.getString(KEY_NO_FILTER, "[]"))
            for (i in 0 until arr.length()) noFilter[arr.getString(i)] = true
        } catch (_: Exception) {}
        userBlock.clear()
        try {
            val arr = JSONArray(p.getString(KEY_USER_BLOCK, "[]"))
            for (i in 0 until arr.length()) userBlock[arr.getString(i)] = true
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
            .putString(KEY_NO_FILTER, JSONArray(noFilter.keys.toList()).toString())
            .putString(KEY_USER_BLOCK, JSONArray(userBlock.keys.toList()).toString())
            .putString(KEY_COMPANIES, companies.toString())
            .apply()
    }

    // --- v1.6 backup / restore ----------------------------------------------
    // The user's CHOICES only (lists + switches) — no stats, no history.

    private const val BACKUP_FORMAT = "noxa-settings"
    private const val MAX_ENTRIES = 10_000
    private val PKG_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    fun exportSettings(): String = JSONObject().apply {
        put("format", BACKUP_FORMAT)
        put("version", 1)
        put("allowed_sites", JSONArray(userAllowList()))
        put("blocked_sites", JSONArray(userBlockList()))
        put("firewalled_apps", JSONArray(firewall.keys.sorted()))
        put("excluded_apps", JSONArray(noFilter.keys.sorted()))
        put("encrypted_dns", GuardianVpnService.encryptedDns.get())
        put("dns_provider", DnsProviders.exportValue())
    }.toString(2)

    /** Merge a backup into the current settings (never deletes anything).
     *  Every entry is validated. Returns a plain-language summary; throws
     *  IllegalArgumentException if the file isn't a Noxa backup. */
    fun importSettings(ctx: Context, text: String): String {
        val o = try { JSONObject(text) } catch (e: Exception) {
            throw IllegalArgumentException("That file isn't a Noxa backup.")
        }
        require(o.optString("format") == BACKUP_FORMAT) { "That file isn't a Noxa backup." }
        fun strings(key: String): List<String> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until minOf(a.length(), MAX_ENTRIES)).mapNotNull { a.optString(it, null) }
        }
        var sites = 0; var apps = 0
        for (d in strings("allowed_sites")) cleanDomain(d)?.let { if (userAllow.put(it, true) == null) sites++ }
        for (d in strings("blocked_sites")) cleanDomain(d)?.let {
            if (!userAllow.containsKey(it) && userBlock.put(it, true) == null) sites++
        }
        for (p in strings("firewalled_apps")) if (PKG_RE.matches(p) && firewall.put(p, true) == null) apps++
        for (p in strings("excluded_apps")) if (PKG_RE.matches(p) && noFilter.put(p, true) == null) apps++
        if (o.has("encrypted_dns")) GuardianVpnService.setEncryptedDns(ctx, o.optBoolean("encrypted_dns", true))
        o.optString("dns_provider", "").takeIf { it.isNotEmpty() }?.let { DnsProviders.importValue(ctx, it) }
        save(ctx)
        return "Restored: $sites site(s), $apps app setting(s). " +
            "Turn protection off and on to apply app exclusions."
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
