package com.guardian.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Guardian's core: a LOCAL filter built on Android's VpnService.
 *
 * It is NOT a real VPN — no remote server, nothing leaves the device. It routes
 * the phone's traffic into a local interface so Guardian can inspect the DNS
 * lookups every app makes ("which server do you want to reach?"). Each looked-up
 * domain is checked against the compiled Bloom filter:
 *   - blocked  -> we answer the app with a dead-end address (a "sinkhole"),
 *                 so the tracker connection is never made.
 *   - allowed  -> we forward the lookup to a real DNS resolver and pass the
 *                 answer back, so normal browsing is untouched.
 *
 * DNS is the right layer to filter at: it's the outbound request that decides
 * who an app talks to, it's cheap to inspect, and it keeps battery cost tiny.
 *
 * NOTE: This is the Phase-1 foundation. It compiles as a real Android service
 * and implements the DNS filter loop; it must be built in Android Studio and
 * run on a device/emulator to validate against the DDG baseline (Step 8).
 */
class GuardianVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)

    private lateinit var filter: BloomFilter

    // Phase 2: attribute each DNS query to the app that made it.
    private var connectivity: ConnectivityManager? = null
    private val uidToPkg = java.util.concurrent.ConcurrentHashMap<Int, String>()

    companion object {
        const val ACTION_START = "com.guardian.app.START"
        const val ACTION_STOP = "com.guardian.app.STOP"
        private const val CHANNEL_ID = "guardian_protection"
        private const val NOTIF_ID = 1
        private const val TAG = "Guardian"

        // Persist the running totals so the counter survives the app being killed.
        const val PREFS = "guardian_stats"
        const val KEY_BLOCKED = "blocked"
        const val KEY_ALLOWED = "allowed"
        const val KEY_PERIOD_START = "period_start"
        private const val RESET_MS = 30L * 24 * 60 * 60 * 1000   // roll the stats every 30 days

        // Canary domain: browsers query it before enabling DNS-over-HTTPS. Answer
        // NXDOMAIN and they fall back to plain DNS, which Guardian can filter.
        private const val DOH_CANARY = "use-application-dns.net"

        // Upstream resolver used for ALLOWED lookups (Cloudflare here; a
        // mainstream resolver keeps us in a large anonymity set — see mission).
        private val UPSTREAM_DNS = InetAddress.getByName("1.1.1.1")

        // Live counter the UI reads to show "X tracking attempts blocked".
        val blockedCount = AtomicLong(0)
        val allowedCount = AtomicLong(0)

        // True while the tunnel is up, so the UI switch can show the real state
        // (incl. when Android's Always-on VPN started us without the app open).
        val isRunning = AtomicBoolean(false)

        // Encrypted DNS (DoH): forward allowed lookups over HTTPS so the ISP/Wi-Fi
        // can't read them. Default on; the service falls back to plain DNS on its
        // own if DoH can't be reached, so it can never break connectivity.
        const val KEY_DOH = "encrypted_dns"
        val encryptedDns = AtomicBoolean(true)

        fun setEncryptedDns(ctx: Context, on: Boolean) {
            encryptedDns.set(on)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DOH, on).apply()
        }
    }

    @Volatile private var dohRetryAt = 0L   // back-off clock when DoH is failing

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (running.get()) return
        filter = BloomFilter.loadCurrent(this)          // downloaded update, else bundled
        FilterUpdater.autoCheck(this)                   // quiet once-a-day refresh

        // Restore the saved totals so the counter continues instead of resetting
        // to 0 whenever the app/process was killed (MIUI does this aggressively).
        getSharedPreferences(PREFS, MODE_PRIVATE).let {
            blockedCount.set(it.getLong(KEY_BLOCKED, 0L))
            allowedCount.set(it.getLong(KEY_ALLOWED, 0L))
            encryptedDns.set(it.getBoolean(KEY_DOH, true))
        }
        // Phase 2: per-app stats + firewall, and the service to map query -> app.
        connectivity = getSystemService(ConnectivityManager::class.java)
        AppStats.load(this)
        maybeResetStatsPeriod()          // roll the counters every 30 days

        val builder = Builder()
            .setSession("Noxa")
            .addAddress("10.111.0.1", 32)          // our tun interface (IPv4)
            .addDnsServer("10.111.0.2")            // pseudo DNS server — MUST differ
            .addRoute("10.111.0.2", 32)            // from the interface, routed to us
            .setMtu(1500)
        // Phase 1 captures IPv4 DNS only. Advertising an IPv6 DNS server here
        // made the phone send DNS over IPv6 to us, and any imperfect IPv6 reply
        // triggered a retry storm (hot CPU / battery drain) on device. IPv6 DNS
        // capture is deferred until its response path is validated on hardware.
        // (DnsPacket still understands IPv6 — we just don't advertise it here.)
        // Don't filter Guardian's own traffic (provable zero-telemetry story).
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
        // User-chosen "Don't filter this app" exclusions — for apps that refuse
        // to run when they detect a VPN (Disney+ "no internet", some banking
        // apps). Excluded apps see the plain network: they work, but Noxa
        // can't protect or count them.
        for (pkg in AppStats.noFilterList()) {
            try { builder.addDisallowedApplication(pkg) } catch (_: Exception) {}
        }

        val fd = builder.establish()
        if (fd == null) {
            Log.e(TAG, "establish() returned null — VPN not started")
            return
        }
        tunnel = fd
        running.set(true)
        isRunning.set(true)
        startForeground(NOTIF_ID, buildNotification())
        Log.i(TAG, "Guardian tun up; filter items=${filter.items}")

        worker = Thread({ runLoop() }, "guardian-dns").also { it.start() }
    }

    private fun runLoop() {
        val tun = tunnel ?: return
        val input = FileInputStream(tun.fileDescriptor)
        val output = FileOutputStream(tun.fileDescriptor)
        val buffer = ByteArray(32767)
        var sinceFlush = 0

        // One protected upstream socket, reused for every allowed lookup — far
        // cheaper than opening/closing a fresh socket on every DNS query.
        val upstream = DatagramSocket()
        protect(upstream)
        upstream.soTimeout = 3000
        try { upstream.connect(InetSocketAddress(UPSTREAM_DNS, 53)) } catch (_: Exception) {}

        try {
            while (running.get()) {
                val length = try { input.read(buffer) } catch (e: Exception) { break }
                if (length < 0) break        // EOF: tunnel closed — STOP (never spin the CPU)
                if (length == 0) {           // no data — hard guard so we can never hot-spin,
                    try { Thread.sleep(2) } catch (_: InterruptedException) { break }
                    continue
                }

                val query = DnsPacket.parseQuery(buffer, length) ?: continue
                val pkg = ownerOf(buffer, query)   // which app made this lookup

                when {
                    AppStats.isFirewalled(pkg) -> {
                        // PER-APP FIREWALL: this app is blocked entirely — sinkhole
                        // every lookup it makes, on the same pipeline as everything else.
                        blockedCount.incrementAndGet()
                        AppStats.recordBlocked(pkg, "Blocked by you · Firewall")
                        DnsPacket.buildSinkholeResponse(buffer, length, query)?.let { output.write(it) }
                    }
                    AppStats.isUserAllowed(query.domain) -> {
                        // USER ALLOWLIST: "never block this" — overrides the tracker
                        // filter (and skips CNAME-uncloaking) so it always resolves.
                        forward(buffer, length, query, pkg, upstream, output, skipCname = true)
                    }
                    query.domain == DOH_CANARY -> {
                        // Disable browser auto-DoH: answer the canary NXDOMAIN.
                        blockedCount.incrementAndGet()
                        AppStats.recordBlocked(pkg, "DNS-over-HTTPS · Filter bypass")
                        DnsPacket.buildNxDomainResponse(buffer, length, query)?.let { output.write(it) }
                    }
                    filter.matchesHostOrParent(query.domain) -> {
                        // BLOCKED: sinkhole (0.0.0.0) + record WHO the tracker is (Phase 3).
                        blockedCount.incrementAndGet()
                        AppStats.recordBlocked(pkg, Trackers.label(query.domain))
                        DnsPacket.buildSinkholeResponse(buffer, length, query)?.let { output.write(it) }
                    }
                    else -> {
                        // ALLOWED (unless CNAME-uncloaking finds a tracker in the
                        // answer). forward() does the allowed/blocked counting.
                        forward(buffer, length, query, pkg, upstream, output)
                    }
                }
                // Save the totals to disk every so often so a kill can't lose them.
                if (++sinceFlush >= 50) { saveStats(); AppStats.save(this); sinceFlush = 0 }
            }
        } finally {
            saveStats()
            AppStats.save(this)
            try { upstream.close() } catch (_: Exception) {}
        }
        // If we fell out of the loop while still "running", the tunnel died
        // (e.g. network change / EOF). Shut down cleanly instead of leaving a
        // hot, half-dead service — the user can flip the switch to restart.
        if (running.get()) stopVpn()
    }

    /** Relay an allowed DNS query upstream and write the answer back to the tun.
     *  Prefers encrypted DNS (DoH); falls back to plain DNS so it never breaks.
     *  Also CNAME-uncloaks: if the answer's CNAME chain points at a tracker, the
     *  query is blocked instead (does the allowed/blocked counting itself). */
    private fun forward(ipPacket: ByteArray, len: Int, q: DnsPacket.Query, pkg: String,
                        sock: DatagramSocket, tunOut: FileOutputStream, skipCname: Boolean = false) {
        try {
            val payload = DnsPacket.extractUdpPayload(ipPacket, len) ?: return

            // Get the upstream answer — encrypted DNS preferred (30s back-off on
            // failure so a blocked :443 can't stall every query), else plain DNS.
            var reply: ByteArray? = null
            if (encryptedDns.get() && System.currentTimeMillis() >= dohRetryAt) {
                reply = resolveDoh(payload)
                if (reply == null) dohRetryAt = System.currentTimeMillis() + 30_000L
            }
            if (reply == null) {
                sock.send(java.net.DatagramPacket(payload, payload.size))
                val buf = ByteArray(1500)
                val resp = java.net.DatagramPacket(buf, buf.size)
                sock.receive(resp)
                reply = buf.copyOf(resp.length)
            }

            // CNAME-uncloaking: a tracker hiding behind a first-party subdomain
            // shows up as a CNAME to a blocked domain — sinkhole it. (Skipped when
            // the user explicitly allowlisted the domain.)
            val cloaked = if (skipCname) null else DnsPacket.cnameTargets(reply, reply.size)
                .firstOrNull { filter.matchesHostOrParent(it) }
            if (cloaked != null) {
                blockedCount.incrementAndGet()
                AppStats.recordBlocked(pkg, "${Trackers.companyOf(Trackers.label(cloaked))} · CNAME-cloaked")
                DnsPacket.buildSinkholeResponse(ipPacket, len, q)?.let { tunOut.write(it) }
                return
            }

            // Genuinely allowed — return the real answer.
            allowedCount.incrementAndGet(); AppStats.recordAllowed(pkg)
            DnsPacket.buildForwardedResponse(ipPacket, len, reply, reply.size)?.let { tunOut.write(it) }
        } catch (e: Exception) {
            Log.w(TAG, "fwd fail: $e")
        }
    }

    /** DNS-over-HTTPS to Cloudflare *by IP* (1.1.1.1) so no bootstrap DNS lookup
     *  is needed. Guardian's own traffic is excluded from the VPN, so this can't
     *  loop. Returns the raw DNS answer, or null on any failure (caller falls back). */
    private fun resolveDoh(query: ByteArray): ByteArray? {
        return try {
            val conn = URL("https://1.1.1.1/dns-query").openConnection() as HttpsURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/dns-message")
            conn.setRequestProperty("Accept", "application/dns-message")
            conn.outputStream.use { it.write(query) }
            if (conn.responseCode != 200) { conn.errorStream?.close(); null }
            else conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    /** Best-effort: which app's package made this DNS query (needs API 29+, IPv4).
     *  Returns AppStats.UNKNOWN when it can't be attributed. */
    private fun ownerOf(buffer: ByteArray, q: DnsPacket.Query): String {
        val cm = connectivity
        if (cm == null || Build.VERSION.SDK_INT < 29 || q.ipVersion != 4) return AppStats.UNKNOWN
        return try {
            val src = InetAddress.getByAddress(buffer.copyOfRange(12, 16))
            val dst = InetAddress.getByAddress(buffer.copyOfRange(16, 20))
            val sport = ((buffer[q.udpStart].toInt() and 0xFF) shl 8) or
                (buffer[q.udpStart + 1].toInt() and 0xFF)
            val uid = cm.getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(src, sport),
                InetSocketAddress(dst, 53)
            )
            if (uid < 0) AppStats.UNKNOWN
            else uidToPkg.computeIfAbsent(uid) {
                try { packageManager.getPackagesForUid(it)?.firstOrNull() ?: AppStats.UNKNOWN }
                catch (e: Exception) { AppStats.UNKNOWN }
            }
        } catch (e: Exception) {
            AppStats.UNKNOWN
        }
    }

    private fun stopVpn() {
        saveStats()
        AppStats.save(this)
        running.set(false)
        isRunning.set(false)
        try { worker?.interrupt() } catch (_: Exception) {}
        try { tunnel?.close() } catch (_: Exception) {}
        tunnel = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Write the running totals to disk so they survive the app being killed. */
    private fun saveStats() {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(KEY_BLOCKED, blockedCount.get())
                .putLong(KEY_ALLOWED, allowedCount.get())
                .apply()
        } catch (_: Exception) {}
    }

    /** Roll all stats over every 30 days so the numbers reflect a recent window,
     *  not an ever-growing all-time total. Keeps firewall/tunnel settings. */
    private fun maybeResetStatsPeriod() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val start = prefs.getLong(KEY_PERIOD_START, 0L)
        if (start == 0L) {
            prefs.edit().putLong(KEY_PERIOD_START, now).apply()
            return
        }
        if (now - start >= RESET_MS) {
            blockedCount.set(0L)
            allowedCount.set(0L)
            AppStats.clearAll()
            saveStats()
            AppStats.save(this)
            prefs.edit().putLong(KEY_PERIOD_START, now).apply()
            Log.i(TAG, "stats reset for a new 30-day period")
        }
    }

    override fun onDestroy() { stopVpn(); super.onDestroy() }

    // --- notification (foreground service requirement) -----------------------
    private fun buildNotification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Protection",
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Noxa is protecting you")
            .setContentText("Blocking trackers and ads")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
