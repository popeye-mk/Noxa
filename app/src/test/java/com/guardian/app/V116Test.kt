package com.guardian.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.16 (security review of 19 Nov 2026): the pure rules behind the new defences. */
class V116Test {
    private val day = 24L * 60 * 60 * 1000

    // ---- device hygiene -------------------------------------------------------
    @Test fun patchAge() {
        val now = 1_795_000_000_000L                                   // 18 Nov 2026
        assertTrue(DeviceHealth.patchAgeDays("2026-11-01", now) in 10..25)
        assertTrue(DeviceHealth.patchOld(DeviceHealth.patchAgeDays("2023-05-01", now)))
        assertFalse(DeviceHealth.patchOld(DeviceHealth.patchAgeDays("2026-06-01", now)))
        assertEquals(-1, DeviceHealth.patchAgeDays("", now))
        assertEquals(-1, DeviceHealth.patchAgeDays("unknown", now))
        assertFalse(DeviceHealth.patchOld(-1))                         // unknown is not "old"
        assertEquals(0, DeviceHealth.patchAgeDays("2030-01-01", now))  // a clock that is behind
    }

    @Test fun onlyAddedCertificatesCount() {
        assertEquals(2, DeviceHealth.countUserCerts(listOf("system:a.0", "user:b.0", "system:c.0", "user:d.0")))
        assertEquals(0, DeviceHealth.countUserCerts(listOf("system:a.0")))
    }

    @Test fun privateDnsBypassesOnlyWithAProviderName() {
        assertTrue(DeviceHealth.bypassesNoxa("dns.example.net"))
        assertFalse(DeviceHealth.bypassesNoxa(null))
        assertFalse(DeviceHealth.bypassesNoxa(""))
    }

    // ---- firmware watch -------------------------------------------------------
    @Test fun threeDifferentDaysInTwoWeeks() {
        val today = 20_000L
        assertFalse(FirmwareWatch.suspicious(listOf(today), today))
        assertFalse(FirmwareWatch.suspicious(listOf(today - 1, today), today))
        assertTrue(FirmwareWatch.suspicious(listOf(today - 9, today - 3, today), today))
        assertFalse(FirmwareWatch.suspicious(listOf(today - 20, today - 16, today), today))   // too long ago
    }

    @Test fun daysAreKeptOnceAndForgottenAfterAMonth() {
        val today = 20_000L
        assertEquals(listOf(today - 2, today), FirmwareWatch.note(listOf(today - 2, today), today))
        assertEquals(listOf(today - 29, today), FirmwareWatch.note(listOf(today - 40, today - 29), today))
    }

    @Test fun toldAgainOnlyAfterAWeek() {
        val now = 1_795_000_000_000L
        assertTrue(FirmwareWatch.shouldTell(0L, now))
        assertFalse(FirmwareWatch.shouldTell(now - 6 * day, now))
        assertTrue(FirmwareWatch.shouldTell(now - 7 * day, now))
    }

    // ---- parent lock ----------------------------------------------------------
    @Test fun slowHashIsSaltedAndNeverThePin() {
        val h = ParentLock.slowHash("salt1", "1234")
        assertEquals(64, h.length)
        assertEquals(h, ParentLock.slowHash("salt1", "1234"))
        assertNotEquals(h, ParentLock.slowHash("salt2", "1234"))
        assertNotEquals(h, ParentLock.slowHash("salt1", "1235"))
        assertNotEquals(h, ParentLock.hash("salt1", "1234"))           // not the old fast form
    }

    @Test fun lockoutsGrow() {
        assertEquals(ParentLock.LOCKOUT_MS, ParentLock.lockoutFor(1))
        assertEquals(5 * ParentLock.LOCKOUT_MS, ParentLock.lockoutFor(2))
        assertEquals(30 * ParentLock.LOCKOUT_MS, ParentLock.lockoutFor(3))
        assertEquals(120 * ParentLock.LOCKOUT_MS, ParentLock.lockoutFor(9))
    }

    @Test fun aRestartStartsTheWaitAgainAndTheClockCantSkipIt() {
        val ms = 60_000L
        assertEquals(0L, ParentLock.lockLeft(0L, 0L, true, 5_000L))                 // no lockout
        assertEquals(40_000L, ParentLock.lockLeft(100_000L, ms, true, 60_000L))     // 40 s left
        assertEquals(0L, ParentLock.lockLeft(100_000L, ms, true, 100_001L))         // over
        assertEquals(ms, ParentLock.lockLeft(100_000L, ms, false, 3_000L))          // restarted: all of it again
        assertEquals(ms, ParentLock.lockLeft(900_000L, ms, true, 1_000L))           // never longer than the lockout
    }

