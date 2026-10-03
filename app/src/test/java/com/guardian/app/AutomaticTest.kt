package com.guardian.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.11 automatic features. */
class AutomaticTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test fun batteryProvenOnlyAfterThreeCleanDays() {
        assertFalse(HealthMonitor.batteryProvenOk(0L, 0L, now))                    // never protected
        assertFalse(HealthMonitor.batteryProvenOk(now - 2 * day, 0L, now))         // too new
        assertTrue(HealthMonitor.batteryProvenOk(now - 3 * day, 0L, now))          // 3 days, no kill
        assertFalse(HealthMonitor.batteryProvenOk(now - 10 * day, now - day, now)) // killed yesterday
        assertTrue(HealthMonitor.batteryProvenOk(now - 10 * day, now - 4 * day, now))
    }

    @Test fun killListParsing() {
        assertEquals(listOf(1L, 2L, 3L), HealthMonitor.parseKills("1,2, 3,,x"))
        assertEquals(emptyList<Long>(), HealthMonitor.parseKills(""))
    }

    @Test fun fallbackRotatesAndNeverStaysOnTheFailingOne() {
        assertEquals("quad9", DnsProviders.nextAfter("cloudflare").id)
        assertEquals("cloudflare", DnsProviders.nextAfter("google").id)              // wraps
        assertEquals("cloudflare", DnsProviders.nextAfter(DnsProviders.CUSTOM_ID).id) // own server -> first built-in
        DnsProviders.clearFallback()
        assertEquals("cloudflare", DnsProviders.active.id)
        val f = DnsProviders.failover(now)
        assertNotNull(f); assertEquals("quad9", DnsProviders.active.id)
        assertNull(DnsProviders.failover(now + 1000))                               // not again within 2 min
        assertNotNull(DnsProviders.fallbackInfo())
        DnsProviders.clearFallback()
        assertEquals("cloudflare", DnsProviders.active.id)
    }

    @Test fun speedTestQueryIsAValidDnsQuestion() {
        val q = DnsSpeed.query("example.com", 0x1234)
        assertEquals(0x12.toByte(), q[0]); assertEquals(0x34.toByte(), q[1])
        assertEquals(1, q[5].toInt())                                   // QDCOUNT = 1
        assertEquals(12 + 13 + 4, q.size)                               // header + qname + type/class
        // wrapped in an IPv4/UDP packet, Noxa's own parser reads it back
        val dns = q
        val udpLen = 8 + dns.size; val total = 20 + udpLen
        val ip = byteArrayOf(0x45, 0, (total shr 8).toByte(), total.toByte(), 0, 0, 0, 0, 64, 17, 0, 0,
            10, 0, 0, 2, 10, 111, 0, 2)
        val udp = byteArrayOf(0x9C.toByte(), 0x40, 0, 53, (udpLen shr 8).toByte(), udpLen.toByte(), 0, 0)
        val p = ip + udp + dns
        assertEquals("example.com", DnsPacket.parseQuery(p, p.size)!!.domain)
    }
}
