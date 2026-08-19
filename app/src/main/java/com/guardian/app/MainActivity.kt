package com.guardian.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.TextView

/**
 * The entire user interface for Phase 1: one switch.
 *
 * Tap it on -> Android asks permission to create the local VPN -> Guardian
 * starts protecting. No settings, no configuration, no jargon. A live counter
 * shows "X tracking attempts blocked" so the protection is visible, not silent.
 */
class MainActivity : Activity() {

    private lateinit var toggle: Switch
    private lateinit var status: TextView
    private lateinit var counter: TextView
    private val ui = Handler(Looper.getMainLooper())

    private val toggleListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        if (checked) requestStart() else stopService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        toggle = findViewById(R.id.toggle)
        status = findViewById(R.id.status)
        counter = findViewById(R.id.counter)

        toggle.setOnCheckedChangeListener(toggleListener)

        // One tap deeper: the Phase 2 per-app dashboard. The default screen stays
        // just the switch — this button is the only added surface.
        findViewById<Button>(R.id.details).setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }
        tick()
        maybeShowIntro()
    }

    /** Step 6 — a one-time, plain-language "what is this" on first launch. */
    private fun maybeShowIntro() {
        val prefs = getSharedPreferences("guardian_ui", MODE_PRIVATE)
        if (prefs.getBoolean("seen_intro", false)) return
        AlertDialog.Builder(this)
            .setTitle("Welcome to Guardian")
            .setMessage(
                "Guardian blocks ads, trackers and malware across your WHOLE phone — " +
                "every app, not just the browser.\n\n" +
                "• Flip the switch on to start. That's the whole setup.\n" +
                "• \"Per-app details\" shows who's tracking you, and lets you block any app.\n" +
                "• On public Wi-Fi, \"Hide my IP\" adds a private tunnel.\n\n" +
                "Free forever. Nothing leaves your device — you can check the code yourself."
            )
            .setPositiveButton("Get started") { _, _ ->
                prefs.edit().putBoolean("seen_intro", true).apply()
            }
            .setCancelable(false)
            .show()
    }

    /** Whenever the screen comes to the front, show the switch's REAL state —
     *  it may be running because Always-on VPN started it without the app open. */
    override fun onResume() {
        super.onResume()
        val on = GuardianVpnService.isRunning.get()
        toggle.setOnCheckedChangeListener(null)      // don't fire the listener while syncing
        toggle.isChecked = on
        toggle.setOnCheckedChangeListener(toggleListener)
        status.text = getString(if (on) R.string.on else R.string.off)
    }

    private fun requestStart() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) startActivityForResult(prepare, 1) // asks the user
        else onActivityResult(1, RESULT_OK, null)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) {
            startService(Intent(this, GuardianVpnService::class.java)
                .setAction(GuardianVpnService.ACTION_START))
            status.text = getString(R.string.on)
        } else {
            toggle.isChecked = false
        }
    }

    private fun stopService() {
        startService(Intent(this, GuardianVpnService::class.java)
            .setAction(GuardianVpnService.ACTION_STOP))
        status.text = getString(R.string.off)
    }

    /** Refresh the live "blocked" count once a second while the screen is open. */
    private fun tick() {
        // Show the larger of the live counter and the saved total, so the number
        // stays continuous even if the service was killed and restarted.
        val live = GuardianVpnService.blockedCount.get()
        val saved = getSharedPreferences(GuardianVpnService.PREFS, MODE_PRIVATE)
            .getLong(GuardianVpnService.KEY_BLOCKED, 0L)
        counter.text = getString(R.string.blocked_count, maxOf(live, saved))
        ui.postDelayed({ tick() }, 1000)
    }
}
