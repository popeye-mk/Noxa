package com.guardian.app

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** v1.15: app-wide setup that every screen needs — today, edge-to-edge (see EdgeToEdge). */
class NoxaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            // Runs right after each screen's own onCreate (Android 10+; edge-to-edge is Android 15+).
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) = EdgeToEdge.apply(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
