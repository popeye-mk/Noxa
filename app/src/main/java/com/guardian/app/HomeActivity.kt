package com.guardian.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * v1.14 "Report to my home server" screen (More settings → one tap deeper).
 * Plain words, including for the children of the house: the home server sees
 * counts and warnings, never the sites anyone visits. See HomeReport.
 */
class HomeActivity : Activity() {

    private lateinit var address: EditText
    private lateinit var key: EditText
    private lateinit var name: EditText
    private lateinit var onSwitch: Switch
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v1.14 parent lock: the address, key and switch are a parent's business
        ParentLock.gate(this, "change the home-server settings", onCancel = { finish() }) { setContentView(buildUi()) }
    }

    override fun onResume() { super.onResume(); showStatus() }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_main)
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        col.addView(title("Report to my home server"))
        col.addView(muted(
            "Off unless you switch it on. When on, Noxa sends a short report once an hour to a server in YOUR " +
            "house — an address on your own Wi-Fi — so one screen at home can show every phone, tablet and TV: " +
            "protection on or off, how much was blocked, and whether any app reached a scam or spyware server.\n\n" +
            "Noxa never sends the sites you visit — it doesn't even keep them. Nothing goes to the internet: only " +
            "on your home Wi-Fi, never on mobile data, never in tunnel mode, only to a home address.\n\n" +
            "If a parent set this up: the home server sees counts and warnings, never what you looked at."
        ).apply { setPadding(0, dp(6), 0, dp(18)) })

        col.addView(label("Home server address"))
        address = field("192.168.1.20  (or 192.168.1.20:8790)", HomeReport.address(this), InputType.TYPE_CLASS_TEXT)
        col.addView(address)
        col.addView(label("Key (the home server shows it when you set it up)"))
        key = field("key", HomeReport.key(this), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        col.addView(key)
        col.addView(label("This device's name, as the home server should show it"))
        name = field(HomeReport.defaultName(this), getSharedPreferences(HomeReport.PREFS, MODE_PRIVATE)
            .getString(HomeReport.KEY_NAME, "") ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        col.addView(name)

        col.addView(Button(this).apply {
            text = "Save"
            setBackgroundResource(R.drawable.btn_primary); setTextColor(Color.WHITE)
            setOnClickListener { save() }
        })

        onSwitch = Switch(this).apply {
            text = "  Report to my home server (once an hour)"
            setTextColor(Color.WHITE); textSize = 15f
            isChecked = HomeReport.isOn(this@HomeActivity)
            setPadding(0, dp(18), 0, 0)
            setOnCheckedChangeListener { _, on ->
                if (on && !save()) { isChecked = false; return@setOnCheckedChangeListener }
                HomeReport.setOn(this@HomeActivity, on)
                if (on) sendNow() else showStatus()
            }
        }
        col.addView(onSwitch)

        col.addView(Button(this).apply {
            text = "Send a test report now"
            setBackgroundResource(R.drawable.btn_dark); setTextColor(Color.WHITE)
            setOnClickListener { if (save()) sendNow() }
        })
        status = muted("").apply { setPadding(0, dp(10), 0, 0) }
        col.addView(status)
        col.addView(muted("The exact content of a report, and a tiny example server anyone can run, are in " +
            "docs/HOME-SERVER.md on Noxa's GitHub page.").apply { textSize = 12f; setPadding(0, dp(18), 0, 0) })
        return ScrollView(this).apply { addView(col) }
    }

    /** Checks and saves the three fields; says what's wrong. */
    private fun save(): Boolean {
        val a = address.text.toString().trim()
        val k = key.text.toString().trim()
        if (HomeReport.parseAddress(a) == null) {
            Toast.makeText(this, "The address must be a home address like 192.168.1.20 (a port is optional: 192.168.1.20:8790).",
                Toast.LENGTH_LONG).show()
            return false
        }
        if (!HomeReport.keyOk(k)) {
            Toast.makeText(this, "Type the key the home server gave you (8 to 200 plain characters).", Toast.LENGTH_LONG).show()
            return false
        }
        HomeReport.save(this, a, k, name.text.toString())
        Toast.makeText(this, "Saved — only on this device.", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun sendNow() {
        status.text = "Sending…"
        Thread {
            val result = try { HomeReport.send(this) } catch (e: Exception) { "Couldn't send: ${e.message}" }
            runOnUiThread { status.text = result; showStatus(result) }
        }.start()
    }

    private fun showStatus(now: String? = null) {
        val last = HomeReport.lastSent(this)
        val when_ = if (last == 0L) "never" else
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(last))
        val result = now ?: HomeReport.lastResult(this)
        status.text = "Last report that got through: $when_." + (if (result.isNotBlank()) "\n$result" else "")
    }

    private fun title(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 24f
        setTypeface(typeface, Typeface.BOLD)
    }
    private fun label(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.parseColor("#C9D3E3")); textSize = 13f
        setPadding(0, dp(12), 0, dp(4))
    }
    private fun muted(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.parseColor("#8AA0B2")); textSize = 13f
    }
    private fun field(hint: String, value: String, type: Int) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(Color.parseColor("#55627A"))
        setTextColor(Color.WHITE); textSize = 15f
        setBackgroundResource(R.drawable.field_dark)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        inputType = type
        isSingleLine = true
        setText(value)
    }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
