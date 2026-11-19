package com.guardian.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * v1.16 (security review of 19 Nov 2026): the only receiver other apps can
 * reach. It acts on the system's boot broadcast and nothing else — another app
 * can't send that one — and hands over to the watchdog, which is no longer
 * exported (any app could send it an intent with its own extras before).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        WatchdogReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
    }
}
