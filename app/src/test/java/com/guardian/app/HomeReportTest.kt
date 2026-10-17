package com.guardian.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.14 "Report to my home server": the pure rules, without Android. */
class HomeReportTest {
    private val hour = 60L * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test fun hourlyAndNotTwoTriesWithinTenMinutes() {
        assertTrue(HomeReport.due(now, 0L, 0L))                              // never sent
        assertFalse(HomeReport.due(now, now - 30 * 60 * 1000, 0L))           // sent half an hour ago
        assertTrue(HomeReport.due(now, now - hour, now - 11 * 60 * 1000))
        assertFalse(HomeReport.due(now, now - hour, now - 5 * 60 * 1000))    // just failed: wait
    }

    @Test fun onlyAHomeAddressIsAccepted() {
        val t = HomeReport.parseAddress(" 192.168.1.20 ")!!
        assertEquals("192.168.1.20", t.host); assertEquals(HomeReport.DEFAULT_PORT, t.port)
        assertEquals(9000, HomeReport.parseAddress("10.0.0.5:9000")!!.port)
        assertEquals(172, HomeReport.parseAddress("172.20.0.9")!!.bytes[0].toInt() and 0xFF)
        for (bad in listOf("8.8.8.8", "fritz.box", "homeserver.local", "192.168.1.300", "127.0.0.1", "169.254.1.1",
                "192.168.1.1:70000", "192.168.1.1:0", "http://192.168.1.1", "", "192.168.1"))
            assertNull(bad, HomeReport.parseAddress(bad))
    }

    @Test fun keyIsOnePlainLine() {
        assertTrue(HomeReport.keyOk("home-Abc123xyz"))
        assertFalse(HomeReport.keyOk("short"))
        assertFalse(HomeReport.keyOk("has space inside"))
        assertFalse(HomeReport.keyOk("evil\r\nX-Other: header"))            // no header injection
        assertFalse(HomeReport.keyOk("k".repeat(201)))
    }

    @Test fun labelsBecomeCategories() {
        assertEquals("Advertising", HomeReport.category("Google · Advertising"))
        assertEquals("Advertising", HomeReport.category("Google · Advertising (apps)"))
        assertEquals("Analytics", HomeReport.category("Hotjar · Session recording"))
        assertEquals("Analytics", HomeReport.category("Datadog · Monitoring"))
        assertEquals("Marketing", HomeReport.category("OneSignal · Push/Marketing"))
        assertEquals("Tracking", HomeReport.category("AppsFlyer · Attribution"))
        assertEquals("Tracking", HomeReport.category("example.com · Tracking"))
        assertEquals("Dangerous site", HomeReport.category(Threats.LABEL))
        assertEquals("Dangerous site", HomeReport.category(FakeSites.LABEL))
        assertEquals("Stalkerware", HomeReport.category(Stalkerware.LABEL))
        assertEquals("Blocked by you", HomeReport.category("Blocked by you · Firewall"))
        assertEquals("Router attack shield", HomeReport.category(RebindShield.LABEL))
        assertEquals("Other", HomeReport.category("odd"))
        val c = HomeReport.categories(mapOf("Google · Advertising" to 5L, "Meta · Advertising" to 2L, Threats.LABEL to 1L))
        assertEquals(7L, c["Advertising"]); assertEquals(1L, c["Dangerous site"])
    }

    @Test fun sinceLastReportAndTheThirtyDayReset() {
        val snap = mapOf("Google · Advertising" to 100L, "Meta · Advertising" to 10L)
        assertEquals(mapOf("Google · Advertising" to 20L, "Hotjar · Session recording" to 3L),
            HomeReport.delta(mapOf("Google · Advertising" to 120L, "Meta · Advertising" to 10L, "Hotjar · Session recording" to 3L), snap))
        // Noxa reset its totals: everything is new again
        assertEquals(mapOf("Google · Advertising" to 4L), HomeReport.delta(mapOf("Google · Advertising" to 4L), snap))
        assertEquals(emptyMap<String, Long>(), HomeReport.delta(snap, snap))
    }

