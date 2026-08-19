package com.guardian.app

/**
 * IPv4 + IPv6 + UDP + DNS reader/writer for the Guardian sinkhole.
 *
 * Phase 1 needs:
 *   - parseQuery:              pull the looked-up domain (+ qtype + IP family)
 *   - isTcpDns:                recognise TCP:53 so the loop can drop it on purpose
 *   - extractUdpPayload:       the raw DNS message (to forward upstream)
 *   - buildSinkholeResponse:   answer a BLOCKED domain (A->0.0.0.0, AAAA->::,
 *                              anything else -> NODATA, so nothing resolves)
 *   - buildNxDomainResponse:   NXDOMAIN — used for the DoH canary
 *   - buildForwardedResponse:  wrap an upstream reply back to the app
 *
 * Both IPv4 and IPv6 transport are handled so DNS can't leak over IPv6. TCP-DNS
 * is intentionally NOT proxied (that needs a full userspace TCP stack); it is
 * detected and dropped, which makes resolvers fall back to UDP, which we filter.
 * DoH is handled at two levels: known DoH endpoints are in the blocklist, and
 * the DoH canary domain is answered NXDOMAIN to switch browsers back to plain DNS.
 *
 * Byte-level networking must still be validated on a real device (Step 8). The
 * algorithms here are cross-checked by build-tools/test_packets.py.
 */
object DnsPacket {

    data class Query(
        val domain: String,
        val qtype: Int,       // 1 = A, 28 = AAAA, others -> NODATA sinkhole
        val ipVersion: Int,   // 4 or 6
        val l3Len: Int,       // IP header length (IPv4 IHL*4, or 40 for IPv6)
        val udpStart: Int,    // offset of UDP header
        val dnsStart: Int,    // offset of DNS message
        val questionEnd: Int  // offset just past the question section
    )

    private const val PROTO_TCP = 6
    private const val PROTO_UDP = 17
    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28

