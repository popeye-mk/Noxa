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
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    // Allowed lookups are resolved upstream by a few worker threads, so one
    // slow answer (bad Wi-Fi, DoH timeout) can't stall every other app's DNS.
    // The tun read loop only does the fast work: parse, filter, sinkhole.
    private class Job(val packet: ByteArray, val len: Int, val q: DnsPacket.Query,
                      val pkg: String, val skipCname: Boolean)
    private val jobs = ArrayBlockingQueue<Job>(256)   // full -> drop; the app retries
    private val workers = ArrayList<Thread>()
    private val cache = DnsCache()

    // Phase 2: attribute each DNS query to the app that made it.
    private var connectivity: ConnectivityManager? = null
    private val uidToPkg = java.util.concurrent.ConcurrentHashMap<Int, String>()

    companion object {
        const val ACTION_START = "com.guardian.app.START"
        const val ACTION_STOP = "com.guardian.app.STOP"
        private const val CHANNEL_ID = "guardian_protection"
        private const val NOTIF_ID = 1
        private const val TAG = "Guardian"

        // v1.1: capture DNS over IPv6 too (closes the IPv6 bypass). Flip to
        // false + rebuild for instant rollback to IPv4-only capture.
        private const val IPV6_DNS = true

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
        private const val WORKERS = 4
        private const val UPSTREAM_TIMEOUT_MS = 3000

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

        // v1.2 keep-alive: remembers whether the USER wants protection on, so
        // the watchdog can tell "killed by the OS" (restart!) apart from
        // "turned off by the user" (leave it off).
        private const val KEY_WANT = "protection_wanted"
        fun wantsProtection(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WANT, false)
        fun setWantsProtection(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_WANT, on).apply()
        }
    }

    @Volatile private var dohRetryAt = 0L   // back-off clock when DoH is failing
    private val dohFailures = AtomicInteger(0)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setWantsProtection(this, false)      // the USER said stop —
            WatchdogReceiver.cancel(this)        // the watchdog must not resurrect it
            stopVpn()
            return START_NOT_STICKY
        }
        // Go foreground IMMEDIATELY: when the watchdog restarts us from the
        // background it must use startForegroundService(), and Android kills
        // services that don't show their notification within seconds.
        startForeground(NOTIF_ID, buildNotification())
        // NEVER steal the one VPN slot from the user's WireGuard tunnel: a
        // sticky/watchdog restart while the tunnel is up would silently kick
        // it off — "IP hidden" on screen, real IP on the wire. Refuse.
        if (TunnelController.isUp) {
            stopForeground(true); stopSelf()
            return START_NOT_STICKY
        }
        // A null intent is Android sticky-restarting us after a kill. Honour
        // the user's last choice: if they had turned protection off, stay off.
        if (intent == null && !wantsProtection(this)) {
            stopForeground(true); stopSelf()
            return START_NOT_STICKY
        }
        setWantsProtection(this, true)
        WatchdogReceiver.schedule(this)
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
        // IPv6 DNS capture (v1.1). The original attempt caused a retry storm on
        // device — replies lacked the MANDATORY IPv6 UDP checksum, so the phone
        // silently dropped every one and retried forever (hot CPU, dead battery).
        // wrapIpv6 now computes the pseudo-header checksum and is cross-verified
        // byte-level by build-tools/test_packets.py ("IPv6 UDP checksum valid").
        // Same lesson as IPv4: interface address and DNS address MUST differ.
        // Kill-switch: set IPV6_DNS=false and rebuild to fall back to v1.0.2
        // behaviour exactly.
        if (IPV6_DNS) {
            try {
                builder.addAddress("fd6e:7f3a:9c11::1", 128)
                builder.addDnsServer("fd6e:7f3a:9c11::2")
                builder.addRoute("fd6e:7f3a:9c11::2", 128)
            } catch (e: Exception) {
                Log.w(TAG, "IPv6 DNS setup failed, continuing IPv4-only: $e")
            }
        }
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
            stopForeground(true)   // don't linger as a foreground zombie
            stopSelf()             // watchdog will retry on its next tick
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

        synchronized(workers) {
            repeat(WORKERS) { i ->
                workers += Thread({ workerLoop(output) }, "guardian-dns-$i").also { it.start() }
            }
        }

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
                        DnsPacket.buildSinkholeResponse(buffer, length, query)?.let { writeTun(output, it) }
                    }
                    AppStats.isUserAllowed(query.domain) -> {
                        // USER ALLOWLIST: "never block this" — overrides the tracker
                        // filter (and skips CNAME-uncloaking) so it always resolves.
                        resolve(buffer, length, query, pkg, output, skipCname = true)
                    }
                    query.domain == DOH_CANARY -> {
                        // Disable browser auto-DoH: answer the canary NXDOMAIN.
                        blockedCount.incrementAndGet()
                        AppStats.recordBlocked(pkg, "DNS-over-HTTPS · Filter bypass")
                        DnsPacket.buildNxDomainResponse(buffer, length, query)?.let { writeTun(output, it) }
                    }
                    filter.matchesHostOrParent(query.domain) -> {
                        // BLOCKED: sinkhole (0.0.0.0) + record WHO the tracker is (Phase 3).
                        blockedCount.incrementAndGet()
                        AppStats.recordBlocked(pkg, Trackers.label(query.domain))
                        DnsPacket.buildSinkholeResponse(buffer, length, query)?.let { writeTun(output, it) }
                    }
                    else -> {
                        // ALLOWED (unless CNAME-uncloaking finds a tracker in the
                        // answer). answer() does the allowed/blocked counting.
                        resolve(buffer, length, query, pkg, output)
                    }
                }
                // Save the totals to disk every so often so a kill can't lose them.
                if (++sinceFlush >= 50) { saveStats(); AppStats.save(this); sinceFlush = 0 }
            }
        } finally {
            saveStats()
            AppStats.save(this)
            stopWorkers()
        }
        // If we fell out of the loop while still "running", the tunnel died
        // (e.g. network change / EOF). Shut down cleanly instead of leaving a
        // hot, half-dead service — the user can flip the switch to restart.
        if (running.get()) stopVpn()
    }

    /** Serialise tun writes: the read loop and the workers all answer apps. */
    private fun writeTun(out: FileOutputStream, bytes: ByteArray) {
        synchronized(out) { out.write(bytes) }
    }

    /** An allowed lookup: answer straight from the cache when we can (no
     *  network at all), otherwise queue it for a worker to resolve upstream. */
    private fun resolve(ipPacket: ByteArray, len: Int, q: DnsPacket.Query, pkg: String,
                        tunOut: FileOutputStream, skipCname: Boolean = false) {
        val payload = DnsPacket.extractUdpPayload(ipPacket, len) ?: return
        val cached = cache.get(q, payload)
        if (cached != null) {
            answer(ipPacket, len, q, pkg, cached, tunOut, skipCname)
            return
        }
        // The loop reuses its buffer, so the job gets its own copy.
        if (!jobs.offer(Job(ipPacket.copyOf(len), len, q, pkg, skipCname)))
            Log.w(TAG, "resolver queue full, dropping lookup (app will retry)")
    }

    /** One resolver worker: its own protected upstream socket, so replies can
     *  never be read by the wrong thread. */
    private fun workerLoop(tunOut: FileOutputStream) {
        val sock = try {
            DatagramSocket().also {
                protect(it)
                it.connect(InetSocketAddress(UPSTREAM_DNS, 53))
            }
        } catch (e: Exception) { Log.w(TAG, "worker socket failed: $e"); return }
        try {
            // Interrupted = this worker belongs to a stopped session; exit even
            // if protection was already switched back on (new workers exist).
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val job = try { jobs.poll(1, TimeUnit.SECONDS) } catch (_: InterruptedException) { break }
                    ?: continue
                forward(job, sock, tunOut)
            }
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun stopWorkers() {
        synchronized(workers) {
            for (w in workers) try { w.interrupt() } catch (_: Exception) {}
            workers.clear()
        }
        jobs.clear()
    }

    /** Relay an allowed DNS query upstream and write the answer back to the tun.
     *  Prefers encrypted DNS (DoH); falls back to plain DNS so it never breaks. */
    private fun forward(job: Job, sock: DatagramSocket, tunOut: FileOutputStream) {
        try {
            val payload = DnsPacket.extractUdpPayload(job.packet, job.len) ?: return

            // Get the upstream answer — encrypted DNS preferred (growing back-off
            // on failure so a blocked :443 can't stall every query), else plain DNS.
            var reply: ByteArray? = null
            if (encryptedDns.get() && System.currentTimeMillis() >= dohRetryAt) {
                reply = resolveDoh(payload)
                if (reply == null) {
                    // 5s, 10s, 20s, then every 30s while DoH stays unreachable.
                    val n = dohFailures.incrementAndGet().coerceAtMost(4)
                    dohRetryAt = System.currentTimeMillis() + minOf(30_000L, 5_000L shl (n - 1))
                } else {
                    dohFailures.set(0)
                }
            }
            if (reply == null) reply = resolvePlain(payload, sock) ?: return

            cache.put(job.q, reply)
            answer(job.packet, job.len, job.q, job.pkg, reply, tunOut, job.skipCname)
        } catch (e: Exception) {
            Log.w(TAG, "fwd fail: $e")
        }
    }

    /** Plain UDP DNS. Only accepts a reply whose transaction ID matches this
     *  query: a late answer to an earlier, timed-out query can still arrive on
     *  the socket and must never be handed to the wrong lookup. */
    private fun resolvePlain(payload: ByteArray, sock: DatagramSocket): ByteArray? {
        sock.send(java.net.DatagramPacket(payload, payload.size))
        val deadline = System.currentTimeMillis() + UPSTREAM_TIMEOUT_MS
        val buf = ByteArray(4096)
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            sock.soTimeout = left.toInt()
            val resp = java.net.DatagramPacket(buf, buf.size)
            try { sock.receive(resp) } catch (_: SocketTimeoutException) { return null }
            if (resp.length >= 12 && buf[0] == payload[0] && buf[1] == payload[1])
                return buf.copyOf(resp.length)
            // stale reply for an earlier query — discard and keep waiting
        }
    }

    /** Deliver an upstream (or cached) answer to the app. CNAME-uncloaks first:
     *  if the answer's CNAME chain points at a tracker, the query is blocked
     *  instead. Does the allowed/blocked counting itself. */
    private fun answer(ipPacket: ByteArray, len: Int, q: DnsPacket.Query, pkg: String,
                       reply: ByteArray, tunOut: FileOutputStream, skipCname: Boolean) {
        try {
            // CNAME-uncloaking: a tracker hiding behind a first-party subdomain
            // shows up as a CNAME to a blocked domain — sinkhole it. (Skipped when
            // the user explicitly allowlisted the domain.)
            val cloaked = if (skipCname) null else DnsPacket.cnameTargets(reply, reply.size)
                .firstOrNull { filter.matchesHostOrParent(it) }
            if (cloaked != null) {
                blockedCount.incrementAndGet()
                AppStats.recordBlocked(pkg, "${Trackers.companyOf(Trackers.label(cloaked))} · CNAME-cloaked")
                DnsPacket.buildSinkholeResponse(ipPacket, len, q)?.let { writeTun(tunOut, it) }
                return
            }

            // Genuinely allowed — return the real answer.
            allowedCount.incrementAndGet(); AppStats.recordAllowed(pkg)
            DnsPacket.buildForwardedResponse(ipPacket, len, reply, reply.size)?.let { writeTun(tunOut, it) }
        } catch (e: Exception) {
            Log.w(TAG, "answer fail: $e")
        }
    }

    /** DNS-over-HTTPS to Cloudflare *by IP* (1.1.1.1) so no bootstrap DNS lookup
     *  is needed. Guardian's own traffic is excluded from the VPN, so this can't
     *  loop. Returns the raw DNS answer, or null on any failure (caller falls back). */
    private fun resolveDoh(query: ByteArray): ByteArray? {
        return try {
            val conn = URL("https://1.1.1.1/dns-query").openConnection() as HttpsURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = UPSTREAM_TIMEOUT_MS
            conn.readTimeout = UPSTREAM_TIMEOUT_MS
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
        stopWorkers()
        cache.clear()
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
