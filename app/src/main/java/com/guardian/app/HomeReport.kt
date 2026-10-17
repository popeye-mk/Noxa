package com.guardian.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * v1.14 "Report to my home server" — OFF by default, one tap deeper (More settings).
 *
 * A family with several phones, tablets and a TV has no single place that shows
 * whether every device is still protected and whether any app on them reached a
 * scam or spyware server. When the user switches this on and types the address
 * of a server in THEIR OWN house, Noxa sends a short report once an hour, so
 * that one screen at home can show every device. The format is documented in
 * docs/HOME-SERVER.md with a tiny example receiver — anyone can run one.
 *
 * What a report holds: a random install id, the device's name, model, Android
 * and Noxa version; protection on/off, Always-on, blocklist date, unexpected
 * stops today; blocked today and the last 7 days; since the last report the
 * blocked counts per category, the top companies, and (last 30 days) the apps
 * that reached scam/malware or spyware servers. NEVER the sites visited or the
 * domains blocked — Noxa doesn't keep them — nor allowed lookups, the app list,
 * or anything about who uses the device.
 *
 * Only on Wi-Fi/Ethernet (never mobile data), never in tunnel mode, only to a
 * private (home) address typed by the user, never a name (nothing is looked
 * up), at most ~4 KB, with the key in a header. Plain HTTP on the home network:
 * the manifest keeps cleartext traffic off for the whole app, so this uses its
 * own small HTTP-over-socket sender instead of switching that policy on.
 */
object HomeReport {
    const val PREFS = "guardian_home"
    const val KEY_ON = "on"
    const val KEY_ADDRESS = "address"
    const val KEY_KEY = "key"
    const val KEY_NAME = "device_name"
    private const val KEY_ID = "install_id"
    private const val KEY_LAST_SENT = "last_sent"
    private const val KEY_LAST_TRY = "last_try"
    private const val KEY_LAST_RESULT = "last_result"
    private const val KEY_SNAPSHOT = "snapshot"

    const val DEFAULT_PORT = 8790
    const val PATH = "/api/home/report"
    const val JOIN_PATH = "/api/home/join"
    const val DISCOVERY_PORT = 8791
    const val HEADER = "X-Home-Key"
    const val INTERVAL_MS = 60L * 60 * 1000          // one report an hour
    const val MIN_TRY_GAP_MS = 10L * 60 * 1000       // after a failure: not again within 10 min
    const val MAX_BODY = 24576                         // a report (~2 KB), plus the settings backup when it changed
    const val MAX_BACKUP = 16384
    private const val KEY_BACKUP_SENT = "backup_sent"
    private const val MAX_REPLY = 16 * 1024
    private const val TIMEOUT_MS = 5000
    private const val TAG = "Guardian"

    private val SPECIAL = listOf("Dangerous site", "Stalkerware", "Blocked by you", "Router attack shield",
        "DNS-over-HTTPS", "Family filter")

    // --- settings --------------------------------------------------------------
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun address(ctx: Context): String = prefs(ctx).getString(KEY_ADDRESS, "") ?: ""
    fun key(ctx: Context): String = prefs(ctx).getString(KEY_KEY, "") ?: ""
    fun lastSent(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_SENT, 0L)
    fun lastResult(ctx: Context): String = prefs(ctx).getString(KEY_LAST_RESULT, "") ?: ""

    /** On only when the switch is on AND an address and key are saved. */
    fun isOn(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ON, false) && parseAddress(address(ctx)) != null && keyOk(key(ctx))

