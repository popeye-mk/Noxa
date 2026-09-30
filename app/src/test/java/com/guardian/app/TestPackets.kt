package com.guardian.app

/** Hand-built packets shared by the unit tests (mirrors build-tools/test_packets.py). */
object TestPackets {
    fun qname(d: String): ByteArray {
        val o = ArrayList<Byte>()
        for (l in d.split('.')) { o += l.length.toByte(); l.forEach { o += it.code.toByte() } }
        o += 0
        return o.toByteArray()
    }

    /** IPv4/UDP DNS query for [name] (A record) with transaction [id]. */
    fun ipv4Query(name: String, id: Int): ByteArray {
        val dns = byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0) +
            qname(name) + byteArrayOf(0, 1, 0, 1)
        val udpLen = 8 + dns.size
        val total = 20 + udpLen
        val ip = byteArrayOf(0x45, 0, (total shr 8).toByte(), total.toByte(), 0, 0, 0, 0, 64, 17, 0, 0,
            192.toByte(), 168.toByte(), 1, 2, 10, 111, 0, 2)
        val udp = byteArrayOf(0x9C.toByte(), 0x40, 0, 53, (udpLen shr 8).toByte(), udpLen.toByte(), 0, 0)
        return ip + udp + dns
    }

    /** A DNS answer: one A record for [name] with the given [ttl]. */
    fun reply(name: String, ttl: Int, id: Int = 0xABCD): ByteArray =
        byteArrayOf((id shr 8).toByte(), id.toByte(), 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0) +
            qname(name) + byteArrayOf(0, 1, 0, 1) +
            byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1,
                (ttl shr 24).toByte(), (ttl shr 16).toByte(), (ttl shr 8).toByte(), ttl.toByte(),
                0, 4, 9, 9, 9, 9)

    fun u32(p: ByteArray, o: Int): Long =
        ((p[o].toLong() and 0xFF) shl 24) or ((p[o + 1].toLong() and 0xFF) shl 16) or
            ((p[o + 2].toLong() and 0xFF) shl 8) or (p[o + 3].toLong() and 0xFF)
}
