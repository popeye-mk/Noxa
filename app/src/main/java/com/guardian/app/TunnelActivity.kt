package com.guardian.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.StringReader

/**
 * "Hide my IP" — the optional tunnel screen. Two clearly-labelled paths, neither
 * in the default face (this is one tap deeper, experimental):
 *
 *   A. Tor (free, easiest)  — hands off to Orbot. No account, no config.
 *   B. VPN provider (faster) — paste/scan a WireGuard config. (QR scan next.)
 *
 * Noxa runs no servers; both paths use infrastructure the user trusts.
 * This increment wires the Tor hand-off and keeps saving the WireGuard config;
 * actually establishing the WireGuard tunnel comes in a later increment.
 */
class TunnelActivity : Activity() {

    private lateinit var input: EditText
    private lateinit var wgSwitch: Switch
    private lateinit var wgStatus: TextView

    companion object {
        const val PREFS = "guardian_tunnel"
        const val KEY_CONFIG = "wg_config"
        const val KEY_BLOCK = "block_in_tunnel"
        const val ORBOT_PKG = "org.torproject.android"
        private const val REQ_VPN = 7
        private const val REQ_IMPORT = 8
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_main)
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        root.addView(title("Hide my IP"))
        root.addView(muted(
            "Use this on untrusted networks (public Wi-Fi) to hide your real IP. " +
            "You don't need it at home. Noxa runs no servers — both options " +
            "below route through something you trust."
        ).apply { setPadding(0, dp(6), 0, dp(20)) })

        // ---- Path A: Tor (free, easiest) ------------------------------------
        root.addView(title("Free & easy — Tor").apply { textSize = 17f })
        root.addView(muted(
            "No account, no payment, nothing to set up. A bit slower. Uses the free " +
            "Tor app (Orbot): tap below, turn Tor on there, and your IP is hidden."
        ).apply { setPadding(0, dp(4), 0, dp(10)) })
        root.addView(Button(this).apply {
            text = "Use Tor (via Orbot)"
            setBackgroundResource(R.drawable.btn_dark)
            setTextColor(Color.WHITE)
            setOnClickListener { useTor() }
        })

        root.addView(divider())

        // ---- Path B: WireGuard provider (faster) ----------------------------
        root.addView(title("Faster — a VPN provider").apply { textSize = 17f })
        root.addView(muted(
            "Faster than Tor. Works with ANY WireGuard provider you trust — " +
            "Proton (free tier), Mullvad, IVPN — or your own server. Scanning a QR " +
            "code is coming soon; for now, paste the config here and save it. " +
            "Turning it on arrives in a later update."
        ).apply { setPadding(0, dp(4), 0, dp(10)) })

