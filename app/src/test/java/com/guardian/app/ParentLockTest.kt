package com.guardian.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.14 parent lock: the pure rules. */
class ParentLockTest {
    @Test fun pinIsFourToEightDigits() {
        assertTrue(ParentLock.pinOk("1234")); assertTrue(ParentLock.pinOk("12345678"))
        assertFalse(ParentLock.pinOk("123")); assertFalse(ParentLock.pinOk("123456789"))
        assertFalse(ParentLock.pinOk("12a4")); assertFalse(ParentLock.pinOk(""))
    }

    @Test fun theStoredFormIsSaltedAndNeverThePin() {
        val h = ParentLock.hash("salt1", "1234")
        assertEquals(64, h.length)
        assertEquals(h, ParentLock.hash("salt1", "1234"))
        assertNotEquals(h, ParentLock.hash("salt2", "1234"))
        assertNotEquals(h, ParentLock.hash("salt1", "1235"))
        assertFalse(h.contains("1234"))
    }

    @Test fun stopAndPauseNeedTheAppsUnlock() {
        assertTrue(ParentLock.allows(locked = false, unlocked = false))
        assertTrue(ParentLock.allows(locked = true, unlocked = true))
        assertFalse(ParentLock.allows(locked = true, unlocked = false))
    }

    @Test fun fiveWrongTriesLockForAMinute() {
        val now = 1_800_000_000_000L
        var fails = 0; var until = 0L
        repeat(4) { val r = ParentLock.afterFailure(fails, now); fails = r.first; until = r.second }
        assertEquals(4, fails); assertEquals(0L, until)
        val r = ParentLock.afterFailure(fails, now)
        assertEquals(0, r.first); assertEquals(now + ParentLock.LOCKOUT_MS, r.second)
    }
}
