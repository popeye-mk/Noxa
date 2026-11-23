package com.guardian.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * v1.16.2: who gets the one VPN slot back when the tunnel ends.
 *
 * Starting the tunnel stops Noxa's own filter (Android allows one VPN). Until
 * now only the public Wi-Fi guard brought the filter back afterwards. Switching
 * the tunnel off by hand, a tunnel that failed to connect, or a restart of the
 * phone left the device with NO filter at all, and with a parent lock that
 * needed no PIN (independent review of 23 Nov 2026).
 *
 * Now every way into the tunnel remembers whether the filter was on, and every
 * way out brings it back.
 */
object TunnelHandover {
    private const val TAG = "Guardian"
    private const val KEY_WANTED = "auto_wanted_blocker"   // the public Wi-Fi guard's old key, same meaning
    private const val KEY_PENDING = "handover_pending"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(TunnelActivity.PREFS, Context.MODE_PRIVATE)

    /** Pure, unit-tested: does Noxa's own filter come back when the tunnel ends? */
    fun bringBack(filterWasOn: Boolean, familyDevice: Boolean): Boolean = filterWasOn || familyDevice

    /** A device a parent set up: parent lock, bedtime, family filter or safe search. */
    fun familyDevice(ctx: Context): Boolean =
        ParentLock.isOn(ctx) || Bedtime.isOn(ctx) || TunnelController.familyOn(ctx)

    /** v1.16.3: when the public Wi-Fi guard must leave the tunnel off. Only what the tunnel would switch
     *  off for a child: bedtime, and anything behind a parent PIN. The family filter and safe search alone
     *  don't count - the tunnel keeps both through the family resolver, and 1.16.2 wrongly stopped the guard
     *  on an adult's own phone that simply has the family filter on. Pure, unit-tested. */
    fun guardStaysOff(parentLock: Boolean, bedtime: Boolean): Boolean = parentLock || bedtime

    /** Call BEFORE stopping the filter to make room for the tunnel. */
    fun remember(ctx: Context) {
        val p = prefs(ctx)
        val wasOn = GuardianVpnService.wantsProtection(ctx) ||
            (p.getBoolean(KEY_PENDING, false) && p.getBoolean(KEY_WANTED, false))   // an earlier handover never ended
        p.edit().putBoolean(KEY_WANTED, wasOn).putBoolean(KEY_PENDING, true).apply()
    }

    /** The tunnel ended (switched off, failed to connect, left the public Wi-Fi). True when the filter was restarted. */
    fun restore(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_PENDING, false)) return false          // nothing was handed over
        p.edit().putBoolean(KEY_PENDING, false).apply()
        if (!bringBack(p.getBoolean(KEY_WANTED, true), familyDevice(ctx))) return false
        GuardianVpnService.setWantsProtection(ctx, true)             // at once: the watchdog heals a start that fails
        val svc = Intent(ctx, GuardianVpnService::class.java).setAction(GuardianVpnService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc) else ctx.startService(svc)
        } catch (e: Exception) {
            Log.w(TAG, "restore filter: $e")
            try { WatchdogReceiver.schedule(ctx) } catch (_: Exception) {}
        }
        return true
    }

    /** Protection was switched off on purpose while the tunnel was up (main switch, after the PIN). */
    fun forget(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_PENDING, false).apply()
    }

    /** A fresh app process (restart of the phone, or Android killed Noxa): the tunnel lived in the old
     *  process and is gone. Protection is wanted again; the watchdog (run at once after a boot) starts it. */
    fun afterProcessStart(ctx: Context) {
        try {
            val p = prefs(ctx)
            if (!p.getBoolean(KEY_PENDING, false)) return
            p.edit().putBoolean(KEY_PENDING, false).apply()
            if (!bringBack(p.getBoolean(KEY_WANTED, true), familyDevice(ctx))) return
            GuardianVpnService.setWantsProtection(ctx, true)
            WatchdogReceiver.schedule(ctx)
        } catch (e: Exception) { Log.w(TAG, "handover after restart: $e") }
    }
}
