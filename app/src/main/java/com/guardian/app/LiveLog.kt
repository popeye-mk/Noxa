package com.guardian.app

/**
 * v1.5 live feed: the last few hundred DNS lookups, newest first, so the user
 * can WATCH what each app is doing right now — and fix a false block in one
 * tap from the Live screen.
 *
 * Privacy: memory only. Never written to disk, never exported, gone when the
 * process dies or protection stops. It exists solely for the user's own eyes.
 */
object LiveLog {

    enum class Verdict { BLOCKED, ALLOWED, CLOAKED, FIREWALL, MINE }

    class Entry(val time: Long, val pkg: String, val domain: String,
                val verdict: Verdict, val label: String)

    private const val CAPACITY = 300
    private val ring = arrayOfNulls<Entry>(CAPACITY)
    private var next = 0
    private var size = 0

    /** Bumped on every add, so the Live screen only redraws when needed. */
    @Volatile var version = 0L
        private set

    /** v1.11: the service listens here for patterns (e.g. an app stuck on blocks). */
    @Volatile var listener: ((Entry) -> Unit)? = null

    fun add(pkg: String, domain: String, verdict: Verdict, label: String = "") {
        val e = Entry(System.currentTimeMillis(), pkg, domain, verdict, label)
        listener?.let { try { it(e) } catch (_: Exception) {} }
        synchronized(this) {
            ring[next] = e
            next = (next + 1) % CAPACITY
            if (size < CAPACITY) size++
            version++
        }
    }

    /** Snapshot, newest first. */
    fun recent(): List<Entry> = synchronized(this) {
        List(size) { i -> ring[(next - 1 - i + CAPACITY) % CAPACITY]!! }
    }

    fun clear() = synchronized(this) {
        ring.fill(null); next = 0; size = 0; version++
    }
}
