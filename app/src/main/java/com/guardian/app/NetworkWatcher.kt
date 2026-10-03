package com.guardian.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiInfo
import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.12: watches the phone's REAL networks (Wi-Fi, mobile), underneath the
 * VPN. Two jobs:
 *
 *  1. Mobile data, per app: [onMobileData] tells the DNS loop when the phone
 *     is on mobile data only, so apps the user chose can be kept offline there.
 *
 *  2. Public Wi-Fi guard: on an OPEN Wi-Fi (no password) or one that showed a
 *     LOGIN PAGE (hotel, airport, café), turn the user's WireGuard tunnel on —
 *     after the login is done — and off again when they leave. Detected
 *     without location permission (Noxa has none): Android exposes a network's
 *     security type and its captive portal, not its name.
 *
 * Only a tunnel this guard turned on is ever turned off by it.
 */
object NetworkWatcher {
    private const val TAG = "Guardian"
    private const val PREFS = TunnelActivity.PREFS
    const val KEY_AUTO = "auto_public_wifi"
    private const val KEY_ENGAGED = "auto_engaged"
    private const val KEY_WANTED_BLOCKER = "auto_wanted_blocker"
    private const val CHANNEL_ID = "guardian_help"
    private const val NOTIF_ID = 450

    private val wifi = ConcurrentHashMap<Network, NetworkCapabilities>()
    private val cellular = ConcurrentHashMap.newKeySet<Network>()
    private val hadLoginPage = ConcurrentHashMap.newKeySet<Network>()
    @Volatile private var registered = false
    @Volatile private var publicNet: Network? = null

    /** True when the phone is on mobile data only (no Wi-Fi / Ethernet up). */
    val onMobileData: Boolean get() = wifi.isEmpty() && cellular.isNotEmpty()

    /** v1.13: a Wi-Fi still behind a login page (or not yet working). Its
     *  login page often answers with private addresses, so the router attack
     *  shield stands aside until the network is validated. */
    val loginPending: Boolean get() = wifi.values.any {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) ||
            !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun start(ctx: Context) {
        if (registered) return
        val app = ctx.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            val wifiReq = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build()   // NOT_VPN by default
            cm.registerNetworkCallback(wifiReq, object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                    wifi[n] = caps
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) hadLoginPage += n
                    evaluate(app, n, caps)
                }
                override fun onLost(n: Network) {
                    wifi.remove(n); hadLoginPage -= n
                    if (n == publicNet) { publicNet = null; disengage(app) }
                }
            })
            val cellReq = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).build()
            cm.registerNetworkCallback(cellReq, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) { cellular += n }
                override fun onLost(n: Network) { cellular -= n }
            })
            registered = true
            // A tunnel we started before the process died is no longer ours to manage.
            if (!TunnelController.isUp) prefs(app).edit().putBoolean(KEY_ENGAGED, false).apply()
        } catch (e: Exception) { Log.w(TAG, "network watcher: $e") }
    }

    /** Pure, unit-tested: is this Wi-Fi risky enough to hide the IP on? */
    fun isPublic(openOrOwe: Boolean, hadLoginPage: Boolean): Boolean = openOrOwe || hadLoginPage

    private fun evaluate(ctx: Context, n: Network, caps: NetworkCapabilities) {
        val open = securityOpen(caps)
        val public = isPublic(open, n in hadLoginPage)
        // Wait until the network works (login page done) before tunnelling.
        val ready = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        if (public && ready) {
            if (publicNet != n) { publicNet = n; engage(ctx) }
        } else if (!public && n == publicNet) {
            publicNet = null; disengage(ctx)
        }
    }

    /** Android 12+: the Wi-Fi's security type (not location-protected). */
    private fun securityOpen(caps: NetworkCapabilities): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val wi = caps.transportInfo as? WifiInfo ?: return false
        return wi.currentSecurityType == WifiInfo.SECURITY_TYPE_OPEN ||
            wi.currentSecurityType == WifiInfo.SECURITY_TYPE_OWE
    }

    private fun engage(ctx: Context) {
        val p = prefs(ctx)
        val cfg = p.getString(TunnelActivity.KEY_CONFIG, "").orEmpty()
        if (!p.getBoolean(KEY_AUTO, true) || cfg.isBlank()) return     // no tunnel set up, or switched off
        if (TunnelController.isUp) return                             // already tunnelling (maybe by hand)
        if (VpnService.prepare(ctx) != null) return                   // permission needs the app
        Thread {
            try {
                val blockerWanted = GuardianVpnService.wantsProtection(ctx)
                p.edit().putBoolean(KEY_WANTED_BLOCKER, blockerWanted).apply()
                ctx.startService(Intent(ctx, GuardianVpnService::class.java).setAction(GuardianVpnService.ACTION_STOP))
                Thread.sleep(700)
                TunnelController.up(ctx, cfg, p.getBoolean(TunnelActivity.KEY_BLOCK, true))
                p.edit().putBoolean(KEY_ENGAGED, true).apply()
                note(ctx, "Public Wi-Fi: your IP is now hidden",
                    "This Wi-Fi has no password or a login page, so Noxa turned on your private tunnel. " +
                    "It turns off by itself when you leave.")
            } catch (e: Exception) {
                Log.w(TAG, "auto tunnel failed: $e")
                restoreBlocker(ctx)
            }
        }.start()
    }

    private fun disengage(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_ENGAGED, false)) return                 // not ours: leave it alone
        Thread {
            TunnelController.down(ctx)
            p.edit().putBoolean(KEY_ENGAGED, false).apply()
            restoreBlocker(ctx)
            try { ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) } catch (_: Exception) {}
        }.start()
    }

    /** The user switched the tunnel by hand: the guard stops managing it. */
    fun userTookOver(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_ENGAGED, false).apply()
    }

    private fun restoreBlocker(ctx: Context) {
        if (!prefs(ctx).getBoolean(KEY_WANTED_BLOCKER, true)) return
        val svc = Intent(ctx, GuardianVpnService::class.java).setAction(GuardianVpnService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc) else ctx.startService(svc)
        } catch (e: Exception) { Log.w(TAG, "restore blocker: $e") }
    }

    private fun note(ctx: Context, title: String, text: String) {
        try {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Help & tips", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(ctx, NOTIF_ID, Intent(ctx, TunnelActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)
            mgr.notify(NOTIF_ID, Notification.Builder(ctx, CHANNEL_ID)
                .setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (_: Exception) {}
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
