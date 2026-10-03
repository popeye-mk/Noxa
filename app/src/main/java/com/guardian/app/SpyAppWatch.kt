package com.guardian.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * v1.13 spy-app watch. Once a day, offline: an app that can READ AND CONTROL
 * the screen (Accessibility) and ALSO either can't be uninstalled normally
 * (device admin) or didn't come from an app store is how banking trojans and
 * stalkerware work. Noxa names it once — it never removes anything itself.
 */
object SpyAppWatch {
    private const val PREFS = "guardian_health"
    private const val KEY_CHECKED = "spyapp_checked"
    private const val KEY_TOLD = "spyapp_told"
    private const val CHANNEL_ID = "guardian_alerts"
    private const val NOTIF_ID = 390
    private const val DAY = 24L * 60 * 60 * 1000

    /** Stores an app may legitimately come from. */
    private val STORES = setOf(
        "com.android.vending", "com.google.android.packageinstaller.store",
        "com.sec.android.app.samsungapps", "com.huawei.appmarket", "com.xiaomi.market",
        "com.xiaomi.mipicks", "com.heytap.market", "com.oppo.market", "com.bbk.appstore",
        "com.amazon.venezia", "org.fdroid.fdroid", "com.aurora.store", "com.google.android.feedback")

    /** Well-known apps that need both powers for honest reasons. */
    private val KNOWN_GOOD = setOf(
        // password managers
        "com.x8bit.bitwarden", "com.lastpass.lpandroid", "com.agilebits.onepassword",
        "com.dashlane", "keepass2android.keepass2android", "com.kunzisoft.keepass.free",
        "com.callpod.android_apps.keeper", "com.nordpass.android.app.password.manager",
        // security / antivirus
        "com.avast.android.mobilesecurity", "com.avg.cleaner", "com.antivirus",
        "com.bitdefender.security", "com.eset.ems2.gp", "com.kms.free",
        "com.kaspersky.security.cloud", "org.malwarebytes.antimalware",
        "com.symantec.mobilesecurity", "com.wsandroid.suite", "com.trendmicro.tmmspersonal",
        // work profile / MDM, Google family tools
        "com.google.android.apps.work.clouddpc", "com.microsoft.windowsintune.companyportal",
        "com.airwatch.androidagent", "com.google.android.apps.kids.familylink",
        "com.google.android.apps.adm",
        // accessibility tools
        "com.google.android.marvin.talkback", "com.samsung.android.accessibility.talkback",
        "com.google.android.apps.accessibility.voiceaccess")

    /** Pure, unit-tested. */
    fun suspicious(pkg: String, accessibility: Boolean, deviceAdmin: Boolean,
                   fromStore: Boolean, system: Boolean, self: String): Boolean =
        accessibility && !system && pkg != self && pkg !in KNOWN_GOOD && (deviceAdmin || !fromStore)

    /** Pure, unit-tested: "pkg/.Svc:other/com.x.Y" -> {pkg, other}. */
    fun accessibilityPackages(setting: String?): Set<String> =
        (setting ?: "").split(':').mapNotNull { it.substringBefore('/').trim().takeIf { p -> p.isNotEmpty() } }.toSet()

    fun daily(ctx: Context, now: Long = System.currentTimeMillis()) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (now - p.getLong(KEY_CHECKED, 0L) < DAY) return
        p.edit().putLong(KEY_CHECKED, now).apply()
        Thread {
            try {
                val found = scan(ctx)
                val told = (p.getString(KEY_TOLD, "") ?: "").split(',').filter { it.isNotEmpty() }.toSet()
                val fresh = found.filter { it.first !in told }
                if (fresh.isEmpty()) return@Thread
                p.edit().putString(KEY_TOLD, (told + fresh.map { it.first }).joinToString(",")).apply()
                notify(ctx, fresh.first().first, fresh.first().second, fresh.size - 1)
            } catch (_: Exception) {}
        }.start()
    }

    /** (pkg, reason) for every suspicious app right now. */
    fun scan(ctx: Context): List<Pair<String, String>> {
        val a11y = accessibilityPackages(
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        if (a11y.isEmpty()) return emptyList()
        val admins = try {
            ctx.getSystemService(DevicePolicyManager::class.java)?.activeAdmins?.map { it.packageName }?.toSet()
        } catch (_: Exception) { null } ?: emptySet()
        val pm = ctx.packageManager
        return a11y.mapNotNull { pkg ->
            val ai = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { return@mapNotNull null }
            val system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val installer = try {
                if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
                else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
            } catch (_: Exception) { null }
            val admin = pkg in admins
            val store = installer != null && installer in STORES
            if (!suspicious(pkg, true, admin, store, system, ctx.packageName)) null
            else pkg to (if (admin) "can read and control your screen AND blocks being uninstalled"
                         else "can read and control your screen and wasn't installed from an app store")
        }
    }

    private fun notify(ctx: Context, pkg: String, reason: String, more: Int) {
        val name = try { ctx.packageManager.getApplicationInfo(pkg, 0).loadLabel(ctx.packageManager).toString() }
                   catch (_: Exception) { pkg }
        val text = "\"$name\" $reason. Banking trojans and spy apps work this way. " +
            "If you didn't set this up yourself, tap → turn off its Accessibility and device admin, then uninstall it." +
            (if (more > 0) " ($more more app(s) like this.)" else "")
        try {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Security alerts", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(ctx, NOTIF_ID,
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            mgr.notify(NOTIF_ID, Notification.Builder(ctx, CHANNEL_ID)
                .setContentTitle("⚠ Check this app: $name")
                .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (_: Exception) {}
    }
}
