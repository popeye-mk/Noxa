package com.guardian.app

import android.content.Context
import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.StringReader

/**
 * Brings the WireGuard tunnel up/down using the official userspace backend
 * (GoBackend). One tunnel, mode-switched against Guardian's blocking VpnService
 * (Android allows only one VPN at a time), so turning this ON pauses blocking.
 *
 * setState() does real network I/O (the handshake) — always call up()/down()
 * OFF the main thread.
 */
object TunnelController {

    private const val TAG = "Guardian"
    private var backend: Backend? = null

    @Volatile var isUp = false
        private set

    private val tunnel = object : Tunnel {
        override fun getName(): String = "guardian"
        override fun onStateChange(newState: Tunnel.State) {
            isUp = newState == Tunnel.State.UP
            Log.i(TAG, "tunnel state -> $newState")
        }
    }

    private fun backend(ctx: Context): Backend =
        backend ?: GoBackend(ctx.applicationContext).also { backend = it }

    /** Parse the config and bring the tunnel UP. When [blockTrackers] is true we
     *  override the config's DNS with a tracker-blocking resolver (the combo:
     *  IP hidden AND trackers blocked). With the Family filter or Safe search on,
     *  a family resolver is used whatever [blockTrackers] says. Throws on failure
     *  so the UI can report it. */
    fun up(ctx: Context, configText: String, blockTrackers: Boolean) {
        val dns = TunnelDns.choose(blockTrackers, familyWanted(ctx))
        val text = if (dns != null) TunnelDns.withDns(configText, dns) else configText
        val cfg = Config.parse(BufferedReader(StringReader(text)))
        backend(ctx).setState(tunnel, Tunnel.State.UP, cfg)
        isUp = true
    }

    /** The Family filter or Safe search is switched on (read from the saved choice:
     *  Noxa's own filter, which normally holds it, is stopped in tunnel mode). */
    fun familyWanted(ctx: Context): Boolean =
        ctx.getSharedPreferences(FamilyFilter.PREFS, Context.MODE_PRIVATE).getBoolean("on", false) ||
            ctx.getSharedPreferences(SafeSearch.PREFS, Context.MODE_PRIVATE).getBoolean("on", false)

    /** Bring the tunnel DOWN. Safe to call even if it was never up. */
    fun down(ctx: Context) {
        try {
            backend(ctx).setState(tunnel, Tunnel.State.DOWN, null)
        } catch (e: Exception) {
            Log.w(TAG, "tunnel down: $e")
        }
        isUp = false
    }
}

/**
 * Which DNS the tunnel uses. Pure (no Android, no WireGuard), unit-tested.
 *
 * Noxa's own filter can't run while the tunnel holds the one VPN slot, so the
 * blocking is done by the resolver the tunnel's DNS points at.
 */
object TunnelDns {
    // HaGeZi DNS ("Full Protection", Nuremberg + Falkenstein) — blocks ads, trackers
    // and threats at the resolver with HaGeZi Multi Pro + TIF; free, EU, no
    // per-query logs. v1.15.1: was AdGuard DNS, which caught 79% of the ad-block
    // test's hosts vs 95% for HaGeZi Pro (Noxa's own filter: 97%).
    const val BLOCKING = "DNS = 159.69.155.94, 188.34.161.210"

    // v1.16.1: AdGuard Family DNS — adult sites blocked and safe search forced, on
    // top of ads and trackers. HaGeZi DNS blocks no adult sites, so with the
    // Family filter on, the tunnel (which the public Wi-Fi guard even switches on
    // by itself) was a way straight past it. Fewer ads caught
    // (about 79% vs 95%), but on a child's device the family filter comes first.
    const val FAMILY = "DNS = 94.140.14.15, 94.140.15.16"

    /** The DNS line to force into the config; null = keep the config's own DNS. */
    fun choose(blockTrackers: Boolean, familyOn: Boolean): String? = when {
        familyOn -> FAMILY
        blockTrackers -> BLOCKING
        else -> null
    }

    /** Force the [Interface] DNS line of a WireGuard config to [dnsLine]. */
    fun withDns(config: String, dnsLine: String): String {
        val out = StringBuilder()
        var inInterface = false
        var dnsDone = false
        for (raw in config.lines()) {
            val t = raw.trim()
            if (t.startsWith("[")) {
                if (inInterface && !dnsDone) { out.append(dnsLine).append('\n'); dnsDone = true }
                inInterface = t.substringBefore('#').trim().equals("[Interface]", ignoreCase = true)   // "[Interface] # phone"
                out.append(raw).append('\n'); continue
            }
            if (inInterface && t.startsWith("DNS", ignoreCase = true)) {
                if (!dnsDone) { out.append(dnsLine).append('\n'); dnsDone = true }
                continue   // drop the config's original DNS line
            }
            out.append(raw).append('\n')
        }
        if (inInterface && !dnsDone) out.append(dnsLine).append('\n')
        return out.toString()
    }
}
