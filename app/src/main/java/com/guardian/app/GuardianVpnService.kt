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

    // Volatile: a downloaded update is swapped in live (v1.5), no restart needed.
    @Volatile private lateinit var filter: BloomFilter

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
        const val ACTION_PAUSE = "com.guardian.app.PAUSE"
        const val EXTRA_FROM_WATCHDOG = "from_watchdog"
        const val PAUSE_MS = 5L * 60 * 1000
        private const val PAUSED_NOTIF_ID = 2
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
        // Recorded on each start for the check-up screen (only a running
        // VpnService can ask Android these).
        const val KEY_ALWAYS_ON = "always_on"
        const val KEY_LOCKDOWN = "lockdown"
        private const val RESET_MS = 30L * 24 * 60 * 60 * 1000   // roll the stats every 30 days

        // Canary domain: browsers query it before enabling DNS-over-HTTPS. Answer
        // NXDOMAIN and they fall back to plain DNS, which Guardian can filter.
        private const val DOH_CANARY = "use-application-dns.net"

        // Upstream resolver for ALLOWED lookups: the user's choice (default
        // Cloudflare — a mainstream resolver keeps us in a large anonymity set).
        // Read live from DnsProviders.current, so a change applies at once.
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
        private const val KEY_PAUSED_UNTIL = "paused_until"

        /** v1.5 "Pause 5 min": protection is off until this time, then the
         *  watchdog brings it back. 0 = not paused. */
        fun pausedUntil(ctx: Context): Long =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_PAUSED_UNTIL, 0L)
        fun isPaused(ctx: Context): Boolean = pausedUntil(ctx) > System.currentTimeMillis()
        private fun setPausedUntil(ctx: Context, t: Long) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_PAUSED_UNTIL, t).apply()
        }

        // v1.8 self-heal, with a BACKOFF so it can never become a battery fire:
        // 3 s, 10 s, 30 s, 2 min, then hand over to the 15-min watchdog. The
        // counter resets once the tunnel has stayed up for 5 minutes.
        private val HEAL_DELAYS_MS = longArrayOf(3_000L, 10_000L, 30_000L, 120_000L)
        private val healAttempts = AtomicInteger(0)
        @Volatile private var tunUpAt = 0L

        /** Delay for the next self-heal, or null = stop trying (watchdog takes over). */
        fun nextHealDelay(now: Long = System.currentTimeMillis()): Long? {
            if (tunUpAt != 0L && now - tunUpAt > 5L * 60 * 1000) healAttempts.set(0)   // it was stable: fresh start
            val n = healAttempts.getAndIncrement()
            return HEAL_DELAYS_MS.getOrNull(n)
        }

        // Set by FilterUpdater after it installs a newer list; the running
        // service picks it up on its next lookup and swaps the filter live.
        private val filterReload = AtomicBoolean(false)
        fun requestFilterReload() = filterReload.set(true)
        // v1.9.2: the check-up asks the RUNNING service live. Before, Always-on
        // was only recorded at start, so switching it on while Noxa ran still
        // showed "Off" until the next restart. Weak ref: never leaks the service.
        @Volatile private var live: java.lang.ref.WeakReference<GuardianVpnService>? = null

        /** (alwaysOn, lockdown) from the running service; null if not running
         *  or Android < 10 (the API doesn't exist there). */
        fun liveAlwaysOn(): Pair<Boolean, Boolean>? {
            if (Build.VERSION.SDK_INT < 29) return null
            val s = live?.get() ?: return null
            if (!isRunning.get()) return null
            return try { s.isAlwaysOn() to s.isLockdownEnabled() } catch (_: Exception) { null }
        }

        fun wantsProtection(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WANT, false)
        fun setWantsProtection(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_WANT, on).apply()
        }
    }

    @Volatile private var shownToday = -1L   // v1.12: last count drawn in the notification
    private fun screenOn(): Boolean = try {
        getSystemService(android.os.PowerManager::class.java).isInteractive
    } catch (_: Exception) { true }

    @Volatile private var dohRetryAt = 0L   // back-off clock when DoH is failing
    private val upstreamMisses = AtomicInteger(0)   // v1.11 automatic resolver fallback
    private val dohFailures = AtomicInteger(0)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setWantsProtection(this, false)      // the USER said stop —
            WatchdogReceiver.cancel(this)        // the watchdog must not resurrect it
            setPausedUntil(this, 0L)             // a real "off" also ends any pause
            getSystemService(NotificationManager::class.java).cancel(PAUSED_NOTIF_ID)
            stopVpn()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE) {
            // Keep wantsProtection = true: this is a timed break, not "off".
            setPausedUntil(this, System.currentTimeMillis() + PAUSE_MS)
            WatchdogReceiver.scheduleResume(this, PAUSE_MS)
            stopVpn()
            showPausedNotification()
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
        // v1.11: a sticky restart, or a watchdog restart that isn't a planned
        // resume, means the OS killed protection — remember it (battery check).
        if (intent == null || intent.getBooleanExtra(EXTRA_FROM_WATCHDOG, false)) HealthMonitor.recordKill(this)
        setWantsProtection(this, true)
        // An explicit start ends any pause ("Resume now", the switch, the tile).
        // A sticky/watchdog restart while still paused stays off.
        if (intent == null && isPaused(this)) {
            stopForeground(true); stopSelf()
            return START_NOT_STICKY
        }
        setPausedUntil(this, 0L)
        getSystemService(NotificationManager::class.java).cancel(PAUSED_NOTIF_ID)
        WatchdogReceiver.schedule(this)
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (running.get()) return
        filter = BloomFilter.loadCurrent(this)          // downloaded update, else bundled
        Stalkerware.load(this)
        Threats.load(this)
        ScamShield.load(this)
        DnsProviders.load(this)
        HealthMonitor.onProtectionStarted(this)
        val appCtx = applicationContext
        LiveLog.listener = { e ->
            if (StuckAppDetector.observe(e.pkg, e.domain, e.verdict, e.label)) StuckAppDetector.offer(appCtx, e.pkg)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_ALWAYS_ON, isAlwaysOn()).putBoolean(KEY_LOCKDOWN, isLockdownEnabled()).apply()
            } catch (_: Exception) {}
        }
        FilterUpdater.autoCheck(this)                   // quiet once-a-day refresh
        AppUpdater.autoCheck(this)                      // "a newer Noxa is available"

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
        DailyStats.load(this)
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
        tunUpAt = System.currentTimeMillis()
        running.set(true)
        isRunning.set(true)
        NoxaWidget.refreshAll(this)
        NoxaTileService.refresh(this)
        startForeground(NOTIF_ID, buildNotification())
        Log.i(TAG, "Guardian tun up; filter items=${filter.items}")

        worker = Thread({ runLoop() }, "guardian-dns").also { it.start() }
    }

    private fun runLoop() {
        val tun = tunnel ?: return
        val input = FileInputStream(tun.fileDescriptor)
        val output = FileOutputStream(tun.fileDescriptor)
        val buffer = ByteArray(32767)
        var lastFlush = System.currentTimeMillis()

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

                if (filterReload.compareAndSet(true, false)) reloadFilterAsync()

                when {
                    AppStats.blockForData(pkg, NetworkWatcher.onMobileData) -> {
                        // v1.12 SAVE MOBILE DATA: the user keeps this app offline
                        // on mobile data; on Wi-Fi it works normally.
                        onBlocked()
                        AppStats.recordBlocked(pkg, "Blocked by you · Mobile data")
                        LiveLog.add(pkg, query.domain, LiveLog.Verdict.FIREWALL, "Blocked on mobile data (by you)")
                        DnsPacket.buildSinkholeResponse(buffer, length, query)?.let { writeTun(output, it) }
                    }
                    AppStats.isFirewalled(pkg) -> {
                        // PER-APP FIREWALL: this app is blocked entirely — sinkhole
                        // every lookup it makes, on the same pipeline as everything else.
                        onBlocked()
                        AppStats.recordBlocked(pkg, "Blocked by you · Firewall")
                        LiveLog.add(pkg, query.domain, LiveLog.Verdict.FIREWALL, "Whole app blocked by you")
                        DnsPacket.buildSinkholeResponse(buffer, length, query)?.let { writeTun(output, it) }
                    }
                    AppStats.isUserBlocked(query.domain) -> {
                        // USER BLOCK LIST (v1.5): "always block this" — the user's
                        // explicit choice wins over everything below, allowlist included.
                        onBlocked()
                        AppStats.recordBlocked(pkg, "Blocked by you · Site")
                        LiveLog.add(pkg, query.domain, LiveLog.Verdict.MINE, "On your block list")
                        DnsPacket.buildSinkholeResponse(buffer, length, query)?.let { writeTun(output, it) }
                    }
                    AppStats.isUserAllowed(query.domain) -> {
                        // USER ALLOWLIST: "never block this" — overrides the tracker
                        // filter (and skips CNAME-uncloaking) so it always resolves.
                        resolve(buffer, length, query, pkg, output, skipCname = true)
                    }
                    query.domain == DOH_CANARY -> {
                        // Disable browser auto-DoH: answer the canary NXDOMAIN.
                        onBlocked()
                        AppStats.recordBlocked(pkg, "DNS-over-HTTPS · Filter bypass")
                        LiveLog.add(pkg, query.domain, LiveLog.Verdict.BLOCKED, "DNS-over-HTTPS · Filter bypass")
                        DnsPacket.buildNxDomainResponse(buffer, length, query)?.let { writeTun(output, it) }
                    }
                    filter.matchesHostOrParent(query.domain) -> {
                        // BLOCKED: sinkhole (0.0.0.0) + record WHO the tracker is (Phase 3).
                        onBlocked()
                        val spy = Stalkerware.matches(query.domain)
                        val danger = !spy && Threats.matches(query.domain)
                        val label = when {
                            spy -> Stalkerware.LABEL
                            danger -> Threats.LABEL
                            else -> Trackers.label(query.domain)
                        }
                        AppStats.recordBlocked(pkg, label)
                        LiveLog.add(pkg, query.domain, LiveLog.Verdict.BLOCKED, if (spy || danger) "⚠ $label" else label)
                        if (spy) Stalkerware.alert(this, pkg, query.domain)
                        if (danger) Threats.alert(this, pkg, query.domain)
                        DnsPacket.buildSinkholeResponse(buffer, length, query)?.let { writeTun(output, it) }
                    }
                    else -> {
                        // v1.10: not on any list — but does it IMPERSONATE a brand
                        // (paypa1.com), or use a risky ending with strict mode on?
                        val fake = FakeSites.lookalikeOf(query.domain)
                        val risky = if (fake == null) ScamShield.riskyEnding(query.domain) else null
                        if (fake != null || risky != null) {
                            onBlocked()
                            val label = if (fake != null) FakeSites.LABEL else ScamShield.LABEL
                            AppStats.recordBlocked(pkg, label)
                            LiveLog.add(pkg, query.domain, LiveLog.Verdict.BLOCKED,
                                if (fake != null) "⚠ Pretends to be ${fake.name}" else "⚠ Risky ending .$risky (strict mode)")
                            if (fake != null) Threats.warn(this, query.domain,
                                "⚠ Fake ${fake.name} site blocked",
                                "${query.domain} pretends to be ${fake.name}, but it is not ${fake.name}'s " +
                                "real website. Noxa blocked it. Don't enter passwords or card details " +
                                "there. If you're sure it's genuine, add it under Settings & tools → Allowed sites.")
                            else Threats.warn(this, query.domain,
                                "Risky website blocked",
                                "${query.domain} uses the .$risky ending, which is mostly used for scams, so " +
                                "Strict scam protection blocked it. If you trust this site, add it under " +
                                "Settings & tools → Allowed sites.")
                            DnsPacket.buildSinkholeResponse(buffer, length, query)?.let { writeTun(output, it) }
                        } else {
                            // ALLOWED (unless CNAME-uncloaking finds a tracker in the
                            // answer). answer() does the allowed/blocked counting.
                            resolve(buffer, length, query, pkg, output)
                        }
                    }
                }
                // Save the totals to disk every 30 s so a kill can't lose much —
                // time-based, so a busy phone doesn't rewrite storage constantly.
                val now = System.currentTimeMillis()
                if (now - lastFlush >= 30_000L) {
                    lastFlush = now
                    // v1.12 battery: only write when something changed, and only
                    // redraw the notification/widget when the count moved AND
                    // the screen is on (nobody sees them otherwise).
                    if (AppStats.dirty) { saveStats(); AppStats.save(this); DailyStats.save(this) }
                    val today = DailyStats.today()
                    if (today != shownToday && screenOn()) {
                        shownToday = today
                        NoxaWidget.refreshAll(this)          // home-screen count stays fresh
                        refreshNotification()                // "…N blocked today" in the shade
                    }
                }
            }
        } finally {
            saveStats()
            AppStats.save(this)
            stopWorkers()
        }
        // If we fell out of the loop while still "running", the tunnel died
        // (e.g. network change / EOF). Shut down cleanly instead of leaving a
        // hot, half-dead service — and (v1.8) come straight back: the user
        // still wants protection, so retry in a few seconds rather than
        // waiting up to 15 min for the watchdog.
        if (running.get()) {
            stopVpn()
            if (wantsProtection(this) && !isPaused(this) && !TunnelController.isUp) {
                val delay = nextHealDelay()
                if (delay != null) {
                    Log.w(TAG, "tunnel died unexpectedly — self-heal in ${delay / 1000} s")
                    WatchdogReceiver.scheduleResume(this, delay, wakeup = false)   // no CPU wake-up for this
                } else {
                    Log.w(TAG, "tunnel keeps dying — leaving it to the 15-min watchdog")
                }
            }
        }
    }

    /** Every block, from any path: the live counter + the per-day history. */
    private fun onBlocked() {
        blockedCount.incrementAndGet()
        DailyStats.recordBlock()
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
     *  never be read by the wrong thread. Unconnected, so a provider change
     *  applies to the next lookup without restarting anything. */
    private fun workerLoop(tunOut: FileOutputStream) {
        val sock = try {
            DatagramSocket().also { protect(it) }
        } catch (e: Exception) { Log.w(TAG, "worker socket failed: $e"); return }
        try {
            // Interrupted = this worker belongs to a stopped session; exit even
            // if protection was already switched back on (new workers exist).
            while (running.get() && !Thread.currentThread().isInterrupted) {
                // v1.12 battery: block until work arrives. The old 1-second poll
                // woke 4 threads 4x a second all day, even with the screen off.
                // stopWorkers() interrupts, which ends take() cleanly.
                val job = try { jobs.take() } catch (_: InterruptedException) { break }
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
            if (reply == null) reply = resolvePlain(payload, sock)
            if (reply == null) {
                // v1.11: the resolver isn't answering. After 3 misses in a row,
                // switch to the next provider for 10 minutes (automatically).
                if (upstreamMisses.incrementAndGet() >= 3) {
                    DnsProviders.failover()?.let { Log.w(TAG, "resolver not answering — using ${it.name} for now") }
                    upstreamMisses.set(0)
                }
                return
            }
            upstreamMisses.set(0)

            cache.put(job.q, reply)
            answer(job.packet, job.len, job.q, job.pkg, reply, tunOut, job.skipCname)
        } catch (e: Exception) {
            Log.w(TAG, "fwd fail: $e")
        }
    }

    /** Plain UDP DNS. Only accepts a reply from the resolver we asked, whose
     *  transaction ID matches this query: a late answer to an earlier,
     *  timed-out query can still arrive and must never reach the wrong lookup. */
    private fun resolvePlain(payload: ByteArray, sock: DatagramSocket): ByteArray? {
        val upstream = DnsProviders.active.address
        sock.send(java.net.DatagramPacket(payload, payload.size, upstream, 53))
        val deadline = System.currentTimeMillis() + UPSTREAM_TIMEOUT_MS
        val buf = ByteArray(4096)
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            sock.soTimeout = left.toInt()
            val resp = java.net.DatagramPacket(buf, buf.size)
            try { sock.receive(resp) } catch (_: SocketTimeoutException) { return null }
            if (resp.length >= 12 && buf[0] == payload[0] && buf[1] == payload[1] &&
                resp.address == upstream)
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
                onBlocked()
                val label = "${Trackers.companyOf(Trackers.label(cloaked))} · CNAME-cloaked"
                AppStats.recordBlocked(pkg, label)
                LiveLog.add(pkg, q.domain, LiveLog.Verdict.CLOAKED, "$label (hides $cloaked)")
                DnsPacket.buildSinkholeResponse(ipPacket, len, q)?.let { writeTun(tunOut, it) }
                return
            }

            // v1.13 router attack shield: a public name answering with a home /
            // LAN address is a rebinding attack. Allowed-sites entries skip this.
            if (!skipCname && !NetworkWatcher.loginPending && RebindShield.refuse(q.domain, reply, reply.size)) {
                onBlocked()
                AppStats.recordBlocked(pkg, RebindShield.LABEL)
                LiveLog.add(pkg, q.domain, LiveLog.Verdict.BLOCKED, "⚠ Points into your home network (router attack shield)")
                DnsPacket.buildSinkholeResponse(ipPacket, len, q)?.let { writeTun(tunOut, it) }
                return
            }

            // Genuinely allowed — return the real answer.
            allowedCount.incrementAndGet(); AppStats.recordAllowed(pkg)
            LiveLog.add(pkg, q.domain, LiveLog.Verdict.ALLOWED)
            DnsPacket.buildForwardedResponse(ipPacket, len, reply, reply.size)?.let { writeTun(tunOut, it) }
        } catch (e: Exception) {
            Log.w(TAG, "answer fail: $e")
        }
    }

    /** DNS-over-HTTPS to the chosen provider. Noxa's own traffic is excluded
     *  from the VPN, so this can't loop (and a DoH hostname resolves via the
     *  system resolver). Returns the raw DNS answer, or null on any failure
     *  (caller falls back to plain DNS at the same provider). */
    private fun resolveDoh(query: ByteArray): ByteArray? {
        val url = DnsProviders.active.doh ?: return null
        return try {
            val conn = URL(url).openConnection() as HttpsURLConnection
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

    /** Best-effort: which app's package made this DNS query (needs API 29+).
     *  IPv4 and IPv6 — modern phones send most DNS over IPv6, so v1.8 handles
     *  both (before, every IPv6 lookup was filed under "system / unknown").
     *  Returns AppStats.UNKNOWN when it can't be attributed. */
    private fun ownerOf(buffer: ByteArray, q: DnsPacket.Query): String {
        val cm = connectivity
        if (cm == null || Build.VERSION.SDK_INT < 29) return AppStats.UNKNOWN
        return try {
            val src: InetAddress
            val dst: InetAddress
            if (q.ipVersion == 6) {
                src = InetAddress.getByAddress(buffer.copyOfRange(8, 24))
                dst = InetAddress.getByAddress(buffer.copyOfRange(24, 40))
            } else {
                src = InetAddress.getByAddress(buffer.copyOfRange(12, 16))
                dst = InetAddress.getByAddress(buffer.copyOfRange(16, 20))
            }
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

    /** Swap in a freshly downloaded filter without dropping a single lookup:
     *  load off-thread (≈3 MB), then replace the reference in one write. */
    private fun reloadFilterAsync() {
        Thread({
            try {
                val f = BloomFilter.loadCurrent(this)
                filter = f
                Stalkerware.load(this)
                Threats.load(this)
                ScamShield.load(this)
                Log.i(TAG, "filter reloaded live; items=${f.items}")
            } catch (e: Exception) { Log.w(TAG, "filter reload failed, keeping current: $e") }
        }, "guardian-filter-reload").start()
    }

    /** While paused, say so — and offer "Resume now" — so the break is never silent. */
    private fun showPausedNotification() {
        try {
            val mgr = getSystemService(NotificationManager::class.java)
            ensureChannel(mgr)
            val resume = Intent(this, GuardianVpnService::class.java).setAction(ACTION_START)
            val resumePi = if (Build.VERSION.SDK_INT >= 26)
                PendingIntent.getForegroundService(this, 3, resume, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            else PendingIntent.getService(this, 3, resume, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                .format(java.util.Date(pausedUntil(this)))
            mgr.notify(PAUSED_NOTIF_ID, Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Noxa is paused")
                .setContentText("Protection turns back on by itself at about $at")
                .setSmallIcon(android.R.drawable.ic_media_pause)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                    Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .addAction(Notification.Action.Builder(null, "Resume now", resumePi).build())
                .setOngoing(true)
                .build())
        } catch (e: Exception) { Log.w(TAG, "paused notification failed: $e") }
    }

    private fun stopVpn() {
        saveStats()
        AppStats.save(this)
        DailyStats.save(this)
        running.set(false)
        isRunning.set(false)
        NoxaWidget.refreshAll(this)
        NoxaTileService.refresh(this)
        try { worker?.interrupt() } catch (_: Exception) {}
        stopWorkers()
        cache.clear()
        LiveLog.clear()        // the live feed is memory-only and ends with the session
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

    override fun onCreate() {
        super.onCreate(); live = java.lang.ref.WeakReference(this)
        NetworkWatcher.start(this)        // v1.12: mobile-data blocking + public Wi-Fi guard
    }

    override fun onDestroy() { live = null; stopVpn(); super.onDestroy() }

    // --- notification (foreground service requirement) -----------------------
    /** v1.7: the persistent notification carries today's count. Same ID +
     *  onlyAlertOnce = a silent in-place update, no buzz, no new entry. */
    private fun refreshNotification() {
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) }
        catch (_: Exception) {}
    }

    private fun ensureChannel(mgr: NotificationManager) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Protection",
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        ensureChannel(getSystemService(NotificationManager::class.java))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        // v1.5: a site broken by blocking? One tap pauses for 5 minutes and
        // protection comes back by itself — no digging for the switch.
        val pause = PendingIntent.getService(
            this, 4, Intent(this, GuardianVpnService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val today = DailyStats.today()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Noxa is protecting you")
            .setContentText(if (today == 0L) "Blocking trackers and ads"
                            else "%,d tracking attempts blocked today".format(today))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Pause 5 min", pause).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
