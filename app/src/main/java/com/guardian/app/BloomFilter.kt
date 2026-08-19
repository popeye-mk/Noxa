package com.guardian.app

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Reads the compiled Guardian Bloom filter (.gbf, format "GBF1") and answers
 * "is this domain blocked?" instantly — the phone never parses raw text lists.
 *
 * The format and hashing MUST match build-tools/build_blocklist.py exactly:
 *   header:  "GBF1" | uint32 k | uint64 m_bits | uint64 items   (little-endian)
 *   body:    ceil(m_bits/8) bytes of bit array
 *   hashing: SHA-256(domain) -> h1 = LE(bytes 0..7), h2 = LE(bytes 8..15)
 *            index_i = (h1 + i*h2) mod m,  for i in 0..k-1
 *
 * A Bloom filter can give a false positive (says "blocked" when it isn't) but
 * NEVER a false negative. We built it at a ~1e-6 false-positive rate, so in
 * practice a legitimate domain being wrongly blocked is vanishingly rare.
 */
class BloomFilter private constructor(
    private val k: Int,
    private val mBits: Long,
    val items: Long,
    private val bits: ByteArray,
) {

    /** True if [domain] is (almost certainly) in the blocklist. */
    fun contains(domain: String): Boolean {
        val d = domain.trim().lowercase()
        if (d.isEmpty()) return false
        val h = sha256(d)
        // h1, h2 are UNSIGNED 64-bit values. Reduce each mod m FIRST (unsigned),
        // then combine mod m. This avoids 64-bit overflow and reproduces the
        // Python builder's arbitrary-precision "(h1 + i*h2) mod m" exactly.
        val h1m = java.lang.Long.remainderUnsigned(leLong(h, 0), mBits)
        val h2m = java.lang.Long.remainderUnsigned(leLong(h, 8), mBits)
        var idx = h1m                       // i = 0
        for (i in 0 until k) {
            val byteIndex = (idx ushr 3).toInt()
            val bitMask = 1 shl (idx and 7L).toInt()
            if (bits[byteIndex].toInt() and bitMask == 0) return false
            idx += h2m                       // both < m, so idx < 2*m: no overflow
            if (idx >= mBits) idx -= mBits   // cheap mod for the next i
        }
        return true
    }

    /**
     * Checks a hostname AND its parent domains, so "a.b.tracker.com" is blocked
     * when the list contains "tracker.com". This is how subdomain matching works
     * for an exact-membership filter: test each suffix that still has a dot.
     * e.g. host "ads.a.tracker.com" -> checks:
     *      ads.a.tracker.com, a.tracker.com, tracker.com
     */
    fun matchesHostOrParent(host: String): Boolean {
        var h = host.trim().lowercase().removeSuffix(".")
        while (h.contains('.')) {
            if (contains(h)) return true
            h = h.substring(h.indexOf('.') + 1)
        }
        return false
    }

    companion object {
        private const val MAGIC = "GBF1"

        /** Prefer a downloaded/updated filter in filesDir; fall back to the
         *  bundled asset. This is what the service should call. */
        fun loadCurrent(ctx: Context): BloomFilter {
            val f = File(ctx.filesDir, FilterUpdater.FILTER_FILE)
            return if (f.exists() && f.length() > 24) load(f.inputStream())
            else load(ctx.assets.open("guardian-default.gbf"))
        }

        /** Load from an InputStream (e.g. assets.open("guardian-default.gbf")). */
        fun load(input: InputStream): BloomFilter {
            input.use { stream ->
                val bytes = stream.readBytes()
                require(bytes.size >= 24) { "gbf file too small" }
                val magic = String(bytes, 0, 4, Charsets.US_ASCII)
                require(magic == MAGIC) { "bad magic: $magic" }
                val k = leInt(bytes, 4)
                val m = leLong(bytes, 8)
                val items = leLong(bytes, 16)
                val body = bytes.copyOfRange(24, bytes.size)
                return BloomFilter(k, m, items, body)
            }
        }

        private fun sha256(s: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

        private fun leInt(b: ByteArray, off: Int): Int {
            var v = 0
            for (i in 0 until 4) v = v or ((b[off + i].toInt() and 0xFF) shl (8 * i))
            return v
        }

        private fun leLong(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
            return v
        }
    }
}
