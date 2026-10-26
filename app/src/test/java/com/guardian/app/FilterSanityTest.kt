package com.guardian.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The phone's own check before a downloaded list goes live (audit of 26 Oct). */
class FilterSanityTest {
    private val real = listOf("src/main/assets/guardian-default.gbf", "app/src/main/assets/guardian-default.gbf")
        .map { File(it) }.first { it.exists() }.readBytes().let { BloomFilter.load(it.inputStream()) }

    /** A well-formed GBF1 file whose bits are all [fill] — what a tampered "empty" or "block all" list looks like. */
    private fun hollow(fill: Byte, items: Long): BloomFilter {
        val m = 8L * 4096
        val head = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .put("GBF1".toByteArray()).putInt(7).putLong(m).putLong(items).array()
        return BloomFilter.load((head + ByteArray((m / 8).toInt()) { fill }).inputStream())
    }

    @Test fun theRealListPasses() {
        assertTrue(FilterSanity.mainOk(real, real.items))
        assertTrue(FilterSanity.mainOk(real, 0))                       // nothing to compare with yet
    }

    @Test fun anEmptyOrBlockEverythingListIsRefused() {
        assertFalse(FilterSanity.mainOk(hollow(0, real.items), real.items))           // blocks nothing
        assertFalse(FilterSanity.mainOk(hollow(-1, real.items), real.items))          // blocks everything, GitHub too
        assertFalse(FilterSanity.familyOk(hollow(0, 1_000_000)))
        assertFalse(FilterSanity.familyOk(hollow(-1, 1_000_000)))
    }

    @Test fun aListFarSmallerThanTheOneInUseIsRefused() {
        assertFalse(FilterSanity.mainOk(real, real.items * 10))       // the new one would be a tenth
    }

    @Test fun downloadsAreCapped() {
        val small = ByteArray(1000) { 7 }
        assertArrayEquals(small, FilterSanity.readCapped(small.inputStream(), 1000))
        assertNull(FilterSanity.readCapped(ByteArray(1001).inputStream(), 1000))
    }
}
