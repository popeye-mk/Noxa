package com.guardian.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs against the REAL bundled filter, so a broken asset fails the build. */
class BloomFilterTest {
    private val file = listOf("src/main/assets/guardian-default.gbf", "app/src/main/assets/guardian-default.gbf")
        .map { File(it) }.first { it.exists() }
    private val bytes = file.readBytes()
    private val f = BloomFilter.load(bytes.inputStream())

    /** The v1.4 parent walk, kept as the reference for the optimised one. */
    private fun referenceMatch(host: String): Boolean {
        var h = host.trim().lowercase().removeSuffix(".")
        while (h.contains('.')) { if (f.contains(h)) return true; h = h.substring(h.indexOf('.') + 1) }
        return false
    }

    @Test fun knownTrackerBlockedAndKnownGoodAllowed() {
        assertTrue(f.matchesHostOrParent("ads.doubleclick.net"))
        assertTrue(f.matchesHostOrParent("connect.facebook.net"))
        assertFalse(f.matchesHostOrParent("www.google.com"))
        assertFalse(f.matchesHostOrParent("wikipedia.org"))
        assertFalse(f.matchesHostOrParent("noxa-test-not-a-tracker-12345.org"))
    }

    @Test fun parentWalkMatchesReference() {
        for (h in listOf("ads.doubleclick.net", "www.google.com", "Metrics.Example.COM.", "a.b.c.d.tracker.com",
            "localhost", "", "com", "x.googlesyndication.com", "graph.facebook.com", "wikipedia.org", "a..b.com"))
            assertEquals(h, referenceMatch(h), f.matchesHostOrParent(h))
    }

    @Test fun rejectsTruncatedAndPaddedFiles() {
        assertTrue(runCatching { BloomFilter.load(bytes.copyOf(bytes.size - 5).inputStream()) }.isFailure)
        assertTrue(runCatching { BloomFilter.load((bytes + byteArrayOf(1)).inputStream()) }.isFailure)
        assertFalse(BloomFilter.isValidFile(bytes.copyOf(bytes.size - 1)))
        assertTrue(BloomFilter.isValidFile(bytes))
    }

    @Test fun concurrentLookupsStayCorrect() {
        val threads = List(4) { Thread { repeat(20_000) { f.matchesHostOrParent("s$it.doubleclick.net") } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertTrue(f.matchesHostOrParent("ads.doubleclick.net"))
        assertFalse(f.matchesHostOrParent("noxa-test-not-a-tracker-12345.org"))
    }
}