    // ---- plain-DNS replies ----------------------------------------------------
    private fun message(name: String, id: Int = 0x1234, reply: Boolean = false, qd: Int = 1, rcode: Int = 0): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), (if (reply) 0x81 else 1).toByte(),
            (0x80 or rcode).toByte(), 0, qd.toByte(), 0, 0, 0, 0, 0, 0))
        if (qd > 0) {
            for (label in name.split('.')) { out.write(label.length); out.write(label.toByteArray()) }
            out.write(byteArrayOf(0, 0, 1, 0, 1))
        }
        return out.toByteArray()
    }

    @Test fun aReplyMustAnswerTheQuestionAsked() {
        val q = message("example.com")
        val good = message("example.com", reply = true)
        assertTrue(DnsPacket.sameQuestion(q, good, good.size))
        val mixedCase = message("ExAmple.COM", reply = true)
        assertTrue(DnsPacket.sameQuestion(q, mixedCase, mixedCase.size))
        val other = message("examp1e.com", reply = true)
        assertFalse(DnsPacket.sameQuestion(q, other, other.size))
        val longer = message("example.com.evil.net", reply = true)
        assertFalse(DnsPacket.sameQuestion(q, longer, longer.size))
        assertFalse(DnsPacket.sameQuestion(q, good, 14))                            // cut short
        val bareError = message("", reply = true, qd = 0, rcode = 2)                // SERVFAIL without a question
        assertTrue(DnsPacket.sameQuestion(q, bareError, bareError.size))
        val bareOk = message("", reply = true, qd = 0, rcode = 0)                   // "fine", but answers nothing
        assertFalse(DnsPacket.sameQuestion(q, bareOk, bareOk.size))
    }

    // ---- the packet parser never throws ---------------------------------------
    @Test fun randomAndMangledPacketsNeverCrashTheParser() {
        val rnd = java.util.Random(20261119L)
        val real = TestPackets.ipv4Query("ads.example.com", 0x1234)
        repeat(20_000) { i ->
            val p = if (i % 2 == 0) ByteArray(rnd.nextInt(120)).also { rnd.nextBytes(it) }
                    else real.copyOf().also { b -> repeat(1 + rnd.nextInt(4)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() } }
            val len = if (p.isEmpty()) 0 else rnd.nextInt(p.size + 1)
            DnsPacket.parseQuery(p, len)
            DnsPacket.isTcpDns(p, len)
            DnsPacket.cnameTargets(p, len)
            DnsPacket.answerAddresses(p, len)
            DnsPacket.sameQuestion(real, p, len)
        }
    }

    // ---- the home report: only added keys --------------------------------------
    @Test fun theReportGainsKeysAndLosesNone() {
        val base = HomeReport.Facts("id", "Tablet", "M", "11", "1.16", true, null, "2026-11-16 04:27:19", 0,
            5L, listOf(1L, 2L), emptyMap(), emptyList(), emptyList(), 1_795_000_000_000L)
        val old = HomeReport.payload(base)
        assertFalse(old.has("device_health"))
        assertFalse(old.getJSONObject("protection").has("taken_away_at"))
        assertEquals(0, old.getJSONObject("apps_30d").getJSONArray("firmware_suspects").length())
        val full = HomeReport.payload(HomeReport.Facts("id", "Tablet", "M", "11", "1.16", true, null, "2026-11-16 04:27:19", 0,
            5L, listOf(1L, 2L), emptyMap(), emptyList(), emptyList(), 1_795_000_000_000L,
            health = HomeReport.Health(true, "2023-05-01", 1297, 1, false, false),
            takenAwayAt = 1_794_999_000_000L,
            firmware = listOf(HomeReport.App("System Service", 4L, false)), catchOwnDns = true))
        for (k in listOf("format", "version", "sent_at", "device", "protection", "blocked_today", "blocked_days",
                         "filters", "since_last", "apps_30d")) assertTrue(k, full.has(k))
        val h = full.getJSONObject("device_health")
        assertTrue(h.getBoolean("private_dns_bypass")); assertEquals("2023-05-01", h.getString("security_patch"))
        assertEquals(1, h.getInt("user_certificates"))
        assertEquals(1_794_999_000L, full.getJSONObject("protection").getLong("taken_away_at"))
        assertTrue(full.getJSONObject("protection").getBoolean("catches_own_dns"))
        val f = full.getJSONObject("apps_30d").getJSONArray("firmware_suspects").getJSONArray(0)
        assertEquals("System Service", f.getString(0)); assertEquals(4L, f.getLong(1))
        assertNull(old.opt("device_health"))
    }
}
