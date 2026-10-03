package com.guardian.app

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** v1.11: time a single plain-DNS lookup of example.com at a resolver. */
object DnsSpeed {
    /** Milliseconds for the best of two tries, or -1 if no valid answer. */
    fun measure(ip: String, timeoutMs: Int = 2000): Long {
        var best = -1L
        repeat(2) { attempt ->
            val t = once(ip, timeoutMs, 0x4E00 + attempt)
            if (t >= 0 && (best < 0 || t < best)) best = t
        }
        return best
    }

    private fun once(ip: String, timeoutMs: Int, id: Int): Long = try {
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            val q = query("example.com", id)
            val t0 = System.nanoTime()
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 53))
            val buf = ByteArray(512)
            val r = DatagramPacket(buf, buf.size)
            s.receive(r)
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (r.length >= 12 && buf[0] == q[0] && buf[1] == q[1]) ms else -1
        }
    } catch (_: Exception) { -1 }

    /** A standard recursive A query (unit-tested). */
    fun query(name: String, id: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in name.split('.')) { out.write(label.length); out.write(label.toByteArray(Charsets.US_ASCII)) }
        out.write(byteArrayOf(0, 0, 1, 0, 1))
        return out.toByteArray()
    }
}