    fun setOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ON, on).apply()
        if (on) HomeReportReceiver.schedule(ctx)          // its own hourly alarm, also while protection is off
        else { HomeReportReceiver.cancel(ctx); HomeRules.clear(ctx) }   // no home server, no rules from it
    }

    fun save(ctx: Context, address: String, key: String, name: String) {
        prefs(ctx).edit().putString(KEY_ADDRESS, address.trim()).putString(KEY_KEY, key.trim())
            .putString(KEY_NAME, name.trim()).apply()
    }

    /** The name typed by the user, else the phone's own name, else make and model. */
    fun name(ctx: Context): String =
        (prefs(ctx).getString(KEY_NAME, "") ?: "").ifBlank { defaultName(ctx) }

    fun defaultName(ctx: Context): String = try {
        Settings.Global.getString(ctx.contentResolver, "device_name")
    } catch (_: Exception) { null }?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun installId(ctx: Context): String = id(ctx)

    private fun id(ctx: Context): String {
        val p = prefs(ctx)
        p.getString(KEY_ID, null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        p.edit().putString(KEY_ID, fresh).apply()
        return fresh
    }

    // --- pure rules (unit-tested) ------------------------------------------------
    /** Hourly, and never two tries within 10 minutes. */
    fun due(now: Long, lastSent: Long, lastTry: Long): Boolean =
        now - lastSent >= INTERVAL_MS && now - lastTry >= MIN_TRY_GAP_MS

    class Target(val host: String, val port: Int, val bytes: ByteArray)

    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?::(\\d{1,5}))?$")

    /** "192.168.1.20" or "192.168.1.20:8790" → a private IPv4 target; null for anything else.
     *  Names are refused on purpose: Noxa never looks one up for this, and a report may only
     *  go to the home network. Loopback and link-local aren't a home server either. */
    fun parseAddress(text: String): Target? {
        val m = IPV4.find(text.trim()) ?: return null
        val parts = (1..4).map { m.groupValues[it].toInt() }
        if (parts.any { it > 255 }) return null
        val port = m.groupValues[5].ifEmpty { null }?.toIntOrNull() ?: DEFAULT_PORT
        if (port !in 1..65535) return null
        val bytes = ByteArray(4) { parts[it].toByte() }
        if (!RebindShield.isPrivate(bytes) || parts[0] == 127 || parts[0] == 169) return null
        return Target(parts.joinToString("."), port, bytes)
    }

    /** A key is one line of plain printable characters (it travels in an HTTP header). */
    fun keyOk(key: String): Boolean =
        key.length in 8..200 && key.all { it in '!'..'~' }

    /** "Company · Category" labels → the report's categories. */
    fun category(label: String): String {
        SPECIAL.firstOrNull { label.startsWith(it) }?.let { return it }
        val c = label.substringAfter(" · ", "").lowercase()
        return when {
            c.isEmpty() -> "Other"
            c.startsWith("advertising") || c.contains("tag manager") || c.contains("ad verification") -> "Advertising"
            c.contains("analytics") || c.contains("session") || c.contains("monitoring") || c.contains("diagnostics") -> "Analytics"
            c.contains("marketing") || c.contains("push") -> "Marketing"
            else -> "Tracking"
        }
    }

    fun categories(counts: Map<String, Long>): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for ((label, n) in counts) { val c = category(label); out[c] = (out[c] ?: 0L) + n }
        return out
    }

    /** What happened since the last report. Counts only ever grow — a drop means Noxa's
     *  30-day reset happened, and then everything counts as new. */
    fun delta(now: Map<String, Long>, snapshot: Map<String, Long>): Map<String, Long> {
        val reset = snapshot.any { (k, v) -> (now[k] ?: 0L) < v }
        return now.mapValues { (k, v) -> if (reset) v else v - (snapshot[k] ?: 0L) }.filterValues { it > 0 }
    }

    fun topCompanies(counts: Map<String, Long>, n: Int = 5): List<Pair<String, Long>> =
        counts.filterKeys { category(it) !in SPECIAL }
            .entries.groupBy { Trackers.companyOf(it.key) }.mapValues { e -> e.value.sumOf { it.value } }
            .entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }

    class App(val name: String, val count: Long, val browser: Boolean)

    class Facts(val id: String, val name: String, val model: String, val android: String, val version: String,
                val protectionOn: Boolean, val alwaysOn: Boolean?, val builtAt: String, val stopsToday: Int,
                val today: Long, val days: List<Long>, val since: Map<String, Long>,
                val dangerApps: List<App>, val spyApps: List<App>, val sentAt: Long)

    /** The report. Pure: built from plain values, so it is unit-tested without Android. */
    fun payload(f: Facts): JSONObject = JSONObject().apply {
        put("format", "noxa-home-report")
        put("version", 1)
        put("sent_at", f.sentAt / 1000)
        put("device", JSONObject().apply {
            put("id", f.id); put("name", f.name.take(40)); put("model", f.model.take(40))
            put("android", f.android); put("noxa", f.version)
        })
        put("protection", JSONObject().apply {
            put("on", f.protectionOn)
            if (f.alwaysOn != null) put("always_on", f.alwaysOn)
            put("blocklist_built", f.builtAt); put("stops_today", f.stopsToday)
        })
        put("blocked_today", f.today)
        put("blocked_days", JSONArray(f.days))
        put("since_last", JSONObject().apply {
            put("total", f.since.values.sum())
            put("categories", JSONObject(categories(f.since)))
            put("top_companies", JSONArray(topCompanies(f.since).map { JSONArray(listOf(it.first, it.second)) }))
        })
        put("apps_30d", JSONObject().apply {
            put("dangerous_site", apps(f.dangerApps)); put("stalkerware", apps(f.spyApps))
        })
    }

    private fun apps(list: List<App>) =
        JSONArray(list.take(5).map { JSONArray(listOf(it.name.take(40), it.count, it.browser)) })

    /** The HTTP request bytes (HTTP/1.1, one shot, connection closed by the server). No key = no key header. */
    fun request(host: String, port: Int, key: String, body: ByteArray, path: String = PATH): ByteArray {
        val head = "POST $path HTTP/1.1\r\nHost: $host:$port\r\nUser-Agent: Noxa\r\n" +
            "Content-Type: application/json\r\n" + (if (key.isEmpty()) "" else "$HEADER: $key\r\n") +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        return head.toByteArray(Charsets.ISO_8859_1) + body
    }

    /** The status code of an HTTP reply, or -1 if it isn't one. */
    fun statusOf(reply: String): Int =
        Regex("^HTTP/1\\.[01] (\\d{3})").find(reply)?.groupValues?.get(1)?.toInt() ?: -1

    fun bodyOf(reply: String): String = reply.substringAfter("\r\n\r\n", "")

    // --- finding the server and joining (no typing) ------------------------------
    /** Pure: "NOXA-HOME 192.168.1.20:8790" → the address, when it is a home address. */
    fun parseDiscovery(text: String): String? {
        val t = text.trim()
        if (!t.startsWith("NOXA-HOME ")) return null
        val addr = t.removePrefix("NOXA-HOME ").trim()
        return if (parseAddress(addr) != null) addr else null
    }

    /** One broadcast on the Wi-Fi ("NOXA-HOME?"); the first home server that answers from a private address.
     *  MUST be called off the main thread. */
    fun discover(timeoutMs: Int = 2500): String? {
        val sock = try { java.net.DatagramSocket() } catch (e: Exception) { return null }
        try {
            sock.broadcast = true
            sock.soTimeout = timeoutMs
            val q = "NOXA-HOME?".toByteArray(Charsets.US_ASCII)
            sock.send(java.net.DatagramPacket(q, q.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
            val buf = ByteArray(256)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val p = java.net.DatagramPacket(buf, buf.size)
                try { sock.receive(p) } catch (_: java.net.SocketTimeoutException) { return null }
                val addr = parseDiscovery(String(p.data, 0, p.length, Charsets.US_ASCII))
                if (addr != null && RebindShield.isPrivate(p.address.address)) return addr
            }
            return null
        } catch (e: Exception) { Log.w(TAG, "discover: $e"); return null } finally { sock.close() }
    }

    /** Ask the home server at [address] for this device's own key — works while the parent's door is open.
     *  Saves the address and the key and switches the report on. Returns plain words. Off the main thread. */
    fun join(ctx: Context, address: String): String {
        val target = parseAddress(address) ?: return "That isn't a home address."
        if (TunnelController.isUp) return "Not now: the private tunnel is on."
        if (!onHomeNetwork(ctx)) return "Not on Wi-Fi."
        val body = JSONObject().apply {
            put("format", "noxa-home-join")
            put("device", JSONObject().apply { put("id", id(ctx)); put("name", name(ctx).take(40)); put("model", (Build.MODEL ?: "").take(40)) })
        }.toString().toByteArray(Charsets.UTF_8)
        val reply = try { exchange(target, request(target.host, target.port, "", body, JOIN_PATH)) }
            catch (e: Exception) { return "No answer from ${target.host}:${target.port} — is the home server on?" }
        val json = try { JSONObject(bodyOf(reply)) } catch (_: Exception) { JSONObject() }
        return when (statusOf(reply)) {
            200 -> {
                val key = json.optString("key")
                if (!keyOk(key)) "The home server answered, but without a usable key."
                else {
                    save(ctx, address, key, name(ctx)); setOn(ctx, true)
                    val first = try { send(ctx) } catch (_: Exception) { "" }
                    "Joined. " + json.optString("message") + " " + first
                }
            }
            403 -> json.optString("message").ifBlank { "The door is closed — open it in the home server's app first." }
            -1 -> "Something at ${target.host}:${target.port} answered, but not a home server."
            else -> "The home server answered with error ${statusOf(reply)}."
        }
    }

    // --- restore the device's settings from the home server --------------------------
    class Backup(val id: String, val name: String, val whenText: String)

    /** The settings backups the home server keeps (one per device). Off the main thread. */
    fun backups(ctx: Context): List<Backup>? {
        val target = parseAddress(address(ctx)) ?: return null
        val body = JSONObject().put("device", JSONObject().put("id", id(ctx))).toString().toByteArray(Charsets.UTF_8)
        val reply = try { exchange(target, request(target.host, target.port, key(ctx), body, "/api/home/backups")) } catch (_: Exception) { return null }
        if (statusOf(reply) != 200) return null
        val arr = try { JSONObject(bodyOf(reply)).optJSONArray("backups") } catch (_: Exception) { null } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Backup(it.optString("id"), it.optString("name"), it.optString("when")) } }
    }

    /** Restore one of them into this device (merge, never deletes). Returns plain words. Off the main thread. */
    fun restore(ctx: Context, backupId: String): String {
        val target = parseAddress(address(ctx)) ?: return "That isn't a home address."
        val body = JSONObject().put("device", JSONObject().put("id", id(ctx))).put("id", backupId).toString().toByteArray(Charsets.UTF_8)
        val reply = try { exchange(target, request(target.host, target.port, key(ctx), body, "/api/home/backup")) }
            catch (_: Exception) { return "No answer from ${target.host}:${target.port}." }
        if (statusOf(reply) != 200) return "The home server has no backup for that device."
        val settings = try { JSONObject(bodyOf(reply)).optString("settings") } catch (_: Exception) { "" }
        if (settings.isBlank()) return "The backup was empty."
        return try { AppStats.importSettings(ctx, settings) } catch (e: IllegalArgumentException) { e.message ?: "That wasn't a Noxa backup." }
    }

    // --- doing it --------------------------------------------------------------
    /** Called by the watchdog every ~15 min and at each protection start. */
    fun tick(ctx: Context, now: Long = System.currentTimeMillis()) {
        if (!isOn(ctx)) return
        val p = prefs(ctx)
        if (!due(now, p.getLong(KEY_LAST_SENT, 0L), p.getLong(KEY_LAST_TRY, 0L))) return
        Thread { try { send(ctx, now) } catch (e: Exception) { Log.w(TAG, "home report: $e") } }.start()
    }

    /** Send one report now. Returns what happened, in plain words (shown on the screen).
     *  MUST be called off the main thread. */
    fun send(ctx: Context, now: Long = System.currentTimeMillis()): String {
        val p = prefs(ctx)
        p.edit().putLong(KEY_LAST_TRY, now).apply()
        val target = parseAddress(address(ctx))
            ?: return remember(ctx, "That isn't a home address. Use the server's address on your own Wi-Fi, like 192.168.1.20.")
        val key = key(ctx)
        if (!keyOk(key)) return remember(ctx, "The key is missing or has odd characters.")
        if (TunnelController.isUp) return remember(ctx, "Not sent: the private tunnel is on, so you're not at home.")
        if (!onHomeNetwork(ctx)) return remember(ctx, "Not sent: not on Wi-Fi.")
        val facts = facts(ctx, now)
        val json = payload(facts)
        // the device's own settings (allowed / blocked sites, app choices) ride along when they changed,
        // so a new phone can be restored from the home server (never stats, never history)
        val backup = try { AppStats.exportSettings() } catch (_: Exception) { "" }
        val backupHash = backup.hashCode().toString()
        val sendBackup = backup.isNotEmpty() && backup.length <= MAX_BACKUP && backupHash != (p.getString(KEY_BACKUP_SENT, "") ?: "")
        if (sendBackup) json.put("settings_backup", backup)
        val body = json.toString().toByteArray(Charsets.UTF_8)
        if (body.size > MAX_BODY) return remember(ctx, "The report came out too large — not sent.")
        val reply = try {
            exchange(target, request(target.host, target.port, key, body))
        } catch (e: Exception) {
            Log.w(TAG, "home report: $e")
            return remember(ctx, "No answer from ${target.host}:${target.port} — is the home server on and on this Wi-Fi?")
        }
        return when (val code = statusOf(reply)) {
            200 -> {
                p.edit().putLong(KEY_LAST_SENT, now)
                    .putString(KEY_SNAPSHOT, JSONObject(AppStats.globalCompanyCounts()).toString()).apply()
                if (sendBackup) p.edit().putString(KEY_BACKUP_SENT, backupHash).apply()
                try {                                           // the answer may carry rules for this device
                    val answer = JSONObject(bodyOf(reply))
                    HomeRules.apply(ctx, answer.optJSONObject("rules"), answer.optString("home_list_version"))
                } catch (e: Exception) { Log.w(TAG, "home rules: $e") }
                remember(ctx, "Sent at ${time(now)} — the home server said OK." +
                    (if (HomeRules.blockedCount() > 0 || HomeRules.isManaged("bedtime") || HomeRules.isManaged("safe_search") ||
                         HomeRules.isManaged("family_filter")) " " + HomeRules.summary(ctx) else ""))
            }
            401, 403 -> remember(ctx, "The home server refused the key — check it.")
            -1 -> remember(ctx, "Something at ${target.host}:${target.port} answered, but not a home server.")
            429 -> remember(ctx, "Too soon — the home server just got a report from this device. Try again in a moment.")
            else -> remember(ctx, "The home server answered with error $code.")
        }
    }

    private fun remember(ctx: Context, result: String): String {
        prefs(ctx).edit().putString(KEY_LAST_RESULT, result).apply()
        return result
    }

    private fun time(t: Long): String =
        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(t))

    /** Is a Wi-Fi or Ethernet network up (looking underneath our own VPN)? */
    fun onHomeNetwork(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.let { c ->
                (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                    !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        }
    } catch (_: Exception) { false }

    private fun facts(ctx: Context, now: Long): Facts {
        DailyStats.load(ctx)
        AppStats.load(ctx)
        val counts = AppStats.globalCompanyCounts()
        val snapshot = HashMap<String, Long>()
        try {
            val o = JSONObject(prefs(ctx).getString(KEY_SNAPSHOT, "{}") ?: "{}")
            val it = o.keys()
            while (it.hasNext()) { val k = it.next(); snapshot[k] = o.getLong(k) }
        } catch (_: Exception) {}
        val saved = ctx.getSharedPreferences(GuardianVpnService.PREFS, Context.MODE_PRIVATE)
        return Facts(
            id = id(ctx), name = name(ctx), model = Build.MODEL ?: "", android = Build.VERSION.RELEASE ?: "",
            version = AppUpdater.installedVersion(ctx),
            protectionOn = GuardianVpnService.isRunning.get() || TunnelController.isUp,
            alwaysOn = GuardianVpnService.liveAlwaysOn()?.first
                ?: if (saved.contains(GuardianVpnService.KEY_ALWAYS_ON)) saved.getBoolean(GuardianVpnService.KEY_ALWAYS_ON, false) else null,
            builtAt = FilterUpdater.currentBuiltAt(ctx), stopsToday = HealthMonitor.killsLastDay(ctx, now),
            today = DailyStats.today(), days = DailyStats.lastDays(7).map { it.second },
            since = delta(counts, snapshot),
            dangerApps = named(ctx, AppStats.appsHitting("Dangerous site")),
            spyApps = named(ctx, AppStats.appsHitting("Stalkerware")),
            sentAt = now)
    }

    private fun named(ctx: Context, hits: List<Pair<String, Long>>): List<App> = hits.map { (pkg, n) ->
        val name = if (pkg == AppStats.UNKNOWN) "system / unknown app" else try {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
        App(name, n, looksLikeBrowser(ctx, pkg))
    }

    private fun looksLikeBrowser(ctx: Context, pkg: String): Boolean = try {
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com/"))
        ctx.packageManager.queryIntentActivities(i, 0).any { it.activityInfo.packageName == pkg }
    } catch (_: Exception) { false }

    private fun exchange(t: Target, req: ByteArray): String = exchangeWith(t, req, MAX_REPLY)

    /** One HTTP exchange over a plain socket (the home network only); the reply capped at [maxReply]. */
    fun exchangeWith(t: Target, req: ByteArray, maxReply: Int): String = Socket().use { s ->
        s.connect(InetSocketAddress(InetAddress.getByAddress(t.bytes), t.port), TIMEOUT_MS)
        s.soTimeout = TIMEOUT_MS
        s.getOutputStream().apply { write(req); flush() }
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(2048)
        val input = s.getInputStream()
        while (buf.size() < maxReply) {
            val n = input.read(tmp)
            if (n < 0) break
            buf.write(tmp, 0, n)
        }
        buf.toString("ISO-8859-1")
    }
}
