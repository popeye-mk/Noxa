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
            if (doh) "Your lookups are hidden from the Wi-Fi/ISP (via ${DnsProviders.active.name})." +
                (DnsProviders.fallbackInfo()?.let { (f, c) -> " ${c.name} wasn't answering, so ${f.name} is used for a few minutes." } ?: "")
            else "Lookups travel unencrypted — the Wi-Fi owner and ISP can read them.",
            if (doh) null else "Turn on") { GuardianVpnService.setEncryptedDns(this, true); refresh() }

        // Battery: Android's standard check can't see OEM battery managers.
        // Xiaomi/HyperOS "No restrictions" still reports "not exempt", so the
        // row nagged even when it was set (v1.10 feedback). Accept the user's
        // word once they've confirmed it, and send OEM phones to the app's own
        // settings page, where their battery option actually lives.
        val pm = getSystemService(PowerManager::class.java)
        val ui = getSharedPreferences("guardian_ui", Context.MODE_PRIVATE)
        val systemOk = pm.isIgnoringBatteryOptimizations(packageName)
        val confirmed = ui.getBoolean(KEY_BATTERY_CONFIRMED, false)
        val proven = HealthMonitor.batteryProvenOk(this)       // v1.11: judged from real behaviour
        val battery = systemOk || confirmed || proven
        out += Item(battery, "Allowed to run in the background",
            when {
                systemOk -> "The battery manager won't kill protection."
                proven -> "No unexpected stops for 3+ days — the phone lets Noxa run."
                confirmed -> "You set Noxa to \"No restrictions\" in the phone's battery settings."
                else -> "The phone may silently stop Noxa to save battery — the #1 cause of \"it stopped\"."
            },
            if (battery) null else "Fix") { fixBattery() }

        val prefs = getSharedPreferences(GuardianVpnService.PREFS, Context.MODE_PRIVATE)
        // Ask the running protection NOW (the saved value can be stale if
        // Always-on was switched on while Noxa was already running).
        val liveAo = GuardianVpnService.liveAlwaysOn()
        if (liveAo != null) prefs.edit()
            .putBoolean(GuardianVpnService.KEY_ALWAYS_ON, liveAo.first)
            .putBoolean(GuardianVpnService.KEY_LOCKDOWN, liveAo.second).apply()
        val alwaysOn = liveAo?.first ?: prefs.getBoolean(GuardianVpnService.KEY_ALWAYS_ON, false)
        val lockdown = liveAo?.second ?: prefs.getBoolean(GuardianVpnService.KEY_LOCKDOWN, false)
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
        val danger = g.filterKeys { it.startsWith("Dangerous site") }.values.sum()
        // Spyware: only a NON-browser app reaching a spyware server is a
        // warning sign. A browser visiting a spy-app's website (your own
        // tests, an article, an ad) is shown, but doesn't turn the row amber.
        val spyHits = AppStats.appsHitting("Stalkerware")
        val appHits = spyHits.filter { (pkg, _) -> !isBrowser(pkg) }
        val spyApps = appHits.sumOf { it.second }
        val spyBrowser = spyHits.sumOf { it.second } - spyApps
        out += Item(spyApps == 0L, "Spyware servers contacted (30 days)",
            when {
                spyApps > 0 -> "$spyApps attempt(s) by an app — check which one."
                spyBrowser > 0 -> "None by apps. $spyBrowser visit(s) to spyware websites in a browser were blocked — that's normal browsing, not spyware on the phone."
                else -> "None — good sign."
            },
            if (spyHits.isEmpty()) null else "See which app") { showWhichApps("Stalkerware", "Spyware servers — last 30 days") }
        out += Item(true, "Dangerous sites blocked (30 days)",
            if (danger == 0L) "None yet." else "$danger scam/malware connection(s) stopped.")
        return out
    }

    /** v1.9.2: "See which app" used to open the app list with no hint where to
     *  look. Now it names the app(s) right away. */
    private fun showWhichApps(prefix: String, title: String) {
        val hits = AppStats.appsHitting(prefix)
        val msg = if (hits.isEmpty()) "No app found — the count may be from before the last reset."
        else hits.joinToString("\n\n") { (pkg, n) ->
            "• ${appName(pkg)} — blocked $n time(s)"
        } + "\n\nNoxa already blocked these, so nothing got through. " +
            "If you don't recognise the app, consider uninstalling it."
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .setNeutralButton("Per-app details") { _, _ -> startActivity(Intent(this, AppsActivity::class.java)) }
            .show()
    }

    private fun fixBattery() {
        val oem = android.os.Build.MANUFACTURER.lowercase()
        val custom = oem in setOf("xiaomi", "redmi", "poco", "huawei", "honor", "oppo", "realme",
            "oneplus", "vivo", "samsung", "meizu", "asus")
        android.app.AlertDialog.Builder(this)
            .setTitle("Let Noxa run in the background")
            .setMessage(if (custom)
                "On this phone: open Noxa's app settings → Battery → choose \"No restrictions\" " +
                "(or \"Unrestricted\"). Then come back and tap \"I've done it\"."
            else "Android will ask you to allow Noxa to ignore battery optimisation.")
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    if (custom) startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    else startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    Toast.makeText(this, "Open Settings → Apps → Noxa → Battery → No restrictions.", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("I've done it") { _, _ ->
                getSharedPreferences("guardian_ui", Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_BATTERY_CONFIRMED, true).apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Apps that open web links = browsers (incl. in-app browser hosts). */
    private val browsers: Set<String> by lazy {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
            packageManager.queryIntentActivities(i, android.content.pm.PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    private fun isBrowser(pkg: String) = pkg in browsers

    private fun appName(pkg: String): String = try {
        if (pkg == AppStats.UNKNOWN) "Android system / unknown app"
        else packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

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
        const val KEY_BATTERY_CONFIRMED = "battery_confirmed"

        /** Days since "YYYY-MM-DD HH:MM:SS" (UTC-ish); -1 if unparsable. */
        fun filterAgeDays(builtAt: String, now: Long = System.currentTimeMillis()): Int = try {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            f.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val t = f.parse(builtAt)!!.time
            ((now - t) / (24L * 60 * 60 * 1000)).toInt().coerceAtLeast(0)
        } catch (_: Exception) { -1 }
    }
}
