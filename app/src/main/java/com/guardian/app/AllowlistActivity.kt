package com.guardian.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * User allowlist — "never block these." If Guardian ever blocks something the
 * user needs, they add its domain here and it's excluded from blocking (overrides
 * the tracker filter). Applies live (AppStats is a shared singleton in-process).
 */
class AllowlistActivity : Activity() {

    private lateinit var field: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStats.load(this)
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1116"))
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "Allowed sites"
            setTextColor(Color.WHITE); textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "If Guardian ever blocks something you need, add its address here and " +
                "it will never be blocked. Example: example.com"
            setTextColor(Color.parseColor("#8AA0B2")); textSize = 13f
            setPadding(0, dp(4), 0, dp(16))
        })

        field = EditText(this).apply {
            hint = "example.com"
            setHintTextColor(Color.parseColor("#55627A"))
            setTextColor(Color.WHITE); textSize = 15f
            setBackgroundColor(Color.parseColor("#161B22"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isSingleLine = true
        }
        root.addView(field)
        root.addView(Button(this).apply {
            text = "Add to allowlist"
            setBackgroundColor(Color.parseColor("#1B2430")); setTextColor(Color.WHITE)
            setOnClickListener {
                val d = field.text.toString()
                if (d.isNotBlank()) {
                    AppStats.addUserAllow(this@AllowlistActivity, d)
                    field.setText("")
                    Toast.makeText(this@AllowlistActivity, "Added.", Toast.LENGTH_SHORT).show()
                    setContentView(buildUi())
                }
            }
        })

        val list = AppStats.userAllowList()
        root.addView(TextView(this).apply {
            text = if (list.isEmpty()) "\nNothing allowlisted yet." else "\nTap an entry to remove it:"
            setTextColor(Color.parseColor("#8AA0B2")); textSize = 13f
            setPadding(0, dp(16), 0, dp(6))
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (d in list) {
            col.addView(TextView(this).apply {
                text = "  ✓  $d      (tap to remove)"
                setTextColor(Color.parseColor("#4CC38A")); textSize = 15f
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener {
                    AppStats.removeUserAllow(this@AllowlistActivity, d)
                    Toast.makeText(this@AllowlistActivity, "Removed.", Toast.LENGTH_SHORT).show()
                    setContentView(buildUi())
                }
            })
        }
        root.addView(ScrollView(this).apply { addView(col) })
        return root
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
