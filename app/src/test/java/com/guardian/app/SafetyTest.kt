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
