package com.guardian.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.14 bedtime: the pure rules. */
class BedtimeTest {
    private val h = 60

    @Test fun aWindowMayCrossMidnight() {
        assertTrue(Bedtime.active(22 * h, 21 * h, 7 * h))
        assertTrue(Bedtime.active(3 * h, 21 * h, 7 * h))
        assertTrue(Bedtime.active(21 * h, 21 * h, 7 * h))            // from the first minute
        assertFalse(Bedtime.active(7 * h, 21 * h, 7 * h))            // until, not including
        assertFalse(Bedtime.active(12 * h, 21 * h, 7 * h))
        assertTrue(Bedtime.active(14 * h, 13 * h, 15 * h))           // a daytime window too
        assertFalse(Bedtime.active(16 * h, 13 * h, 15 * h))
        assertFalse(Bedtime.active(9 * h, 9 * h, 9 * h))             // an empty window is never active
    }

    @Test fun onlyChosenAppsAndNeverTheSystem() {
        val chosen = setOf("com.example.game")
        assertTrue(Bedtime.blocks("com.example.game", active = true, all = false, chosen = chosen))
        assertFalse(Bedtime.blocks("com.example.phone", active = true, all = false, chosen = chosen))
        assertTrue(Bedtime.blocks("com.example.phone", active = true, all = true, chosen = chosen))
        assertFalse(Bedtime.blocks("com.example.game", active = false, all = true, chosen = chosen))
        assertFalse(Bedtime.blocks(AppStats.UNKNOWN, active = true, all = true, chosen = chosen))
    }

    @Test fun theMenuSaysWhatIsSet() {
        assertEquals("off", Bedtime.describe(false, 21 * h, 7 * h, true, 0))
        assertEquals("21:00–07:00, all apps", Bedtime.describe(true, 21 * h, 7 * h, true, 0))
        assertEquals("20:30–06:45, 3 app(s)", Bedtime.describe(true, 20 * h + 30, 6 * h + 45, false, 3))
        assertEquals("Blocked by you", HomeReport.category(Bedtime.LABEL))
    }
}
