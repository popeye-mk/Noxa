package com.guardian.app

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Android 15+ draws every app edge-to-edge (behind the status bar, the
 * navigation bar and the keyboard) once the app targets API 35, and from API 36
 * there is no opt-out. Noxa's screens are plain views, so this keeps them
 * between the bars in one place instead of in every screen:
 *
 *  - the screen's content area (android.R.id.content) gets padding equal to
 *    the system bars + display cutout + keyboard, so nothing hides under them;
 *  - the strips behind the bars show Noxa's own night-sky background.
 *
 * Padding goes on the content FRAME, not on the screen's own view, so screens
 * that rebuild themselves with setContentView() keep it. See-through screens
 * (Check link, the update prompt) are left alone. Before Android 15 nothing
 * changes: the system already keeps apps between the bars.
 */
object EdgeToEdge {

    fun apply(activity: Activity) {
        if (Build.VERSION.SDK_INT < 35 || isTranslucent(activity)) return
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        content.setBackgroundResource(R.drawable.bg_main)
        content.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }
        content.requestApplyInsets()
    }

    private fun isTranslucent(activity: Activity): Boolean {
        val a = activity.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent))
        return try { a.getBoolean(0, false) } finally { a.recycle() }
    }
}
