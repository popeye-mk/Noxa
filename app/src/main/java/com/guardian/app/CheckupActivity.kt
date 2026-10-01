package com.guardian.app

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * v1.8 "Protection check-up": one screen that says what keeps you protected,
 * what's missing, and fixes each with one tap. The things that silently
 * weaken protection (battery managers, no Always-on, notifications off, a
 * stale list) are exactly what non-technical users never find on their own.
 */
class CheckupActivity : Activity() {

    private class Item(val ok: Boolean, val title: String, val detail: String,
                       val fixLabel: String? = null, val fix: (() -> Unit)? = null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStats.load(this)
        setContentView(buildUi())
    }

    override fun onResume() { super.onResume(); setContentView(buildUi()) }

    private fun items(): List<Item> {
        val out = ArrayList<Item>()
        val on = GuardianVpnService.isRunning.get() || TunnelController.isUp
        out += Item(on, "Protection is on",
            if (on) "Trackers, scams and spyware servers are being blocked."
            else "Nothing is blocked while it's off.",
            if (on) null else "Turn on") { startActivity(Intent(this, MainActivity::class.java)); finish() }

        val doh = GuardianVpnService.encryptedDns.get()
        out += Item(doh, "Encrypted DNS",
            if (doh) "Your lookups are hidden from the Wi-Fi/ISP (via ${DnsProviders.current.name})."
            else "Lookups travel unencrypted — the Wi-Fi owner and ISP can read them.",
            if (doh) null else "Turn on") { GuardianVpnService.setEncryptedDns(this, true); refresh() }

        val pm = getSystemService(PowerManager::class.java)
        val battery = pm.isIgnoringBatteryOptimizations(packageName)
        out += Item(battery, "Allowed to run in the background",
            if (battery) "The battery manager won't kill protection."
            else "The phone may silently stop Noxa to save battery — the #1 cause of \"it stopped\".",
            if (battery) null else "Fix") {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { Toast.makeText(this, "Open Settings → Battery → Noxa → Unrestricted.", Toast.LENGTH_LONG).show() }
        }

        val prefs = getSharedPreferences(GuardianVpnService.PREFS, Context.MODE_PRIVATE)
        val alwaysOn = prefs.getBoolean(GuardianVpnService.KEY_ALWAYS_ON, false)
        val lockdown = prefs.getBoolean(GuardianVpnService.KEY_LOCKDOWN, false)
        out += Item(alwaysOn, "Always-on VPN",
            when {
                alwaysOn && lockdown -> "On, with \"block connections without VPN\": nothing leaks, even if Noxa restarts."
                alwaysOn -> "On: Android restarts Noxa itself after a reboot or kill."
                else -> "Off: after a reboot or a kill there's a gap until the watchdog (≤15 min) restarts protection."
            },
            if (alwaysOn) null else "Set up") {
            Toast.makeText(this, "Tap the gear ⚙ next to Noxa, then turn on \"Always-on VPN\".", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } catch (_: Exception) {}
        }

        val notifs = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        out += Item(notifs, "Alerts can reach you",
            if (notifs) "Spyware and scam warnings will show."
            else "Notifications are off: you'd never see a spyware or scam warning (and some phones kill silent services).",
            if (notifs) null else "Allow") {
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } catch (_: Exception) {}
        }

        val built = FilterUpdater.currentBuiltAt(this)
        val ageDays = filterAgeDays(built)
        val fresh = ageDays in 0..14
        out += Item(fresh, "Blocklist is fresh",
            if (ageDays < 0) "Built: unknown." else "Built $ageDays day(s) ago ($built). Refreshed weekly by itself.",
            if (fresh) null else "Update now") {
            Toast.makeText(this, "Checking…", Toast.LENGTH_SHORT).show()
            Thread {
                val msg = FilterUpdater.checkAndUpdate(this)
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); refresh() }
            }.start()
        }

        val g = AppStats.globalCompanyCounts()
        val spy = g.filterKeys { it.startsWith("Stalkerware") }.values.sum()
        val danger = g.filterKeys { it.startsWith("Dangerous site") }.values.sum()
        out += Item(spy == 0L, "Spyware servers contacted (30 days)",
            if (spy == 0L) "None — good sign." else "$spy attempt(s) blocked. Check the alerts and Per-app details.",
            if (spy == 0L) null else "See which app") { startActivity(Intent(this, AppsActivity::class.java)) }
        out += Item(true, "Dangerous sites blocked (30 days)",
            if (danger == 0L) "None yet." else "$danger scam/malware connection(s) stopped.")
        return out
    }

    private fun refresh() = setContentView(buildUi())

    private fun buildUi(): View {
        val list = items()
        val todo = list.count { !it.ok }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_main)
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "🛡  Protection check-up"
            setTextColor(Color.WHITE); textSize = 24f; setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = if (todo == 0) "Everything that keeps you protected is in place."
                   else "$todo thing(s) could make protection stronger — each has a one-tap fix."
            setTextColor(Color.parseColor(if (todo == 0) "#4CC38A" else "#E5A84D")); textSize = 14f
            setPadding(0, dp(6), 0, dp(14))
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (it in list) col.addView(row(it))
        root.addView(ScrollView(this).apply { addView(col) })
        return root
    }

    private fun row(it: Item): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(10), dp(8), dp(10))
        isFocusable = true
        setBackgroundResource(R.drawable.row_focus)
        addView(LinearLayout(this@CheckupActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@CheckupActivity).apply {
                text = (if (it.ok) "✓  " else "!  ") + it.title
                setTextColor(Color.parseColor(if (it.ok) "#4CC38A" else "#E5A84D")); textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@CheckupActivity).apply {
                text = it.detail
                setTextColor(Color.parseColor("#C9D3E3")); textSize = 13f
            })
        })
        if (it.fixLabel != null && it.fix != null) {
            addView(Button(this@CheckupActivity).apply {
                text = it.fixLabel; isAllCaps = false
                setBackgroundResource(R.drawable.btn_primary); setTextColor(Color.WHITE)
                setOnClickListener { _ -> it.fix.invoke() }
            })
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Days since "YYYY-MM-DD HH:MM:SS" (UTC-ish); -1 if unparsable. */
        fun filterAgeDays(builtAt: String, now: Long = System.currentTimeMillis()): Int = try {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            f.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val t = f.parse(builtAt)!!.time
            ((now - t) / (24L * 60 * 60 * 1000)).toInt().coerceAtLeast(0)
        } catch (_: Exception) { -1 }
    }
}
