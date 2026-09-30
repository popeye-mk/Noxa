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
 *
 * v1.5: the same screen doubles as "My blocked sites" (EXTRA_BLOCK = true) —
 * the user's own always-block list, fed from here or from the Live feed.
 */
class AllowlistActivity : Activity() {

    companion object { const val EXTRA_BLOCK = "block_mode" }

    private lateinit var field: EditText
    private val blockMode by lazy { intent.getBooleanExtra(EXTRA_BLOCK, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStats.load(this)
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_main)
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        root.addView(TextView(this).apply {
            text = if (blockMode) "My blocked sites" else "Allowed sites"
            setTextColor(Color.WHITE); textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = if (blockMode)
                "Sites you've chosen to block yourself — on top of Noxa's list. " +
                "Blocking example.com also blocks everything under it. Example: example.com"
            else "If Noxa ever blocks something you need, add its address here and " +
                "it will never be blocked. Example: example.com"
            setTextColor(Color.parseColor("#8AA0B2")); textSize = 13f
            setPadding(0, dp(4), 0, dp(16))
        })

        field = EditText(this).apply {
            hint = "example.com"
            setHintTextColor(Color.parseColor("#55627A"))
            setTextColor(Color.WHITE); textSize = 15f
            setBackgroundResource(R.drawable.field_dark)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isSingleLine = true
        }
        root.addView(field)
        root.addView(Button(this).apply {
            text = if (blockMode) "Block this site" else "Add to allowlist"
            setBackgroundResource(R.drawable.btn_primary); setTextColor(Color.WHITE)
            setOnClickListener {
                val d = field.text.toString()
                if (d.isNotBlank()) {
                    if (blockMode) AppStats.addUserBlock(this@AllowlistActivity, d)
                    else AppStats.addUserAllow(this@AllowlistActivity, d)
                    field.setText("")
                    Toast.makeText(this@AllowlistActivity, "Added.", Toast.LENGTH_SHORT).show()
                    setContentView(buildUi())
                }
            }
        })

        val list = if (blockMode) AppStats.userBlockList() else AppStats.userAllowList()
        root.addView(TextView(this).apply {
            text = if (list.isNotEmpty()) "\nTap an entry to remove it:"
                   else if (blockMode) "\nNothing blocked by you yet." else "\nNothing allowlisted yet."
            setTextColor(Color.parseColor("#8AA0B2")); textSize = 13f
            setPadding(0, dp(16), 0, dp(6))
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (d in list) {
            col.addView(TextView(this).apply {
                text = if (blockMode) "  ⛔  $d      (tap to remove)" else "  ✓  $d      (tap to remove)"
                setTextColor(Color.parseColor(if (blockMode) "#E5484D" else "#4CC38A")); textSize = 15f
                setPadding(dp(8), dp(10), dp(8), dp(10))
                // TV/D-pad: entries must take focus and show it.
                isFocusable = true
                setBackgroundResource(R.drawable.row_focus)
                setOnClickListener {
                    if (blockMode) AppStats.removeUserBlock(this@AllowlistActivity, d)
                    else AppStats.removeUserAllow(this@AllowlistActivity, d)
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
