package com.guardian.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * v1.10 "Find the app behind pop-up ads" — see AdwareScan for how it decides.
 * One screen: the likeliest apps first, the reasons in plain words, and two
 * one-tap actions (open the app's settings to remove it, or cut its internet
 * with Noxa's per-app firewall so it can't load ads any more).
 */
class AdwareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStats.load(this)
        setContentView(page(listOf(text("Checking your apps…", 15f, "#C9D3E3"))))
        scan()
    }

    private fun scan() {
        Thread {
            val list = try { AdwareScan.scan(this) } catch (_: Exception) { emptyList() }
            runOnUiThread { if (!isFinishing) setContentView(results(list)) }
        }.start()
    }

    private fun results(list: List<AdwareScan.Suspect>): View {
        val views = ArrayList<View>()
        if (list.isEmpty()) {
            views += text("No app on this phone has the usual signs of a pop-up ad app. 👍", 15f, "#4CC38A")
        } else {
            views += text(
                "These apps have the abilities pop-up ad apps use. That doesn't prove they're bad — " +
                "look for one you don't recognise or don't use. The top one is the likeliest.",
                14f, "#E5A84D")
            for (s in list) views += card(s)
        }
        views += text(
            "\nTip: when a pop-up appears, open “Live — watch it happen” right after it. " +
            "The app that just contacted ad servers is the one showing it.", 13f, "#C9D3E3")
        views += button("📡  Open Live") { startActivity(Intent(this, LiveActivity::class.java)) }
        views += text("\nNothing about your apps leaves this phone.", 12f, "#8D9AB0")
        return page(views)
    }

    private fun card(s: AdwareScan.Suspect): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(12), dp(10), dp(12))
        isFocusable = true
        setBackgroundResource(R.drawable.row_focus)
        addView(text(s.name, 17f, "#FFFFFF", bold = true))
        for (r in s.reasons) addView(text("•  $r", 13f, "#C9D3E3"))
        val blocked = AppStats.isFirewalled(s.pkg)
        addView(LinearLayout(this@AdwareActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
            addView(button("App settings / remove") {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${s.pkg}")))
                } catch (_: Exception) {
                    Toast.makeText(this@AdwareActivity, "Open Settings → Apps → ${s.name}.", Toast.LENGTH_LONG).show()
                }
            })
            addView(button(if (blocked) "Allow its internet" else "Block its internet") {
                AppStats.setFirewalled(this@AdwareActivity, s.pkg, !blocked)
                Toast.makeText(this@AdwareActivity,
                    if (blocked) "${s.name} can use the internet again."
                    else "${s.name} is cut off from the internet — no more ads from it.",
                    Toast.LENGTH_LONG).show()
                scan()
            })
        })
    }

    private fun page(children: List<View>): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_main)
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        root.addView(text("🕵  Find the app behind pop-up ads", 22f, "#FFFFFF", bold = true))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        for (c in children) col.addView(c)
        root.addView(ScrollView(this).apply { addView(col) })
        return root
    }

    private fun text(s: String, size: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, onTap: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false
        setBackgroundResource(R.drawable.btn_primary); setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(4), dp(8), dp(4)) }
        setOnClickListener { onTap() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
