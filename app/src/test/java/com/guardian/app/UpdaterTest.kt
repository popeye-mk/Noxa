package com.guardian.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GitHub "new version" notice must only run in copies signed with our
 * release key — F-Droid builds and Noxa TEST builds can't install GitHub APKs.
 */
class UpdaterTest {
    private val cert = "ab".repeat(32)                    // a 64-hex fake fingerprint
    private val keytoolStyle = cert.uppercase().chunked(2).joinToString(":")

    @Test fun releaseCertConstantIsARealFingerprint() {
        // Guards against shipping the placeholder or a typo: 64 hex chars.
        val c = AppUpdater.RELEASE_CERT_SHA256.replace(":", "").lowercase()
        assertEquals("RELEASE_CERT_SHA256 must be 64 hex characters", 64, c.length)
        assertTrue("RELEASE_CERT_SHA256 must be hex", c.all { it in "0123456789abcdef" })
    }

    @Test fun matchesSameCert() =
        assertTrue(AppUpdater.matchesReleaseCert(listOf(cert), cert))

    @Test fun matchesKeytoolFormatting() =
        assertTrue(AppUpdater.matchesReleaseCert(listOf(cert), keytoolStyle))

    @Test fun anyOfSeveralCertsIsEnough() =
        assertTrue(AppUpdater.matchesReleaseCert(listOf("cd".repeat(32), cert), cert))

    @Test fun otherKeyDoesNotMatch() =                       // e.g. F-Droid's key
        assertFalse(AppUpdater.matchesReleaseCert(listOf("cd".repeat(32)), cert))

    @Test fun noCertsDoesNotMatch() =
        assertFalse(AppUpdater.matchesReleaseCert(emptyList(), cert))

    @Test fun placeholderNeverMatches() =
        assertFalse(AppUpdater.matchesReleaseCert(listOf(cert), "PASTE_RELEASE_CERT_SHA256_HERE"))

    @Test fun sha256HexKnownVector() = assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        AppUpdater.sha256Hex("abc".toByteArray()))

    @Test fun versionCompare() {
        assertTrue(AppUpdater.isNewer("1.10", "1.9"))
        assertTrue(AppUpdater.isNewer("1.9.1", "1.9"))
        assertFalse(AppUpdater.isNewer("1.9", "1.9.1"))
        assertFalse(AppUpdater.isNewer("1.9", "1.9-test"))
    }
}
