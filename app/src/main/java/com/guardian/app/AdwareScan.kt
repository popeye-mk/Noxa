package com.guardian.app

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * v1.10 pop-up ad app finder. Full-screen ads over other apps or the lock
 * screen almost always come from ONE installed app (a "cleaner", flashlight,
 * free game...). Android never says which. This looks for the abilities such
 * apps need, plus what Noxa itself saw them do:
 *
 *  - may draw over other apps            (how the pop-up appears)
 *  - hides its own icon                  (so you can't find and remove it)
 *  - Accessibility switched on for it    (can read and control the screen)
 *  - many ad-server lookups this month   (Noxa's own per-app counts)
 *  - not from an app store, or installed in the last 2 weeks
 *
 * Everything is read on the phone; nothing is sent anywhere. Having these
 * abilities doesn't prove an app is bad (a password manager or a chat-bubble
 * app has some of them), so the screen shows the reasons and lets the user
 * decide.
 */
object AdwareScan {
    const val THRESHOLD = 3
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Installers we treat as app stores (anything else = installed from a file). */
    private val STORES = setOf(
        "com.android.vending",                 // Google Play
        "org.fdroid.fdroid", "org.fdroid.basic", "com.looker.droidify", "com.machiav3lli.fdroid",
        "dev.imranr.obtainium", "com.aurora.store",
        "com.sec.android.app.samsungapps",     // Galaxy Store
        "com.huawei.appmarket",                // AppGallery
        "com.xiaomi.market", "com.xiaomi.mipicks", "com.miui.packageinstaller.store",
        "com.heytap.market", "com.oppo.market", "com.bbk.appstore",
        "com.amazon.venezia",                  // Amazon Appstore
    )

    class Signals(
        val drawsOver: Boolean,
        val hidden: Boolean,
        val accessibility: Boolean,
        val adLookups: Long,
        val sideloaded: Boolean,
        val daysSinceInstall: Int,
    )

    class Suspect(val pkg: String, val name: String, val score: Int, val reasons: List<String>)

    /** Pure, unit-tested: points + plain-language reasons. */
    fun score(s: Signals): Pair<Int, List<String>> {
        var n = 0
        val r = ArrayList<String>()
        if (s.drawsOver) { n += 2; r += "Can show things on top of other apps — that's how pop-up ads appear" }
        if (s.hidden) { n += 2; r += "Hides its icon, so it's hard to find and remove" }
        if (s.accessibility) { n += 2; r += "Can read and control your screen (Accessibility is on for it)" }
        if (s.adLookups >= 200) { n += 2; r += "Contacted ad servers ${s.adLookups} times this month" }
        else if (s.adLookups >= 50) { n += 1; r += "Contacted ad servers ${s.adLookups} times this month" }
        if (s.sideloaded) { n += 1; r += "Not installed from an app store" }
        if (s.daysSinceInstall in 0..13) {
            n += 1
            r += if (s.daysSinceInstall == 0) "Installed today" else "Installed ${s.daysSinceInstall} day(s) ago"
        }
        return n to r
    }

    /** Ad-server lookups in Noxa's per-app breakdown ("X · Advertising ..."). */
    fun adLookups(counts: Map<String, Long>): Long =
        counts.filterKeys { it.contains("Advertising") }.values.sum()

    /** Scan every app the user installed. Slow-ish: call off the main thread. */
    fun scan(ctx: Context): List<Suspect> {
        val pm = ctx.packageManager
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val a11y = accessibilityPackages(ctx)
        val now = System.currentTimeMillis()
        val out = ArrayList<Suspect>()
        val apps = try { pm.getInstalledApplications(0) } catch (_: Exception) { emptyList<ApplicationInfo>() }
        for (ai in apps) {
            if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue      // phone's own apps
            val pkg = ai.packageName
            if (pkg == ctx.packageName || pkg.startsWith("com.guardian.app")) continue
            val pi = try {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            } catch (_: Exception) { continue }
            val hidden = pm.getLaunchIntentForPackage(pkg) == null &&
                pm.getLeanbackLaunchIntentForPackage(pkg) == null
            val installer = installerOf(pm, pkg)
            val (points, reasons) = score(Signals(
                drawsOver = canDrawOver(ops, ai, pi),
                hidden = hidden,
                accessibility = pkg in a11y,
                adLookups = adLookups(AppStats.companyCounts(pkg)),
                sideloaded = installer == null || installer !in STORES,
                daysSinceInstall = ((now - pi.firstInstallTime) / DAY_MS).toInt(),
            ))
            if (points >= THRESHOLD) {
                val name = try { pm.getApplicationLabel(ai).toString() } catch (_: Exception) { pkg }
                out += Suspect(pkg, name, points, reasons)
            }
        }
        return out.sortedByDescending { it.score }
    }

    /** "Display over other apps" actually allowed (not just asked for). */
    private fun canDrawOver(ops: AppOpsManager?, ai: ApplicationInfo, pi: PackageInfo): Boolean {
        val perms = pi.requestedPermissions ?: return false
        val idx = perms.indexOf(Manifest.permission.SYSTEM_ALERT_WINDOW)
        if (idx < 0) return false
        val mode = try {
            if (Build.VERSION.SDK_INT >= 29)
                ops?.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, ai.uid, ai.packageName)
            else @Suppress("DEPRECATION")
                ops?.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, ai.uid, ai.packageName)
        } catch (_: Exception) { null }
        val granted = (pi.requestedPermissionsFlags?.getOrNull(idx) ?: 0) and
            PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            null, AppOpsManager.MODE_DEFAULT -> granted     // can't tell / default: use the grant
            else -> false
        }
    }

    private fun installerOf(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
    } catch (_: Exception) { null }

    /** Packages with an Accessibility service the user switched on. */
    private fun accessibilityPackages(ctx: Context): Set<String> = try {
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').map { it.substringBefore('/').trim() }.filter { it.isNotEmpty() }.toSet()
    } catch (_: Exception) { emptySet() }
}
