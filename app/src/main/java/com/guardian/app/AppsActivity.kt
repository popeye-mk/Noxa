package com.guardian.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Phase 2 dashboard — one tap deeper than the main switch, never in the default
 * path. Lists the apps Guardian has seen (most-blocked first), each with a
 * "block this app entirely" switch (the per-app firewall). An Export button
 * shares the full table as CSV so weekly totals never have to be tracked by hand.
 *
 * Built programmatically so Phase 2 adds no new layout files.
 */
class AppsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStats.load(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        setContentView(buildUi())   // refresh counts when returning to the screen
    }

    private fun label(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_main)
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "Per-app blocking"
            setTextColor(Color.WHITE); textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Most-blocked first. Tap an app to see who it was reaching; flip a switch to block it entirely."
            setTextColor(Color.parseColor("#8AA0B2")); textSize = 13f
            setPadding(0, dp(4), 0, dp(12))
        })

        // Phase 3: plain-language summary across all apps.
        root.addView(TextView(this).apply {
            text = Explanations.header(GuardianVpnService.blockedCount.get())
            setTextColor(Color.parseColor("#4CC38A")); textSize = 14f
            setPadding(0, 0, 0, dp(12))
        })

        // Privacy hardening: encrypt DNS. Kept one tap deeper, off the main screen.
        root.addView(dohToggleRow())

        root.addView(Button(this).apply {
            text = "Export stats (CSV)"
            setBackgroundResource(R.drawable.btn_dark)
            setTextColor(Color.WHITE)
            setOnClickListener { exportCsv() }
        })

        // Tunnel (option 2) — one tap deeper.
        root.addView(Button(this).apply {
            text = "Hide my IP (tunnel) ›"
            setBackgroundResource(R.drawable.btn_secondary)
            setTextColor(Color.parseColor("#8AA0B2"))
            setOnClickListener { startActivity(Intent(this@AppsActivity, TunnelActivity::class.java)) }
        })

        // User allowlist — un-block anything caught by mistake.
        root.addView(Button(this).apply {
            text = "Allowed sites (never block) ›"
            setBackgroundResource(R.drawable.btn_secondary)
            setTextColor(Color.parseColor("#8AA0B2"))
            setOnClickListener { startActivity(Intent(this@AppsActivity, AllowlistActivity::class.java)) }
        })

        // Blocklist version + manual update (Step 2).
        val filterStatus = TextView(this).apply {
            text = "Blocklist: ${FilterUpdater.currentBuiltAt(this@AppsActivity)}"
            setTextColor(Color.parseColor("#8AA0B2")); textSize = 12f
            setPadding(0, dp(14), 0, dp(4))
        }
        root.addView(filterStatus)
        root.addView(Button(this).apply {
            text = "Check for blocklist update"
            setBackgroundResource(R.drawable.btn_dark)
            setTextColor(Color.WHITE)
            setOnClickListener {
                filterStatus.text = "Checking for update…"
                Thread {
                    val msg = FilterUpdater.checkAndUpdate(this@AppsActivity)
                    runOnUiThread {
                        Toast.makeText(this@AppsActivity, msg, Toast.LENGTH_LONG).show()
                        filterStatus.text = "Blocklist: ${FilterUpdater.currentBuiltAt(this@AppsActivity)}"
                    }
                }.start()
            }
        })

        // "Don't filter this app" for apps that refuse to work behind a VPN —
        // reachable even when the app has never appeared in the stats list
        // (VPN-detecting apps often never send a single lookup through us).
        root.addView(Button(this).apply {
            text = "App won't work with Noxa on? Exclude it ›"
            setBackgroundResource(R.drawable.btn_dark)
            setTextColor(Color.WHITE)
            setOnClickListener { showExcludePicker() }
        })

        val apps = AppStats.seenApps().sortedByDescending { AppStats.blocked[it] ?: 0L }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (apps.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No activity yet. Turn Noxa on and use the phone for a bit, then come back."
                setTextColor(Color.parseColor("#8AA0B2"))
                setPadding(0, dp(24), 0, 0)
            })
        } else {
            for (pkg in apps) list.addView(row(pkg))
        }
        root.addView(ScrollView(this).apply { addView(list) })
        return root
    }

    private fun row(pkg: String): View {
        val blocked = AppStats.blocked[pkg] ?: 0L
        val allowed = AppStats.allowed[pkg] ?: 0L
        val name = label(pkg)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = name; setTextColor(Color.WHITE); textSize = 16f
        })
        col.addView(TextView(this).apply {
            text = "$blocked blocked · $allowed allowed"
            setTextColor(Color.parseColor("#8AA0B2")); textSize = 12f
        })
        // The plain-language "who was this app reaching" breakdown lives one tap
        // deeper (tap the row) — keeping the list itself clean and scannable.

        val block = Switch(this).apply {
            isChecked = AppStats.isFirewalled(pkg)
            setOnCheckedChangeListener { _, on -> AppStats.setFirewalled(this@AppsActivity, pkg, on) }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(col)
            addView(block)
            setOnClickListener { showDetail(name, pkg) }
        }
    }

    /** Encrypt-my-DNS toggle: forwards allowed lookups over HTTPS (DoH). */
    private fun dohToggleRow(): View {
        val label = TextView(this).apply {
            text = "Encrypt my DNS — hide your lookups from Wi-Fi / ISP"
            setTextColor(Color.WHITE); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = Switch(this).apply {
            isChecked = GuardianVpnService.encryptedDns.get()
            setOnCheckedChangeListener { _, on -> GuardianVpnService.setEncryptedDns(this@AppsActivity, on) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(14))
            addView(label); addView(sw)
        }
    }

    /** Tap an app -> plain-language breakdown of who it was trying to reach. */
    private fun showDetail(name: String, pkg: String) {
        val excluded = AppStats.isNoFilter(pkg)
        val extra = if (excluded)
            "\n\n⚠ Not filtered: this app bypasses Noxa completely (it works, " +
            "but nothing is blocked or counted for it)."
        else ""
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(Explanations.appDetail(name, pkg) + extra)
            .setPositiveButton("Close", null)
            .setNeutralButton(if (excluded) "Filter this app again" else "Don't filter this app") { _, _ ->
                AppStats.setNoFilter(this, pkg, !excluded)
                Toast.makeText(this,
                    (if (!excluded) "$name will bypass Noxa" else "$name is filtered again") +
                    " — turn protection off and on to apply.",
                    Toast.LENGTH_LONG).show()
            }
            .show()
    }

    /** Pick ANY installed app to exclude from Noxa (VpnService bypass). For
     *  apps that show "no internet" while a VPN is active (Disney+, some
     *  banking apps). ✓ marks currently excluded apps; tapping toggles. */
    private fun showExcludePicker() {
        val pm = packageManager
        val launchables = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.distinct()
            .filter { it != packageName }
            .map { pkg -> Pair(label(pkg), pkg) }
            .sortedBy { it.first.lowercase() }
        val labels = launchables.map { (name, pkg) ->
            (if (AppStats.isNoFilter(pkg)) "✓ " else "") + name
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Exclude an app from Noxa")
            .setItems(labels) { _, i ->
                val (name, pkg) = launchables[i]
                val nowOn = !AppStats.isNoFilter(pkg)
                AppStats.setNoFilter(this, pkg, nowOn)
                Toast.makeText(this,
                    (if (nowOn) "$name will bypass Noxa" else "$name is filtered again") +
                    " — turn protection off and on to apply.",
                    Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun exportCsv() {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "Noxa per-app stats")
            putExtra(Intent.EXTRA_TEXT, AppStats.exportCsv())
        }
        startActivity(Intent.createChooser(share, "Export Noxa stats"))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
