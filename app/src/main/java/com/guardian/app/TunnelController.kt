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

    // AdGuard DNS — blocks ads + trackers at the resolver. Used to get "blocking
    // WHILE tunnelling" without rebuilding the WireGuard backend: we just point
    // the tunnel's DNS here so lookups to trackers fail inside the tunnel too.
    private const val BLOCKING_DNS = "DNS = 94.140.14.14, 94.140.15.15"

    /** Parse the config and bring the tunnel UP. When [blockTrackers] is true we
     *  override the config's DNS with a tracker-blocking resolver (the combo:
     *  IP hidden AND trackers blocked). Throws on failure so the UI can report it. */
    fun up(ctx: Context, configText: String, blockTrackers: Boolean) {
        val text = if (blockTrackers) withBlockingDns(configText) else configText
        val cfg = Config.parse(BufferedReader(StringReader(text)))
        backend(ctx).setState(tunnel, Tunnel.State.UP, cfg)
        isUp = true
    }

    /** Force the [Interface] DNS line to the tracker-blocking resolver. */
    private fun withBlockingDns(config: String): String {
        val out = StringBuilder()
        var inInterface = false
        var dnsDone = false
        for (raw in config.lines()) {
            val t = raw.trim()
            if (t.startsWith("[")) {
                if (inInterface && !dnsDone) { out.append(BLOCKING_DNS).append('\n'); dnsDone = true }
                inInterface = t.equals("[Interface]", ignoreCase = true)
                out.append(raw).append('\n'); continue
            }
            if (inInterface && t.startsWith("DNS", ignoreCase = true)) {
                if (!dnsDone) { out.append(BLOCKING_DNS).append('\n'); dnsDone = true }
                continue   // drop the config's original DNS line
            }
            out.append(raw).append('\n')
        }
        if (inInterface && !dnsDone) out.append(BLOCKING_DNS).append('\n')
        return out.toString()
    }

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
