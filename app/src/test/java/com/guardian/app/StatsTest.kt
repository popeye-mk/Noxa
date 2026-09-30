package com.guardian.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTest {
    @Test fun liveLogKeepsLast300NewestFirst() {
        LiveLog.clear()
        repeat(305) { LiveLog.add("p", "d$it.com", LiveLog.Verdict.ALLOWED) }
        val r = LiveLog.recent()
        assertEquals(300, r.size)
        assertEquals("d304.com", r[0].domain)
        assertEquals("d5.com", r[299].domain)
    }

    @Test fun dailyStatsCountToday() {
        val before = DailyStats.today()
        repeat(5) { DailyStats.recordBlock() }
        assertEquals(before + 5, DailyStats.today())
        val week = DailyStats.lastDays(7)
        assertEquals(7, week.size)
        assertEquals(before + 5, week.last().second)
        assertTrue(week.dropLast(1).all { it.second == 0L })
    }

    @Test fun cleanDomain() {
        assertEquals("ads.example.com", AppStats.cleanDomain("https://Ads.Example.com:443/x?y"))
        assertNull(AppStats.cleanDomain("localhost"))
    }

    @Test fun siteOf() {
        assertEquals("example.com", LiveActivity.siteOf("cdn.example.com"))
        assertEquals("bbc.co.uk", LiveActivity.siteOf("ads.news.bbc.co.uk"))
        assertEquals("bbc.co.uk", LiveActivity.siteOf("bbc.co.uk"))
        assertEquals("x.com.au", LiveActivity.siteOf("a.x.com.au"))
    }

    @Test fun backupExportHasTheUsersLists() {
        val json = org.json.JSONObject(AppStats.exportSettings())
        assertEquals("noxa-settings", json.getString("format"))
        assertTrue(json.has("allowed_sites") && json.has("blocked_sites") && json.has("excluded_apps"))
    }
}
