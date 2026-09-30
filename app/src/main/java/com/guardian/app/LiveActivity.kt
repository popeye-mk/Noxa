package com.guardian.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.5 "Live" — watch every app's lookups as they happen, newest on top.
 *
 * Red = blocked, green = allowed. Tap any line to fix it on the spot:
 * a blocked site you need -> "Allow"; a site you don't want -> "Block".
 * Reads LiveLog, which lives in memory only (nothing saved, nothing sent).
 */
class LiveActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private lateinit var filterBtn: Button
    private var blockedOnly = false
    private var shownVersion = -1L
    private val labels = HashMap<String, String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStats.load(this)
        setContentView(buildUi())
    }

    override fun onResume() { super.onResume(); shownVersion = -1L; tick() }
    override fun onPause() { super.onPause(); ui.removeCallbacksAndMessages(null) }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_main)
            setPadding(dp(20), dp(24), dp(20), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "📡  Live"
            setTextColor(Color.WHITE); textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        summary = TextView(this).apply {
            setTextColor(Color.parseColor("#8AA0B2")); textSize = 13f
            setPadding(0, dp(4), 0, dp(10))
        }
        root.addView(summary)
        filterBtn = Button(this).apply {
            setBackgroundResource(R.drawable.btn_secondary); setTextColor(Color.WHITE)
            isAllCaps = false
            setOnClickListener {
                blockedOnly = !blockedOnly
                shownVersion = -1L; render()
            }
        }
        root.addView(filterBtn)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            setPadding(0, dp(8), 0, 0)
            addView(list)
        })
        return root
    }

    /** Redraw once a second, but only when something new actually happened. */
    private fun tick() {
        if (LiveLog.version != shownVersion) render()
        ui.postDelayed({ tick() }, 1000)
    }

    private fun render() {
        shownVersion = LiveLog.version
        val all = LiveLog.recent()
        val blocked = all.count { it.verdict != LiveLog.Verdict.ALLOWED }
        summary.text = when {
            !GuardianVpnService.isRunning.get() ->
                "Protection is off — turn it on to see what your apps are doing."
            all.isEmpty() -> "Waiting for the first lookup… open any app."
            else -> "Last ${all.size} lookups · $blocked blocked. Tap a line to allow or block it.\n" +
                "Kept in memory only — never saved, never sent."
        }
        filterBtn.text = if (blockedOnly) "Showing: blocked only  ·  tap for all" else "Showing: everything  ·  tap for blocked only"
        list.removeAllViews()
        val rows = if (blockedOnly) all.filter { it.verdict != LiveLog.Verdict.ALLOWED } else all
        for (e in rows.take(150)) list.addView(row(e))
    }

    private fun row(e: LiveLog.Entry): View {
        val isBlocked = e.verdict != LiveLog.Verdict.ALLOWED
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isFocusable = true                                  // TV / D-pad
            setBackgroundResource(R.drawable.row_focus)
            addView(TextView(this@LiveActivity).apply {
                text = (if (isBlocked) "⛔  " else "✓  ") + e.domain
                setTextColor(Color.parseColor(if (isBlocked) "#E5484D" else "#4CC38A"))
                textSize = 15f
            })
            addView(TextView(this@LiveActivity).apply {
                val why = if (isBlocked && e.label.isNotEmpty()) "  ·  ${e.label}" else ""
                text = "${timeFmt.format(Date(e.time))}  ·  ${appName(e.pkg)}$why"
                setTextColor(Color.parseColor("#8AA0B2")); textSize = 12f
            })
            setOnClickListener { showActions(e) }
        }
    }

    /** One-tap fixes for the tapped lookup. */
    private fun showActions(e: LiveLog.Entry) {
        val site = siteOf(e.domain)
        val options = ArrayList<Pair<String, () -> Unit>>()
        when (e.verdict) {
            LiveLog.Verdict.ALLOWED -> {
                options += "⛔  Block ${e.domain}" to { block(e.domain) }
                if (site != e.domain) options += "⛔  Block everything on $site" to { block(site) }
            }
            LiveLog.Verdict.BLOCKED, LiveLog.Verdict.CLOAKED -> {
                options += "✓  Allow ${e.domain}" to { allow(e.domain) }
                if (site != e.domain) options += "✓  Allow everything on $site" to { allow(site) }
            }
            LiveLog.Verdict.MINE -> {
                options += "✓  Stop blocking ${e.domain}" to {
                    // Remove whichever entry on the user's list covers it.
                    val hit = AppStats.userBlockList().firstOrNull { e.domain == it || e.domain.endsWith(".$it") }
                    if (hit != null) AppStats.removeUserBlock(this, hit)
                    toast("Unblocked ${hit ?: e.domain}. Reload the app or page.")
                }
            }
            LiveLog.Verdict.FIREWALL -> {
                options += "Open per-app details" to {
                    startActivity(android.content.Intent(this, AppsActivity::class.java))
                }
            }
        }
        val message = buildString {
            append("App: ${appName(e.pkg)}\n")
            if (e.label.isNotEmpty()) append("Why: ${e.label}\n")
            append("Time: ${timeFmt.format(Date(e.time))}")
        }
        AlertDialog.Builder(this)
            .setTitle(e.domain)
            .setMessage(message)
            .apply {
                // AlertDialog can't show a message AND a list, so use buttons.
                options.getOrNull(0)?.let { (t, f) -> setPositiveButton(t) { _, _ -> f() } }
                options.getOrNull(1)?.let { (t, f) -> setNeutralButton(t) { _, _ -> f() } }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun block(d: String) {
        AppStats.addUserBlock(this, d)
        toast("Blocked $d — applies right away.")
    }

    private fun allow(d: String) {
        AppStats.addUserAllow(this, d)
        toast("Allowed $d. Reload the app or page.")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun appName(pkg: String): String = labels.getOrPut(pkg) {
        if (pkg == AppStats.UNKNOWN) "System / unknown app"
        else try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** The "site" a host belongs to: ads.news.bbc.co.uk -> bbc.co.uk,
         *  cdn.example.com -> example.com. Handles two-part country endings
         *  (co.uk, com.au…) so "block everything on" never blocks a whole country. */
        fun siteOf(host: String): String {
            val p = host.split('.')
            if (p.size <= 2) return host
            val twoPartSuffix = p[p.size - 1].length == 2 &&
                p[p.size - 2] in setOf("co", "com", "net", "org", "gov", "ac", "edu", "ne", "or")
            val keep = if (twoPartSuffix) 3 else 2
            return if (p.size <= keep) host else p.takeLast(keep).joinToString(".")
        }
    }
}
