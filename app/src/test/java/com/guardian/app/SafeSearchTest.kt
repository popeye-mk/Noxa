package com.guardian.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.14 safe search: the host mapping and the CNAME answer. */
class SafeSearchTest {
    @Test fun searchSitesMapToTheirSafeHost() {
        for (g in listOf("www.google.com", "www.google.be", "www.google.co.uk", "google.com", "google.co.uk", "encrypted.google.com"))
            assertEquals(g, "forcesafesearch.google.com", SafeSearch.targetFor(g))
        assertEquals("restrict.youtube.com", SafeSearch.targetFor("m.youtube.com"))
        assertEquals("restrict.youtube.com", SafeSearch.targetFor("youtubei.googleapis.com"))
        assertEquals("strict.bing.com", SafeSearch.targetFor("www.bing.com"))
        assertEquals("safe.duckduckgo.com", SafeSearch.targetFor("duckduckgo.com"))
        assertEquals("familysearch.yandex.ru", SafeSearch.targetFor("yandex.ru"))
        for (n in listOf("mail.google.com", "www.googleapis.com", "fonts.gstatic.com", "example.com", "www.google.com.evil.example", "cdn.youtube.com"))
            assertNull(n, SafeSearch.targetFor(n))
    }

    @Test fun theUpstreamQueryIsAPlainQuestion() {
        val q = DnsPacket.buildQuery(0x1234, "forcesafesearch.google.com", 28)
        assertEquals(0x12.toByte(), q[0]); assertEquals(0x34.toByte(), q[1])
        assertEquals(1, q[5].toInt())                                      // QDCOUNT
        assertEquals(12 + 28 + 4, q.size)                                  // header + name + type/class
        assertEquals(28, q[q.size - 3].toInt())                            // AAAA
        // wrapped as a packet, Noxa's own parser reads it back
        val udpLen = 8 + q.size; val total = 20 + udpLen
        val ip = byteArrayOf(0x45, 0, (total shr 8).toByte(), total.toByte(), 0, 0, 0, 0, 64, 17, 0, 0, 10, 0, 0, 2, 10, 111, 0, 2)
        val udp = byteArrayOf(0x9C.toByte(), 0x40, 0, 53, (udpLen shr 8).toByte(), udpLen.toByte(), 0, 0)
        val p = ip + udp + q
        val parsed = DnsPacket.parseQuery(p, p.size)!!
        assertEquals("forcesafesearch.google.com", parsed.domain); assertEquals(28, parsed.qtype)
    }

    @Test fun theAnswerIsACnamePlusTheSafeHostsAddresses() {
        val packet = TestPackets.ipv4Query("www.google.com", 0x4242)
        val q = DnsPacket.parseQuery(packet, packet.size)!!
        val upstream = TestPackets.reply("forcesafesearch.google.com", 300)        // A 9.9.9.9
        val out = DnsPacket.buildCnameResponse(packet, packet.size, q, "forcesafesearch.google.com", upstream, upstream.size)!!
        val dns = out.copyOfRange(28, out.size)                                  // past IPv4 + UDP headers
        assertEquals(0x42.toByte(), dns[0]); assertEquals(0x42.toByte(), dns[1])  // the app's transaction id
        assertEquals(0x81.toByte(), dns[2]); assertEquals(0x80.toByte(), dns[3])  // a normal answer, NOERROR
        assertEquals(2, dns[7].toInt())                                          // CNAME + one address
        assertArrayEquals(packet.copyOfRange(28 + 12, packet.size), dns.copyOfRange(12, 12 + packet.size - 40))  // question untouched
        assertEquals(listOf("forcesafesearch.google.com"), DnsPacket.cnameTargets(dns, dns.size))
        val addrs = DnsPacket.answerAddresses(dns, dns.size)
        assertEquals(1, addrs.size); assertArrayEquals(byteArrayOf(9, 9, 9, 9), addrs[0])
        assertTrue(out.size == 28 + dns.size)
    }

    @Test fun noUsableAddressMeansNoSafeAnswer() {
        val packet = TestPackets.ipv4Query("www.google.com", 1)
        val q = DnsPacket.parseQuery(packet, packet.size)!!
        val aaaaWanted = q.copy(qtype = 28)
        val upstreamA = TestPackets.reply("forcesafesearch.google.com", 60)
        assertNull(DnsPacket.buildCnameResponse(packet, packet.size, aaaaWanted, "forcesafesearch.google.com", upstreamA, upstreamA.size))
        val empty = byteArrayOf(0, 1, 0x81.toByte(), 0x83.toByte(), 0, 1, 0, 0, 0, 0, 0, 0) + TestPackets.qname("x.example") + byteArrayOf(0, 1, 0, 1)
        assertNull(DnsPacket.buildCnameResponse(packet, packet.size, q, "forcesafesearch.google.com", empty, empty.size))
        assertNull(DnsPacket.buildCnameResponse(packet, packet.size, q.copy(qtype = 65), "forcesafesearch.google.com", upstreamA, upstreamA.size))
    }
}
