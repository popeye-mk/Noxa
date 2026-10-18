package com.guardian.app

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.TextView

/**
 * One "← Back" row at the top of every screen below the main one (1.14.3). On a tablet or a TV there is no
 * reliable system back — a tablet in a kiosk, a remote with no back key — and deeper screens such as
 * "Report to my home server" had no way out. Focusable, so the TV remote reaches it first; tapping it closes
 * the screen like the system back would.
 */
object BackBar {
    fun build(activity: Activity, label: String = "←  Back"): View = TextView(activity).apply {
        val d = activity.resources.displayMetrics.density
        text = label
        textSize = 16f
        setTextColor(Color.parseColor("#C9D3E6"))
        gravity = Gravity.CENTER_VERTICAL
        minHeight = (44 * d).toInt()
        setPadding((4 * d).toInt(), 0, (12 * d).toInt(), (6 * d).toInt())
        isFocusable = true
        isClickable = true
        contentDescription = "Back"
        setBackgroundResource(R.drawable.row_focus)
        setOnClickListener { activity.finish() }
    }
}
