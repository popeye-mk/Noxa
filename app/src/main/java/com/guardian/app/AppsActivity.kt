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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * "Who's tracking you" — one tap deeper than the main switch.
 *
 * Friendly-first layout (the app is for non-technical people): your apps are
 * the star of the screen — real icons, plain names, ranked by how often each
 * tried to track you, each with a "block it entirely" switch. All the technical
 * controls (encrypted DNS, tunnel, allowlist, update, exclude, always-on) live
 * behind ONE "Settings & tools" button, so the default view stays calm.
 *
 * Built programmatically — no layout files.
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
    } catch (e: Exception) { prettyPkg(pkg) }

    /** Last resort for system apps with no user-facing name: turn
     *  "com.miui.msa.global" into "Miui Msa Global" — still nicer than a raw id. */
    private fun prettyPkg(pkg: String): String =
        pkg.substringAfterLast('.').replace('_', ' ')
            .replaceFirstChar { it.uppercase() }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_main)
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }

        root.addView(TextView(this).apply {
            text = "Who's tracking you"
            setTextColor(Color.WHITE); textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Your apps, ranked by how often they tried to track you. Tap one for details, or use the switch to block it completely."
            setTextColor(Color.parseColor("#8FA0BC")); textSize = 13f
            setPadding(0, dp(4), 0, dp(14))
        })

        // Plain-language summary across all apps (the green headline number).
        root.addView(TextView(this).apply {
            text = Explanations.header(GuardianVpnService.blockedCount.get())
            setTextColor(Color.parseColor("#4CC38A")); textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(14))
        })

        // Everyday privacy toggle (kept out because it's a simple on/off, not a menu).
        root.addView(dohToggleRow())

        // ALL the technical controls collapse into one calm button.
        root.addView(Button(this).apply {
            text = "⚙  Settings & tools"
            setBackgroundResource(R.drawable.btn_dark)
            setTextColor(Color.WHITE)
            setOnClickListener { showToolsMenu() }
        })

        root.addView(TextView(this).apply {
            text = "YOUR APPS"
            setTextColor(Color.parseColor("#61708C")); textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(dp(4), dp(18), 0, dp(4))
        })

        val apps = AppStats.seenApps().sortedByDescending { AppStats.blocked[it] ?: 0L }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (apps.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Nothing yet. Turn Noxa on and use your phone for a bit — then come back to see who's been tracking you."
                setTextColor(Color.parseColor("#8FA0BC"))
                setPadding(dp(4), dp(24), dp(4), 0)
            })
        } else {
            for (pkg in apps) list.addView(row(pkg))
        }
        // The list fills the rest of the screen and scrolls; the header above
        // stays put — so the tools never push the apps off-screen.
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(list)
        })
        return root
    }

    private fun row(pkg: String): View {
        val blocked = AppStats.blocked[pkg] ?: 0L
        val name = label(pkg)

        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                rightMargin = dp(12)
            }
            try { setImageDrawable(packageManager.getApplicationIcon(pkg)) }
            catch (e: Exception) { setImageResource(android.R.drawable.sym_def_app_icon) }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = name; setTextColor(Color.WHITE); textSize = 16f
        })
        col.addView(TextView(this).apply {
            if (blocked > 0) {
                text = "🛡  ${"%,d".format(blocked)} tracking attempts blocked"
                setTextColor(Color.parseColor("#4CC38A"))
            } else {
                text = "No tracking seen yet"
                setTextColor(Color.parseColor("#61708C"))
            }
            textSize = 13f
        })
        if (AppStats.isNoFilter(pkg)) {
            col.addView(TextView(this).apply {
                text = "⚠ Not filtered (bypasses Noxa)"
                setTextColor(Color.parseColor("#C7923E")); textSize = 12f
            })
        }

        val block = Switch(this).apply {
            isChecked = AppStats.isFirewalled(pkg)
            thumbTintList = resources.getColorStateList(R.color.switch_thumb, theme)
            trackTintList = resources.getColorStateList(R.color.switch_track, theme)
            setOnCheckedChangeListener { _, on -> AppStats.setFirewalled(this@AppsActivity, pkg, on) }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            addView(icon)
            addView(col)
            addView(block)
            setOnClickListener { showDetail(name, pkg) }
            isFocusable = true                        // TV/D-pad focus
            setBackgroundResource(R.drawable.row_focus)
        }
    }

    /** Extra-privacy toggle: encrypted DNS (DoH). Plain-language label. */
    private fun dohToggleRow(): View {
        val label = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@AppsActivity).apply {
                text = "Hide my browsing from Wi-Fi & my provider"
                setTextColor(Color.WHITE); textSize = 15f
            })
            addView(TextView(this@AppsActivity).apply {
                text = "Encrypts your lookups (DoH). Leave on."
                setTextColor(Color.parseColor("#8FA0BC")); textSize = 12f
            })
        }
        val sw = Switch(this).apply {
            isChecked = GuardianVpnService.encryptedDns.get()
            thumbTintList = resources.getColorStateList(R.color.switch_thumb, theme)
            trackTintList = resources.getColorStateList(R.color.switch_track, theme)
            setOnCheckedChangeListener { _, on -> GuardianVpnService.setEncryptedDns(this@AppsActivity, on) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(14))
            addView(label); addView(sw)
        }
    }

    /** All the advanced controls, in one calm plain-language menu. */
    private fun showToolsMenu() {
        val items = arrayOf(
            "🌐  Hide my IP (private tunnel)",
            "✓  Allowed sites (never block these)",
            "🔧  Fix an app that won't work",
            "🔄  Update protection now",
            "🔋  Keep protection always on",
            "📄  Save my report"
        )
        AlertDialog.Builder(this)
            .setTitle("Settings & tools")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> startActivity(Intent(this, TunnelActivity::class.java))
                    1 -> startActivity(Intent(this, AllowlistActivity::class.java))
                    2 -> showExcludePicker()
                    3 -> checkForUpdate()
                    4 -> openAlwaysOn()
                    5 -> exportCsv()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun checkForUpdate() {
        Toast.makeText(this, "Checking for a newer blocklist…", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = FilterUpdater.checkAndUpdate(this@AppsActivity)
            runOnUiThread { Toast.makeText(this@AppsActivity, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun openAlwaysOn() {
        Toast.makeText(this,
            "Tap the gear ⚙ next to Noxa, then turn on \"Always-on VPN\".",
            Toast.LENGTH_LONG).show()
        try { startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS)) }
        catch (_: Exception) {}
    }

    /** Tap an app -> plain-language breakdown + the "don't filter" escape hatch. */
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

    /** Pick ANY installed app to exclude from Noxa (for apps that refuse to run
     *  while a VPN is active — Disney+, some banking apps, Android Auto). */
    private fun showExcludePicker() {
        val pm = packageManager
        val headless = listOf("com.google.android.projection.gearhead") // Android Auto (no icon)
            .filter { pkg -> try { pm.getApplicationInfo(pkg, 0); true } catch (e: Exception) { false } }
        val launchables = (
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0) +
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            )
            .map { it.activityInfo.packageName }
            .plus(headless)
            .distinct()
            .filter { it != packageName }
            .map { pkg -> Pair(label(pkg), pkg) }
            .sortedBy { it.first.lowercase() }
        val labels = launchables.map { (name, pkg) ->
            (if (AppStats.isNoFilter(pkg)) "✓ " else "") + name
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Which app won't work?")
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
            putExtra(Intent.EXTRA_SUBJECT, "Noxa report")
            putExtra(Intent.EXTRA_TEXT, AppStats.exportCsv())
        }
        startActivity(Intent.createChooser(share, "Save my report"))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
