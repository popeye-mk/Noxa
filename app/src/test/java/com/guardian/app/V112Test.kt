package com.guardian.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.12: public Wi-Fi guard, mobile-data blocking, new-app report. */
class V112Test {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test fun publicWifiIsOpenOrHadLoginPage() {
        assertTrue(NetworkWatcher.isPublic(openOrOwe = true, hadLoginPage = false))
        assertTrue(NetworkWatcher.isPublic(openOrOwe = false, hadLoginPage = true))
        assertFalse(NetworkWatcher.isPublic(openOrOwe = false, hadLoginPage = false))   // home WPA2
    }

    @Test fun mobileDataBlockOnlyOnMobileAndOnlyChosenApps() {
        val chosen = { p: String -> p == "com.example.video" }
        assertTrue(AppStats.blockForData("com.example.video", onMobileData = true, chosen = chosen))
        assertFalse(AppStats.blockForData("com.example.video", onMobileData = false, chosen = chosen))  // Wi-Fi: works
        assertFalse(AppStats.blockForData("com.example.other", onMobileData = true, chosen = chosen))
        assertFalse(AppStats.blockForData(AppStats.UNKNOWN, onMobileData = true) { true })              // never the system
    }

    @Test fun newAppReportWindow() {
        val first = now - 30 * day
        assertFalse(HealthMonitor.newAppDue(now - 2 * 3600_000L, first, now))   // too soon
        assertTrue(HealthMonitor.newAppDue(now - day - 1, first, now))          // after a day
        assertFalse(HealthMonitor.newAppDue(now - 4 * day, first, now))         // too old: missed
        assertFalse(HealthMonitor.newAppDue(now - 2 * day, now - day, now))     // before Noxa watched
        assertFalse(HealthMonitor.newAppDue(now - 2 * day, 0L, now))            // never protected
    }

    @Test fun trackerSummaryCountsCompaniesNotOwnBlocks() {
        val (n, companies) = HealthMonitor.trackerSummary(mapOf(
            "Google · Ads" to 10L, "Google · Analytics" to 5L,
            "Meta · Tracking" to 3L, "Blocked by you · Mobile data" to 50L))
        assertEquals(18L, n)
        assertEquals(2, companies)
    }
}
