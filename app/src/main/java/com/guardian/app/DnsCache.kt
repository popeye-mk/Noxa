package com.guardian.app

/**
 * Small in-memory cache of upstream DNS answers.
 *
 * Apps look up the same handful of names over and over; answering repeats
 * locally saves a network round-trip (latency + battery) on most lookups.
 *
 * Only RAW upstream answers for lookups that passed the Bloom filter are
 * stored. Blocking decisions (filter, firewall, allowlist, CNAME-uncloaking)
 * are still made fresh on every lookup, so a cached answer can never bypass
 * a block or outlive a user's allowlist/firewall change.
 *
 * Entries live for the answer's smallest TTL, capped at [MAX_TTL_S]. Byte
 * layout helpers are cross-checked by build-tools/test_packets.py.
 */
class DnsCache(private val capacity: Int = 1000) {

    private class Cached(val reply: ByteArray, val expiresAt: Long, val ttlOffsets: IntArray)

    private val map = object : LinkedHashMap<String, Cached>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>) = size > capacity
    }

    /** A cached answer for [q], rewritten for this query (its ID, its exact
     *  question bytes, remaining TTLs) — or null on a miss. [dnsQuery] is the
     *  app's raw DNS message. */
    fun get(q: DnsPacket.Query, dnsQuery: ByteArray, now: Long = System.currentTimeMillis()): ByteArray? {
        val e = synchronized(map) {
            val e = map[key(q)] ?: return null
            if (now >= e.expiresAt) { map.remove(key(q)); return null }
            e
        }
        val out = e.reply.copyOf()
        patchForQuery(out, dnsQuery)
        val remaining = ((e.expiresAt - now + 999) / 1000).coerceAtLeast(1L)
        for (off in e.ttlOffsets) writeU32(out, off, remaining)
        return out
    }

    /** Store an upstream [reply] for [q] if it's cacheable. */
    fun put(q: DnsPacket.Query, reply: ByteArray, now: Long = System.currentTimeMillis()) {
        val ttl = cacheTtlSeconds(reply) ?: return
        val offsets = ttlOffsets(reply) ?: return
        synchronized(map) { map[key(q)] = Cached(reply.copyOf(), now + ttl * 1000L, offsets) }
    }

    fun clear() = synchronized(map) { map.clear() }

    private fun key(q: DnsPacket.Query) = "${q.domain}|${q.qtype}"

    companion object {
        const val MAX_TTL_S = 300L          // never trust a cached answer longer than 5 min
        const val NEGATIVE_TTL_S = 60L      // NXDOMAIN / NODATA without an SOA hint
        private const val TYPE_OPT = 41

        /** Seconds to cache [reply] for, or null if it must not be cached
         *  (truncated, server failure, zero TTL, malformed). */
        fun cacheTtlSeconds(reply: ByteArray): Long? {
            if (reply.size < 12) return null
            if (reply[2].toInt() and 0x02 != 0) return null              // TC: truncated
            val rcode = reply[3].toInt() and 0x0F
            if (rcode != 0 && rcode != 3) return null                    // only NOERROR / NXDOMAIN
            val an = u16(reply, 6)
            var min = Long.MAX_VALUE
            var pos = skipQuestions(reply) ?: return null
            val total = an + u16(reply, 8)                               // answers + authority
            for (i in 0 until total) {
                pos = DnsPacket.skipName(reply, pos, reply.size)
                if (pos + 10 > reply.size) return null
                val type = u16(reply, pos)
                if (type != TYPE_OPT) min = minOf(min, u32(reply, pos + 4))
                pos += 10 + u16(reply, pos + 8)
                if (pos > reply.size) return null
            }
            val ttl = if (min == Long.MAX_VALUE) NEGATIVE_TTL_S
                      else if (an == 0) minOf(min, NEGATIVE_TTL_S) else min
            return if (ttl <= 0L) null else minOf(ttl, MAX_TTL_S)
        }

        /** Offsets of every record's TTL field (except EDNS OPT), so a cache
         *  hit can report the time actually left. Null if malformed. */
        fun ttlOffsets(reply: ByteArray): IntArray? {
            var pos = skipQuestions(reply) ?: return null
            val total = u16(reply, 6) + u16(reply, 8) + u16(reply, 10)
            val out = ArrayList<Int>(total)
            for (i in 0 until total) {
                pos = DnsPacket.skipName(reply, pos, reply.size)
                if (pos + 10 > reply.size) return null
                if (u16(reply, pos) != TYPE_OPT) out.add(pos + 4)
                pos += 10 + u16(reply, pos + 8)
                if (pos > reply.size) return null
            }
            return out.toIntArray()
        }

        /** Make a cached [reply] answer THIS query: copy its transaction ID and
         *  its question bytes (same name, but the app may use different letter
         *  case — DNS 0x20). Question length is identical by construction. */
        fun patchForQuery(reply: ByteArray, dnsQuery: ByteArray) {
            reply[0] = dnsQuery[0]; reply[1] = dnsQuery[1]
            val qEnd = skipQuestions(dnsQuery) ?: return
            if (qEnd <= reply.size && skipQuestions(reply) == qEnd)
                System.arraycopy(dnsQuery, 12, reply, 12, qEnd - 12)
        }

        private fun skipQuestions(p: ByteArray): Int? {
            if (p.size < 12) return null
            var pos = 12
            for (i in 0 until u16(p, 4)) {
                pos = DnsPacket.skipName(p, pos, p.size) + 4
                if (pos > p.size) return null
            }
            return pos
        }

        private fun u16(p: ByteArray, o: Int) = ((p[o].toInt() and 0xFF) shl 8) or (p[o + 1].toInt() and 0xFF)
        private fun u32(p: ByteArray, o: Int): Long =
            ((p[o].toLong() and 0xFF) shl 24) or ((p[o + 1].toLong() and 0xFF) shl 16) or
            ((p[o + 2].toLong() and 0xFF) shl 8) or (p[o + 3].toLong() and 0xFF)
        private fun writeU32(p: ByteArray, o: Int, v: Long) {
            p[o] = (v ushr 24).toByte(); p[o + 1] = (v ushr 16).toByte()
            p[o + 2] = (v ushr 8).toByte(); p[o + 3] = v.toByte()
        }
    }
}
