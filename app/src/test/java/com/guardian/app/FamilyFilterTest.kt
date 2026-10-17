package com.guardian.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.14 family filter: the pure rules (the list itself is built and verified by build-tools). */
class FamilyFilterTest {
    @Test fun theManifestTellsWhichListIsPublished() {
        assertEquals("abc123", FamilyFilter.manifestSha("""{"built_at": "2026-10-20 04:17:00", "family_sha256": "abc123"}"""))
        assertEquals("", FamilyFilter.manifestSha("""{"built_at": "2026-10-12 04:41:09"}"""))   // not published yet
        assertEquals("", FamilyFilter.manifestSha("not json"))
    }

    @Test fun downloadOnlyWhenPublishedAndDifferent() {
        assertTrue(FamilyFilter.needsDownload("", "abc", fileExists = false))
        assertTrue(FamilyFilter.needsDownload("old", "abc", fileExists = true))
        assertTrue(FamilyFilter.needsDownload("abc", "abc", fileExists = false))      // file lost: fetch again
        assertFalse(FamilyFilter.needsDownload("ABC", "abc", fileExists = true))
        assertFalse(FamilyFilter.needsDownload("", "", fileExists = false))           // nothing published
    }

    @Test fun offMeansNothingMatchesAndTheLabelHasItsOwnCategory() {
        assertFalse(FamilyFilter.isOn)
        assertFalse(FamilyFilter.matches("example.com"))
        assertEquals("Family filter", HomeReport.category(FamilyFilter.LABEL))
        assertTrue(FamilyFilter.URL_.startsWith("https://github.com/popeye-mk/Noxa/releases/download/family-filter/"))
    }
}
