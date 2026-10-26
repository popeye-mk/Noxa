package com.guardian.app

/**
 * The phone's own last check before a downloaded list replaces the one in use (audit of 26 Oct).
 *
 * The SHA-256 in the manifest only proves the file is the one the repo published — the manifest comes from the
 * same repo. If someone ever pushed to main (a stolen login, a poisoned build), an all-zero filter with a matching
 * manifest would silently switch protection off on every phone within a day. So the phone repeats the CI build's
 * own rules (build-tools/verify_build.py) on the file itself: the known trackers / adult sites must match, the
 * everyday sites and Noxa's own update source must not, and the size must be plausible.
 */
object FilterSanity {

    /** Largest download accepted (the main list is ~3 MB, the family list ~14 MB). */
    const val MAX_BYTES = 64L * 1024 * 1024

    private val MUST_BLOCK = listOf("doubleclick.net", "googlesyndication.com", "google-analytics.com",
        "connect.facebook.net")
    private val MUST_ALLOW = listOf("google.com", "wikipedia.org", "github.com", "raw.githubusercontent.com",
        "mozilla.org")
    private val FAMILY_MUST_BLOCK = listOf("pornhub.com", "xvideos.com")

    /** The main list: probes right, and not wildly smaller or bigger than the one in use (an old phone may be
     *  months behind, so wider than the CI's week-to-week 0.85…1.6). */
    fun mainOk(f: BloomFilter, currentItems: Long): Boolean =
        MUST_BLOCK.all { f.matchesHostOrParent(it) } && MUST_ALLOW.none { f.matchesHostOrParent(it) } &&
            (currentItems <= 0 || f.items in (currentItems / 2)..(currentItems * 3))

    /** The family list: the CI's size range and probes. */
    fun familyOk(f: BloomFilter): Boolean =
        f.items in 500_000L..12_000_000L && FAMILY_MUST_BLOCK.all { f.matchesHostOrParent(it) } &&
            MUST_ALLOW.none { f.matchesHostOrParent(it) }

    /** Read at most MAX_BYTES; null when the body is bigger (a huge file is never a list of ours). */
    fun readCapped(input: java.io.InputStream, max: Long = MAX_BYTES): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