    @Test fun topCompaniesLeaveTheWarningsOut() {
        val top = HomeReport.topCompanies(mapOf("Google · Advertising" to 5L, "Google · Analytics" to 4L,
            "Meta · Advertising" to 6L, Threats.LABEL to 50L, "Blocked by you · Site" to 40L), n = 2)
        assertEquals(listOf("Google" to 9L, "Meta" to 6L), top)
    }

    private fun facts() = HomeReport.Facts(
        id = "11111111-2222-3333-4444-555555555555", name = "Kids tablet", model = "Lenovo TB-X606F", android = "11",
        version = "1.14", protectionOn = true, alwaysOn = false, builtAt = "2026-10-12 04:41:09", stopsToday = 0,
        today = 123L, days = listOf(10L, 20L, 30L, 40L, 50L, 60L, 123L),
        since = mapOf("Google · Advertising" to 30L, "Meta · Advertising" to 12L, "TikTok · Analytics" to 9L, Threats.LABEL to 2L),
        dangerApps = listOf(HomeReport.App("Chrome", 2L, true)), spyApps = emptyList(), sentAt = now)

    @Test fun theReportHoldsCountsAndWarningsAndNeverSites() {
        val json = HomeReport.payload(facts())
        assertEquals("noxa-home-report", json.getString("format"))
        assertEquals("Kids tablet", json.getJSONObject("device").getString("name"))
        assertTrue(json.getJSONObject("protection").getBoolean("on"))
        assertEquals(123L, json.getLong("blocked_today"))
        assertEquals(7, json.getJSONArray("blocked_days").length())
        val since = json.getJSONObject("since_last")
        assertEquals(53L, since.getLong("total"))
        assertEquals(42L, since.getJSONObject("categories").getLong("Advertising"))
        assertEquals(2L, since.getJSONObject("categories").getLong("Dangerous site"))
        assertEquals("Google", since.getJSONArray("top_companies").getJSONArray(0).getString(0))
        val chrome = json.getJSONObject("apps_30d").getJSONArray("dangerous_site").getJSONArray(0)
        assertEquals("Chrome", chrome.getString(0)); assertTrue(chrome.getBoolean(2))   // a browser: normal browsing
        val text = json.toString()
        for (never in listOf("domain", "site_list", "visited", "example.com", "allowed"))
            assertFalse(never, text.contains(never))
        assertTrue(text.toByteArray().size < HomeReport.MAX_BODY)
    }

    @Test fun theRequestIsOneShotHttpWithTheKeyInAHeader() {
        val body = "{}".toByteArray()
        val req = String(HomeReport.request("192.168.1.20", 8790, "home-Abc123xyz", body), Charsets.ISO_8859_1)
        assertTrue(req.startsWith("POST ${HomeReport.PATH} HTTP/1.1\r\nHost: 192.168.1.20:8790\r\n"))
        assertTrue(req.contains("\r\n${HomeReport.HEADER}: home-Abc123xyz\r\n"))
        assertTrue(req.contains("\r\nContent-Length: 2\r\n"))
        assertTrue(req.contains("\r\nConnection: close\r\n"))
        assertTrue(req.endsWith("\r\n\r\n{}"))
    }

    @Test fun theReplyStatusIsRead() {
        assertEquals(200, HomeReport.statusOf("HTTP/1.0 200 OK\r\nContent-Type: application/json\r\n\r\n{\"ok\": true}"))
        assertEquals(401, HomeReport.statusOf("HTTP/1.1 401 Unauthorized\r\n\r\n"))
        assertEquals(-1, HomeReport.statusOf("SSH-2.0-OpenSSH\r\n"))
        assertEquals("{\"ok\": true}", HomeReport.bodyOf("HTTP/1.0 200 OK\r\nX: y\r\n\r\n{\"ok\": true}"))
        assertTrue(JSONObject(HomeReport.bodyOf("HTTP/1.0 200 OK\r\n\r\n{\"ok\": true}")).getBoolean("ok"))
    }
}