        input = EditText(this).apply {
            hint = "[Interface]\nPrivateKey = ...\nAddress = ...\n[Peer]\nPublicKey = ...\nEndpoint = host:51820\nAllowedIPs = 0.0.0.0/0"
            setHintTextColor(Color.parseColor("#55627A"))
            setTextColor(Color.WHITE); textSize = 13f
            setBackgroundResource(R.drawable.field_dark)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            gravity = Gravity.TOP
            minLines = 7
            isSingleLine = false
            setText(getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CONFIG, ""))
        }
        root.addView(input)
        root.addView(Button(this).apply {
            text = "Import config from file (.conf)"
            setBackgroundResource(R.drawable.btn_dark)
            setTextColor(Color.WHITE)
            setOnClickListener { launchImport() }
        })
        root.addView(Button(this).apply {
            text = "Check & save config"
            setBackgroundResource(R.drawable.btn_primary)
            setTextColor(Color.WHITE)
            setOnClickListener { saveConfig() }
        })
        root.addView(muted("Saved only on this phone — nothing is sent anywhere.").apply {
            textSize = 12f; setPadding(0, dp(12), 0, 0)
        })

        // The combo: block ads/trackers while the tunnel is on (default ON).
        root.addView(Switch(this).apply {
            text = "  Block ads & trackers in the tunnel"
            setTextColor(Color.WHITE); textSize = 15f
            isChecked = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BLOCK, true)
            setPadding(0, dp(18), 0, 0)
            setOnCheckedChangeListener { _, on ->
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BLOCK, on).apply()
            }
        })
        root.addView(muted("Routes the tunnel's DNS through a tracker-blocking resolver, so you get " +
            "IP-hidden AND blocking together. Turn off only if a provider's DNS misbehaves.")
            .apply { textSize = 12f; setPadding(0, dp(4), 0, dp(10)) })

        // The on/off switch for the WireGuard tunnel (the real "Hide my IP").
        wgStatus = muted(if (TunnelController.isUp) "Tunnel ON." else "Tunnel is off.")
            .apply { setPadding(0, dp(8), 0, dp(6)) }
        root.addView(wgStatus)
        wgSwitch = Switch(this).apply {
            text = "  Hide my IP (turn tunnel on)"
            setTextColor(Color.WHITE); textSize = 16f
            isChecked = TunnelController.isUp
            setOnCheckedChangeListener { _, on -> if (on) turnTunnelOn() else turnTunnelOff() }
        }
        root.addView(wgSwitch)

        return ScrollView(this).apply { addView(root) }
    }

    private fun useTor() {
        val launch = packageManager.getLaunchIntentForPackage(ORBOT_PKG)
        if (launch != null) {
            Toast.makeText(this, "Opening Orbot — turn Tor ON there to hide your IP.",
                Toast.LENGTH_LONG).show()
            startActivity(launch)
        } else {
            Toast.makeText(this, "Get Orbot (the free Tor app), then come back.",
                Toast.LENGTH_LONG).show()
            val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$ORBOT_PKG"))
            try {
                startActivity(market)
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$ORBOT_PKG")))
            }
        }
    }

    private fun saveConfig() {
        val text = input.text.toString().trim()
        // Validate with the real WireGuard parser before saving, so the user gets
        // told immediately if the config is malformed (not later when connecting).
        if (text.isNotEmpty()) {
            try {
                Config.parse(BufferedReader(StringReader(text)))
            } catch (e: Exception) {
                Toast.makeText(this, "That isn't a valid WireGuard config:\n${e.message}",
                    Toast.LENGTH_LONG).show()
                return
            }
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CONFIG, text).apply()
        Toast.makeText(this, if (text.isEmpty()) "Cleared" else "✓ Valid config saved on this phone",
            Toast.LENGTH_SHORT).show()
    }

    private fun turnTunnelOn() {
        val cfg = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CONFIG, "").orEmpty()
        if (cfg.isBlank()) {
            Toast.makeText(this, "Paste & save a WireGuard config first.", Toast.LENGTH_SHORT).show()
            setSwitch(false); return
        }
        // The app already has VPN consent from Guardian's blocker, so prepare()
        // is usually null; handle the prompt just in case it was revoked.
        val prep = VpnService.prepare(this)
        if (prep != null) { startActivityForResult(prep, REQ_VPN); return }
        // Only one VPN at a time — stop Guardian's blocking VPN to free the slot.
        startService(Intent(this, GuardianVpnService::class.java).setAction(GuardianVpnService.ACTION_STOP))
        val block = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BLOCK, true)
        wgStatus.text = "Connecting…"
        Thread {
            try {
                TunnelController.up(this, cfg, block)
                runOnUiThread {
                    wgStatus.text = if (block) "Tunnel ON — IP hidden + trackers blocked."
                                    else "Tunnel ON — IP hidden."
                }
            } catch (e: Exception) {
                Log.w("Guardian", "tunnel up failed: $e")
                runOnUiThread {
                    wgStatus.text = "Couldn't connect: ${e.message}"
                    setSwitch(false)
                }
            }
        }.start()
    }

    private fun turnTunnelOff() {
        wgStatus.text = "Turning off…"
        Thread {
            TunnelController.down(this)
            runOnUiThread { wgStatus.text = "Tunnel is off." }
        }.start()
    }

    /** Set the switch state WITHOUT firing the listener (avoids recursion). */
    private fun setSwitch(on: Boolean) {
        wgSwitch.setOnCheckedChangeListener(null)
        wgSwitch.isChecked = on
        wgSwitch.setOnCheckedChangeListener { _, o -> if (o) turnTunnelOn() else turnTunnelOff() }
    }

    /** Step 7 — import a WireGuard .conf via the system file picker (no camera,
     *  no new dependency). Works great with providers that give a file (Proton). */
    private fun launchImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"                    // .conf can be text/plain or octet-stream
        }
        try { startActivityForResult(i, REQ_IMPORT) }
        catch (e: Exception) { Toast.makeText(this, "No file picker available.", Toast.LENGTH_SHORT).show() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) turnTunnelOn() else setSwitch(false)
        } else if (requestCode == REQ_IMPORT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (!text.isNullOrBlank()) { input.setText(text); saveConfig() }  // validates + saves
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't read that file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // --- tiny view helpers ---------------------------------------------------
    private fun title(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 24f
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun muted(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.parseColor("#8AA0B2")); textSize = 13f
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            .apply { topMargin = dp(22); bottomMargin = dp(22) }
        setBackgroundColor(Color.parseColor("#222B36"))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
