package com.guardian.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SafetyTest {
    private val asset = listOf("src/main/assets/stalkerware.txt", "app/src/main/assets/stalkerware.txt")
        .map { File(it) }.first { it.exists() }
    private val list = Stalkerware.parse(asset.readText())

    @Test fun bundledStalkerwareListIsSane() {
        assertTrue("expected a few hundred domains, got ${list.size}", list.size in 300..5000)
        assertTrue(list.all { it.contains('.') && it == it.lowercase() })
    }

    @Test fun stalkerwareMatchesParentsButNotNeighbours() {
        val set = setOf("spyapp.example", "abc12.appspot.com")
        assertTrue(Stalkerware.matchesIn(set, "api.spyapp.example"))
        assertTrue(Stalkerware.matchesIn(set, "SPYAPP.example."))
        assertTrue(Stalkerware.matchesIn(set, "abc12.appspot.com"))
        assertFalse(Stalkerware.matchesIn(set, "other.appspot.com"))      // sibling: not a parent match
        assertFalse(Stalkerware.matchesIn(set, "appspot.com"))
        assertFalse(Stalkerware.matchesIn(set, "notspyapp.example"))
        assertFalse(Stalkerware.matchesIn(emptySet(), "spyapp.example"))
    }

    @Test fun bundledListNeverContainsEverydayServices() {
        for (d in listOf("google.com", "facebook.com", "whatsapp.net", "appspot.com", "firebaseio.com", "amazonaws.com"))
            assertFalse(d, list.contains(d))
    }

    @Test fun versionComparison() {
        assertTrue(AppUpdater.isNewer("1.8", "1.7"))
        assertTrue(AppUpdater.isNewer("1.10", "1.9"))
        assertTrue(AppUpdater.isNewer("2.0", "1.99"))
        assertTrue(AppUpdater.isNewer("1.8", "1.7-test"))
        assertFalse(AppUpdater.isNewer("1.7", "1.7"))
        assertFalse(AppUpdater.isNewer("1.7", "1.7-test"))
        assertFalse(AppUpdater.isNewer("1.6", "1.7"))
        assertFalse(AppUpdater.isNewer("v1.8".removePrefix("v"), "1.8"))
        assertEquals(false, AppUpdater.isNewer("", "1.7"))
    }
}

class DnsProvidersTest {
    @Test fun ipv4Validation() {
        assertTrue(DnsProviders.isIpv4("192.168.1.1"))
        assertTrue(DnsProviders.isIpv4("9.9.9.9"))
        assertFalse(DnsProviders.isIpv4("256.1.1.1"))
        assertFalse(DnsProviders.isIpv4("1.1.1"))
        assertFalse(DnsProviders.isIpv4("01.1.1.1"))
        assertFalse(DnsProviders.isIpv4("dns.quad9.net"))
        assertFalse(DnsProviders.isIpv4(""))
    }

    @Test fun customProviderRules() {
        assertTrue(DnsProviders.custom("10.0.0.1", "") != null)
        assertEquals(null, DnsProviders.custom("10.0.0.1", "")!!.doh)
        assertEquals("https://dns.example/dns-query", DnsProviders.custom("10.0.0.1", " https://dns.example/dns-query ")!!.doh)
        assertEquals(null, DnsProviders.custom("10.0.0.1", "http://insecure.example/dns-query"))
        assertEquals(null, DnsProviders.custom("not-an-ip", ""))
    }

    @Test fun builtInsAreDistinctAndEncrypted() {
        assertEquals(DnsProviders.BUILT_IN.size, DnsProviders.BUILT_IN.map { it.id }.toSet().size)
        assertTrue(DnsProviders.BUILT_IN.all { DnsProviders.isIpv4(it.ip) && it.doh!!.startsWith("https://") })
        assertEquals("cloudflare", DnsProviders.current.id)   // default
    }
}

class ThreatsTest {
    private val asset = listOf("src/main/assets/threats.gbf", "app/src/main/assets/threats.gbf")
        .map { File(it) }.first { it.exists() }
    private val main = listOf("src/main/assets/guardian-default.gbf", "app/src/main/assets/guardian-default.gbf")
        .map { File(it) }.first { it.exists() }

    @Test fun bundledThreatFilterLoadsAndIsSubsetOfMain() {
        val t = BloomFilter.load(asset.inputStream())
        val m = BloomFilter.load(main.inputStream())
        assertTrue(t.items in 50_000..2_000_000)
        // everyday sites are never "dangerous"
        for (d in listOf("google.com", "wikipedia.org", "github.com", "bbc.co.uk", "facebook.com"))
            assertFalse(d, t.matchesHostOrParent(d))
        // a few random names: not flagged
        assertFalse(t.matchesHostOrParent("noxa-random-name-9f3a7c.com"))
        assertTrue(m.items > t.items)
    }

    @Test fun alertRateLimits() {
        val now = 1_000_000L
        assertTrue(Threats.shouldAlert("a.scam.example", now))
        assertFalse(Threats.shouldAlert("b.scam.example", now + 1))          // same site within a day
        assertTrue(Threats.shouldAlert("scam2.example", now + 2))
        assertTrue(Threats.shouldAlert("scam3.example", now + 3))
        assertTrue(Threats.shouldAlert("scam4.example", now + 4))
        assertFalse(Threats.shouldAlert("scam5.example", now + 5))           // 4 per hour max
        assertTrue(Threats.shouldAlert("scam5.example", now + 61L * 60 * 1000))
    }

    @Test fun filterAgeDays() {
        val now = 1_700_000_000_000L
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        assertEquals(3, CheckupActivity.filterAgeDays(f.format(java.util.Date(now - 3L * 24 * 3600 * 1000 - 1000)), now))
        assertEquals(0, CheckupActivity.filterAgeDays(f.format(java.util.Date(now)), now))
        assertEquals(-1, CheckupActivity.filterAgeDays("", now))
    }
}

class SelfHealTest {
    @Test fun backsOffThenStops() {
        val now = 1_700_000_000_000L
        assertEquals(3_000L, GuardianVpnService.nextHealDelay(now))
        assertEquals(10_000L, GuardianVpnService.nextHealDelay(now + 1))
        assertEquals(30_000L, GuardianVpnService.nextHealDelay(now + 2))
        assertEquals(120_000L, GuardianVpnService.nextHealDelay(now + 3))
        assertEquals(null, GuardianVpnService.nextHealDelay(now + 4))     // hands over to the watchdog
        assertEquals(null, GuardianVpnService.nextHealDelay(now + 5))     // and stays handed over
    }
}
