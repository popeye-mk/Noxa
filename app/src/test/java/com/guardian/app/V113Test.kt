package com.guardian.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.13: router attack shield, link check, spy-app watch. */
class V113Test {
    private fun ip(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    /** A DNS response for example.com with the given A/AAAA answers. */
    private fun response(vararg addrs: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, addrs.size.toByte(), 0, 0, 0, 0))
        out.write(byteArrayOf(7) + "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0, 0, 1, 0, 1))
        // first a CNAME record (must be skipped), pointing back at the question name
        for (a in addrs) {
            val type = if (a.size == 4) 1 else 28
            out.write(byteArrayOf(0xC0.toByte(), 12, 0, type.toByte(), 0, 1, 0, 0, 0, 60, 0, a.size.toByte()))
            out.write(a)
        }
        return out.toByteArray()
    }

    @Test fun answerAddressesReadsAandAAAA() {
        val v6 = ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[15] = 1 }
        val r = response(ip(93, 184, 216, 34), v6)
        val got = DnsPacket.answerAddresses(r, r.size)
        assertEquals(2, got.size)
        assertTrue(got[0].contentEquals(ip(93, 184, 216, 34)))
        assertTrue(got[1].contentEquals(v6))
    }

    @Test fun privateRanges() {
        assertTrue(RebindShield.isPrivate(ip(192, 168, 1, 1)))
        assertTrue(RebindShield.isPrivate(ip(10, 0, 0, 5)))
        assertTrue(RebindShield.isPrivate(ip(172, 20, 0, 1)))
        assertTrue(RebindShield.isPrivate(ip(127, 0, 0, 1)))
        assertTrue(RebindShield.isPrivate(ip(169, 254, 1, 1)))
        assertFalse(RebindShield.isPrivate(ip(172, 32, 0, 1)))
        assertFalse(RebindShield.isPrivate(ip(0, 0, 0, 0)))        // a resolver's "blocked" answer
        assertFalse(RebindShield.isPrivate(ip(100, 100, 1, 1)))    // Tailscale / carrier NAT
        assertFalse(RebindShield.isPrivate(ip(8, 8, 8, 8)))
        assertTrue(RebindShield.isPrivate(ByteArray(16).also { it[15] = 1 }))                         // ::1
        assertTrue(RebindShield.isPrivate(ByteArray(16).also { it[0] = 0xFD.toByte() }))              // fd00::
        assertTrue(RebindShield.isPrivate(ByteArray(16).also { it[0] = 0xFE.toByte(); it[1] = 0x80.toByte() }))
        assertFalse(RebindShield.isPrivate(ByteArray(16)))                                            // ::
        val mapped = ByteArray(16).also { it[10] = -1; it[11] = -1; it[12] = 192.toByte(); it[13] = 168.toByte(); it[15] = 1 }
        assertTrue(RebindShield.isPrivate(mapped))
    }

    @Test fun rebindRefusedExceptLocalNames() {
        val home = response(ip(192, 168, 1, 1))
        assertTrue(RebindShield.refuse("evil.example.com", home, home.size))
        assertFalse(RebindShield.refuse("fritz.box", home, home.size))
        assertFalse(RebindShield.refuse("abc123.plex.direct", home, home.size))
        assertFalse(RebindShield.refuse("printer.local", home, home.size))
        assertFalse(RebindShield.refuse("router", home, home.size))
        val pub = response(ip(93, 184, 216, 34))
        assertFalse(RebindShield.refuse("example.com", pub, pub.size))
    }

    @Test fun linkExtraction() {
        assertEquals("https://paypa1.com/login", LinkCheck.findLink("Your account is locked https://paypa1.com/login now"))
        assertEquals("dhl-parcel.top/track", LinkCheck.findLink("Parcel waiting: dhl-parcel.top/track"))
        assertNull(LinkCheck.findLink("hello there, no link"))
        assertEquals("paypa1.com" to false, LinkCheck.hostOf("https://PayPa1.com/login"))
        assertEquals("evil.xyz" to true, LinkCheck.hostOf("https://paypal.com@evil.xyz/x"))
        assertEquals("shop.example.com" to false, LinkCheck.hostOf("shop.example.com:8080/a?b#c"))
        assertNull(LinkCheck.hostOf("https://localhost/"))
    }

    @Test fun linkVerdicts() {
        fun j(host: String, disguised: Boolean = false, blocked: Boolean = false, allowed: Boolean = false,
              spy: Boolean = false, danger: Boolean = false, fake: String? = null,
              tracker: Boolean = false, risky: String? = null) =
            LinkCheck.judge(host, disguised, blocked, allowed, spy, danger, fake, tracker, risky).level
        assertEquals(LinkCheck.Level.DANGER, j("paypa1.com", fake = "PayPal"))
        assertEquals(LinkCheck.Level.DANGER, j("evil.xyz", disguised = true))
        assertEquals(LinkCheck.Level.DANGER, j("x.com", spy = true, allowed = true))     // spyware beats allow
        assertEquals(LinkCheck.Level.CAUTION, j("bit.ly"))
        assertEquals(LinkCheck.Level.CAUTION, j("1.2.3.4"))
        assertEquals(LinkCheck.Level.CAUTION, j("win.top", risky = "top"))
        assertEquals(LinkCheck.Level.CAUTION, j("doubleclick.net", tracker = true))
        assertEquals(LinkCheck.Level.OK, j("wikipedia.org"))
    }

    @Test fun spyAppRule() {
        val me = "com.guardian.app"
        assertTrue(SpyAppWatch.suspicious("com.evil.flash", true, deviceAdmin = true, fromStore = true, system = false, self = me))
        assertTrue(SpyAppWatch.suspicious("com.evil.flash", true, deviceAdmin = false, fromStore = false, system = false, self = me))
        assertFalse(SpyAppWatch.suspicious("com.store.app", true, deviceAdmin = false, fromStore = true, system = false, self = me))
        assertFalse(SpyAppWatch.suspicious("com.x8bit.bitwarden", true, true, false, false, me))   // password manager
        assertFalse(SpyAppWatch.suspicious("com.oem.thing", true, true, false, system = true, self = me))
        assertFalse(SpyAppWatch.suspicious("com.evil.flash", false, true, false, false, me))       // no accessibility
        assertEquals(setOf("com.a", "com.b"),
            SpyAppWatch.accessibilityPackages("com.a/.Svc:com.b/com.b.Other"))
        assertTrue(SpyAppWatch.accessibilityPackages(null).isEmpty())
    }
}
