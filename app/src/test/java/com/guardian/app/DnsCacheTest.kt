package com.guardian.app

import com.guardian.app.TestPackets.ipv4Query
import com.guardian.app.TestPackets.reply
import com.guardian.app.TestPackets.u32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsCacheTest {
    private val pkt = ipv4Query("eXample.com", 0x7777)
    private val q = DnsPacket.parseQuery(pkt, pkt.size)!!
    private val payload = DnsPacket.extractUdpPayload(pkt, pkt.size)!!

    @Test fun missBeforePut() {
        assertNull(DnsCache().get(q, payload, 0))
    }

    @Test fun hitIsRewrittenForTheNewQuery() {
        val c = DnsCache()
        c.put(q, reply("Example.com", 86400), now = 0)
        val hit = c.get(q, payload, now = 10_000)!!
        assertEquals(0x77.toByte(), hit[0]); assertEquals(0x77.toByte(), hit[1])   // transaction ID
        assertArrayEquals(payload.copyOfRange(12, 25), hit.copyOfRange(12, 25))    // app's own casing
        assertEquals(290L, u32(hit, hit.size - 10))          // TTL capped at 300, 10 s elapsed
    }

    @Test fun expiresAfterCap() {
        val c = DnsCache()
        c.put(q, reply("example.com", 86400), now = 0)
        assertNotNull(c.get(q, payload, now = 299_000))
        assertNull(c.get(q, payload, now = 300_000))
    }

    @Test fun servfailAndZeroTtlNotCached() {
        val c = DnsCache()
        val bad = reply("example.com", 60); bad[3] = 0x82.toByte()   // RCODE 2 = SERVFAIL
        c.put(q, bad, now = 0)
        assertNull(c.get(q, payload, now = 1))
        c.put(q, reply("example.com", 0), now = 0)
        assertNull(c.get(q, payload, now = 1))
    }

    @Test fun truncatedReplyNotCached() {
        val r = reply("example.com", 60); r[2] = (r[2].toInt() or 0x02).toByte()   // TC bit
        assertNull(DnsCache.cacheTtlSeconds(r))
    }

    @Test fun cachedAnswerWrapsIntoAPacket() {
        val c = DnsCache()
        c.put(q, reply("example.com", 60), now = 0)
        val hit = c.get(q, payload, now = 1)!!
        val fwd = DnsPacket.buildForwardedResponse(pkt, pkt.size, hit, hit.size)
        assertNotNull(fwd); assertEquals(28 + hit.size, fwd!!.size)
    }

    @Test fun lruEvictsBeyondCapacity() {
        val c = DnsCache(capacity = 2)
        for (n in listOf("a.com", "b.com", "c.com")) {
            val p = ipv4Query(n, 1)
            c.put(DnsPacket.parseQuery(p, p.size)!!, reply(n, 60), now = 0)
        }
        val pa = ipv4Query("a.com", 1)
        assertNull(c.get(DnsPacket.parseQuery(pa, pa.size)!!, DnsPacket.extractUdpPayload(pa, pa.size)!!, 1))
        val pc = ipv4Query("c.com", 1)
        assertTrue(c.get(DnsPacket.parseQuery(pc, pc.size)!!, DnsPacket.extractUdpPayload(pc, pc.size)!!, 1) != null)
    }
}
