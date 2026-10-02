package com.guardian.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** v1.9.2: the check-up's "See which app" must name the right app(s). */
class CheckupTest {

    @Before fun reset() = AppStats.clearAll()

    @Test fun namesTheAppThatHitSpyware() {
        AppStats.recordBlocked("com.example.spy", "Stalkerware · Spyware")
        AppStats.recordBlocked("com.example.spy", "Stalkerware · Spyware")
        AppStats.recordBlocked("com.example.game", "Google · Advertising")
        assertEquals(listOf("com.example.spy" to 2L), AppStats.appsHitting("Stalkerware"))
    }

    @Test fun biggestFirst() {
        AppStats.recordBlocked("a", "Stalkerware · Spyware")
        repeat(3) { AppStats.recordBlocked("b", "Stalkerware · Spyware") }
        assertEquals(listOf("b" to 3L, "a" to 1L), AppStats.appsHitting("Stalkerware"))
    }

    @Test fun nothingWhenNoHits() {
        AppStats.recordBlocked("x", "Meta · Tracking")
        assertTrue(AppStats.appsHitting("Stalkerware").isEmpty())
    }
}