    fun parseQuery(p: ByteArray, len: Int): Query? {
        if (len < 12) return null
        val version = (p[0].toInt() ushr 4) and 0xF
        val l3: Int
        val proto: Int
        when (version) {
            4 -> {
                if (len < 28) return null
                l3 = (p[0].toInt() and 0xF) * 4
                proto = p[9].toInt() and 0xFF
            }
            6 -> {
                if (len < 48) return null
                l3 = 40                                  // fixed IPv6 header, no options
                proto = p[6].toInt() and 0xFF            // next header
            }
            else -> return null
        }
        if (proto != PROTO_UDP) return null
        val udpStart = l3
        if (udpStart + 8 > len) return null
        if (u16(p, udpStart + 2) != 53) return null      // destination port 53
        val dnsStart = udpStart + 8
        if (dnsStart + 12 > len) return null
        if (u16(p, dnsStart + 4) < 1) return null        // QDCOUNT >= 1

        // Parse QNAME (labels) starting after the 12-byte DNS header.
        val sb = StringBuilder()
        var pos = dnsStart + 12
        while (pos < len) {
            val labelLen = p[pos].toInt() and 0xFF
            if (labelLen == 0) { pos += 1; break }
            if (labelLen and 0xC0 != 0) return null      // no compression in queries
            pos += 1
            if (pos + labelLen > len) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until labelLen) sb.append((p[pos + i].toInt() and 0xFF).toChar())
            pos += labelLen
        }
        if (pos + 4 > len) return null                   // need QTYPE + QCLASS
        val qtype = u16(p, pos)
        val questionEnd = pos + 4
        if (sb.isEmpty()) return null
        return Query(sb.toString().lowercase(), qtype, version, l3, udpStart, dnsStart, questionEnd)
    }

    /** True for a TCP packet aimed at port 53 (v4 or v6). The loop drops these
     *  on purpose so the resolver falls back to UDP, which we can filter. */
    fun isTcpDns(p: ByteArray, len: Int): Boolean {
        if (len < 4) return false
        val version = (p[0].toInt() ushr 4) and 0xF
        val l3: Int
        val proto: Int
        when (version) {
            4 -> { if (len < 20) return false; l3 = (p[0].toInt() and 0xF) * 4; proto = p[9].toInt() and 0xFF }
            6 -> { if (len < 40) return false; l3 = 40; proto = p[6].toInt() and 0xFF }
            else -> return false
        }
        if (proto != PROTO_TCP) return false
        if (l3 + 4 > len) return false
        return u16(p, l3 + 2) == 53
    }

    fun extractUdpPayload(p: ByteArray, len: Int): ByteArray? {
        val version = (p[0].toInt() ushr 4) and 0xF
        val l3 = when (version) {
            4 -> (p[0].toInt() and 0xF) * 4
            6 -> 40
            else -> return null
        }
        val dnsStart = l3 + 8
        if (dnsStart > len) return null
        return p.copyOfRange(dnsStart, len)
    }

    /** CNAME-uncloaking: pull every CNAME target out of a DNS *response* message
     *  (handles name compression). A tracker hiding behind a first-party subdomain
     *  (e.g. metrics.site.com CNAME -> tracker.evilcorp.com) shows up here, so the
     *  caller can block it even though the queried name wasn't on any list. */
    fun cnameTargets(p: ByteArray, len: Int): List<String> {
        if (len < 12) return emptyList()
        val out = ArrayList<String>()
        val qd = u16(p, 4)
        val an = u16(p, 6)
        var pos = 12
        var i = 0
        while (i < qd) {                     // skip the question section
            pos = skipName(p, pos, len) + 4  // + QTYPE + QCLASS
            if (pos > len) return out
            i++
        }
        i = 0
        while (i < an) {                     // walk the answer records
            pos = skipName(p, pos, len)
            if (pos + 10 > len) break
            val type = u16(p, pos)
            val rdlen = u16(p, pos + 8)
            val rdStart = pos + 10
            if (type == 5 && rdStart + rdlen <= len) {   // CNAME record
                val name = readName(p, rdStart, len)
                if (name.isNotEmpty()) out.add(name)
            }
            pos = rdStart + rdlen
            if (pos > len) break
            i++
        }
        return out
    }

    /** Decode a (possibly compressed) domain name starting at [start]. */
    private fun readName(p: ByteArray, start: Int, len: Int): String {
        val sb = StringBuilder()
        var pos = start
        var guard = 0
        while (pos in 0 until len && guard++ < 128) {
            val b = p[pos].toInt() and 0xFF
            if (b == 0) break
            if (b and 0xC0 == 0xC0) {                    // compression pointer
                if (pos + 1 >= len) break
                pos = ((b and 0x3F) shl 8) or (p[pos + 1].toInt() and 0xFF)
                continue
            }
            pos++
            if (pos + b > len) break
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 0 until b) sb.append((p[pos + k].toInt() and 0xFF).toChar())
            pos += b
        }
        return sb.toString().lowercase()
    }

    /** Advance past a (possibly compressed) name; returns the next offset. */
    private fun skipName(p: ByteArray, start: Int, len: Int): Int {
        var pos = start
        var guard = 0
        while (pos in 0 until len && guard++ < 128) {
            val b = p[pos].toInt() and 0xFF
            if (b == 0) return pos + 1
            if (b and 0xC0 == 0xC0) return pos + 2       // pointer ends the name
            pos += 1 + b
        }
        return pos
    }

    /** Answer a BLOCKED domain so nothing resolves: A -> 0.0.0.0, AAAA -> ::,
     *  every other qtype -> NODATA (NOERROR with no answer record). */
    fun buildSinkholeResponse(p: ByteArray, len: Int, q: Query): ByteArray? {
        val answer = when (q.qtype) {
            TYPE_A -> byteArrayOf(
                0xC0.toByte(), 0x0C, 0x00, TYPE_A.toByte(), 0x00, 0x01,
                0x00, 0x00, 0x00, 0x3C, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00
            )
            TYPE_AAAA -> byteArrayOf(
                0xC0.toByte(), 0x0C, 0x00, TYPE_AAAA.toByte(), 0x00, 0x01,
                0x00, 0x00, 0x00, 0x3C, 0x00, 0x10
            ) + ByteArray(16)                            // 16 zero bytes = ::
            else -> null                                 // NODATA
        }
        val dns = buildDnsMessage(p, q, 0x81, 0x80, answer)   // QR, RD, RA, NOERROR
        return wrapResponse(p, q, dns)
    }

    /** NXDOMAIN — used to answer the DoH canary so browsers disable auto-DoH. */
    fun buildNxDomainResponse(p: ByteArray, len: Int, q: Query): ByteArray? {
        val dns = buildDnsMessage(p, q, 0x81, 0x83, null)     // RCODE 3 = NXDOMAIN
        return wrapResponse(p, q, dns)
    }

    /** Wrap the upstream resolver's reply back into a packet for the app. */
    fun buildForwardedResponse(orig: ByteArray, origLen: Int,
                               dnsReply: ByteArray, dnsLen: Int): ByteArray? {
        val version = (orig[0].toInt() ushr 4) and 0xF
        val dns = dnsReply.copyOfRange(0, dnsLen)
        return when (version) {
            4 -> { val ihl = (orig[0].toInt() and 0xF) * 4; wrapIpv4(orig, ihl, ihl, dns) }
            6 -> wrapIpv6(orig, dns)
            else -> null
        }
    }

    // --- DNS message assembly ------------------------------------------------
    private fun buildDnsMessage(p: ByteArray, q: Query,
                                flagsHi: Int, flagsLo: Int, answer: ByteArray?): ByteArray {
        val question = p.copyOfRange(q.dnsStart, q.questionEnd)   // header + qname+qtype+qclass
        val anCount = if (answer != null) 1 else 0
        val out = ArrayList<Byte>(question.size + (answer?.size ?: 0))
        out.add(question[0]); out.add(question[1])               // ID (echoed)
        out.add(flagsHi.toByte()); out.add(flagsLo.toByte())     // flags
        out.add(0x00); out.add(0x01)                             // QDCOUNT = 1
        out.add(0x00); out.add(anCount.toByte())                 // ANCOUNT
        out.add(0x00); out.add(0x00)                             // NSCOUNT = 0
        out.add(0x00); out.add(0x00)                             // ARCOUNT = 0
        for (i in 12 until question.size) out.add(question[i])   // question, verbatim
        if (answer != null) for (b in answer) out.add(b)
        return out.toByteArray()
    }

    private fun wrapResponse(orig: ByteArray, q: Query, dns: ByteArray): ByteArray =
        if (q.ipVersion == 6) wrapIpv6(orig, dns)
        else wrapIpv4(orig, q.l3Len, q.udpStart, dns)

    // --- build an IPv4/UDP packet: swap src/dst, attach dnsPayload ------------
    private fun wrapIpv4(orig: ByteArray, ihl: Int, udpStart: Int,
                         dnsPayload: ByteArray): ByteArray {
        val total = ihl + 8 + dnsPayload.size
        val out = ByteArray(total)
        System.arraycopy(orig, 0, out, 0, ihl)                  // copy IP header
        out[2] = (total ushr 8).toByte(); out[3] = total.toByte()
        out[8] = 64                                             // TTL
        for (i in 0 until 4) { out[12 + i] = orig[16 + i]; out[16 + i] = orig[12 + i] }  // swap IPs
        out[10] = 0; out[11] = 0                                // zero checksum then fill
        val ipck = checksum(out, 0, ihl)
        out[10] = (ipck ushr 8).toByte(); out[11] = ipck.toByte()

        val srcPort = u16(orig, udpStart); val dstPort = u16(orig, udpStart + 2)
        val udpLen = 8 + dnsPayload.size
        out[ihl] = (dstPort ushr 8).toByte(); out[ihl + 1] = dstPort.toByte()
        out[ihl + 2] = (srcPort ushr 8).toByte(); out[ihl + 3] = srcPort.toByte()
        out[ihl + 4] = (udpLen ushr 8).toByte(); out[ihl + 5] = udpLen.toByte()
        out[ihl + 6] = 0; out[ihl + 7] = 0                      // UDP checksum 0 (legal over IPv4)
        System.arraycopy(dnsPayload, 0, out, ihl + 8, dnsPayload.size)
        return out
    }

    // --- build an IPv6/UDP packet: swap src/dst, mandatory UDP checksum -------
    private fun wrapIpv6(orig: ByteArray, dnsPayload: ByteArray): ByteArray {
        val udpLen = 8 + dnsPayload.size
        val out = ByteArray(40 + udpLen)
        System.arraycopy(orig, 0, out, 0, 40)                   // copy IPv6 header
        out[4] = (udpLen ushr 8).toByte(); out[5] = udpLen.toByte()   // payload length
        out[6] = PROTO_UDP.toByte()                             // next header
        out[7] = 64                                             // hop limit
        for (i in 0 until 16) { out[8 + i] = orig[24 + i]; out[24 + i] = orig[8 + i] }  // swap addrs

        val srcPort = u16(orig, 40); val dstPort = u16(orig, 42)
        out[40] = (dstPort ushr 8).toByte(); out[41] = dstPort.toByte()
        out[42] = (srcPort ushr 8).toByte(); out[43] = srcPort.toByte()
        out[44] = (udpLen ushr 8).toByte(); out[45] = udpLen.toByte()
        out[46] = 0; out[47] = 0                                // checksum placeholder
        System.arraycopy(dnsPayload, 0, out, 48, dnsPayload.size)

        // UDP checksum is mandatory over IPv6: sum a pseudo-header + the segment.
        val pseudo = ByteArray(40 + udpLen)
        System.arraycopy(out, 8, pseudo, 0, 32)                 // src+dst (already swapped)
        pseudo[34] = (udpLen ushr 8).toByte(); pseudo[35] = udpLen.toByte()  // upper-layer length
        pseudo[39] = PROTO_UDP.toByte()                         // next header
        System.arraycopy(out, 40, pseudo, 40, udpLen)           // UDP header + data
        var ck = checksum(pseudo, 0, pseudo.size)
        if (ck == 0) ck = 0xFFFF                                // 0 is illegal -> 0xFFFF
        out[46] = (ck ushr 8).toByte(); out[47] = ck.toByte()
        return out
    }

    private fun u16(p: ByteArray, off: Int) =
        ((p[off].toInt() and 0xFF) shl 8) or (p[off + 1].toInt() and 0xFF)

    private fun checksum(b: ByteArray, off: Int, length: Int): Int {
        var sum = 0L; var i = off
        while (i < off + length - 1) { sum += u16(b, i).toLong(); i += 2 }
        if (length and 1 == 1) sum += ((b[off + length - 1].toInt() and 0xFF) shl 8).toLong()
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
