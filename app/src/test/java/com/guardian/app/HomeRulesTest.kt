package com.guardian.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.14 rules from the home server: the pure parts. */
class HomeRulesTest {
    @Test fun rulesAreReadStrictly() {
        val r = HomeRules.parse(JSONObject("""{"blocked_sites": ["TikTok.com", "https://twitch.tv/x", "nope", "tiktok.com"],
            "family_filter": true, "safe_search": null, "bedtime": {"on": true, "start": "21:00", "end": "07:00", "all": false, "apps": ["com.a.b"]}}"""))
        assertEquals(listOf("tiktok.com", "twitch.tv"), r.blockedSites)
        assertEquals(true, r.familyFilter)
        assertNull(r.safeSearch)                                   // null = keep the device's own setting
        assertEquals("21:00", r.bedtime!!.optString("start"))
        val none = HomeRules.parse(null)
        assertTrue(none.blockedSites.isEmpty() && none.familyFilter == null && none.bedtime == null)
        assertNull(HomeRules.parse(JSONObject("""{"bedtime": {"on": true}}""")).bedtime)   // no hours: no rule
    }

    @Test fun aParentDomainCounts() {
        val set = setOf("tiktok.com", "bad-coin.example")
        assertTrue(HomeRules.matches(set, "www.tiktok.com"))
        assertTrue(HomeRules.matches(set, "TikTok.com."))
        assertFalse(HomeRules.matches(set, "nottiktok.com"))
        assertFalse(HomeRules.matches(emptySet(), "tiktok.com"))
        assertEquals("Blocked by you", HomeReport.category(HomeRules.LABEL))
    }
}
