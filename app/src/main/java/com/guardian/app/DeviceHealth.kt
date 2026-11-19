package com.guardian.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings

/**
 * v1.16 device hygiene (security review of 19 Nov 2026). Things outside Noxa
 * that weaken the device or switch Noxa's blocking off, read without any
 * permission and never sent anywhere except, as plain yes/no facts, to the
 * user's own home server:
 *
 *  - Private DNS set to a provider: Android then sends every lookup straight to
 *    that provider (DNS over TLS), past Noxa — nothing is blocked or counted
 *    while the app still says "Protected".
 *  - an old security patch (cheap tablets and TVs often never get one)
 *  - certificates the user (or an app) added: they let someone read encrypted
 *    traffic — a classic stalkerware / "free VPN" trick
 *  - USB debugging left on, a system-wide proxy
 */
object DeviceHealth {
    private const val DAY = 24L * 60 * 60 * 1000

    class State(val privateDnsHost: String?, val patch: String, val userCerts: Int,
                val usbDebugging: Boolean, val proxy: Boolean)

    /** Pure, unit-tested: days since a "2025-03-01" style patch date; -1 if unreadable. */
    fun patchAgeDays(patch: String, nowMs: Long): Int {
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").matchEntire(patch.trim()) ?: return -1
        return try {
            val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            c.clear()
            c.set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt())
            val days = (nowMs - c.timeInMillis) / DAY
            if (days < 0) 0 else days.toInt()
        } catch (_: Exception) { -1 }
    }

    /** Pure, unit-tested: older than a year (unknown is not "old"). */
    fun patchOld(ageDays: Int): Boolean = ageDays > 365

    /** Pure, unit-tested: certificates added on the device (the system's own start with "system:"). */
    fun countUserCerts(aliases: Iterable<String>): Int = aliases.count { it.startsWith("user:") }

    /** Pure, unit-tested: Private DNS only bypasses Noxa when a provider name is set (strict mode). */
    fun bypassesNoxa(privateDnsHost: String?): Boolean = !privateDnsHost.isNullOrBlank()

    fun read(ctx: Context): State = State(
        privateDnsHost = privateDnsHost(ctx),
        patch = try { Build.VERSION.SECURITY_PATCH.orEmpty() } catch (_: Exception) { "" },
        userCerts = userCerts(),
        usbDebugging = try { Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }
                       catch (_: Exception) { false },
        proxy = try { ctx.getSystemService(ConnectivityManager::class.java)?.defaultProxy != null }
                catch (_: Exception) { false })

    /** The Private DNS provider name of a real (non-VPN) network, or null. Android 9+. */
    fun privateDnsHost(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 28) return null
        return try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val host = cm.getLinkProperties(n)?.privateDnsServerName
                if (!host.isNullOrBlank()) return host
            }
            null
        } catch (_: Exception) { null }
    }

    private fun userCerts(): Int = try {
        val ks = java.security.KeyStore.getInstance("AndroidCAStore")
        ks.load(null)
        countUserCerts(java.util.Collections.list(ks.aliases()))
    } catch (_: Exception) { 0 }
}
