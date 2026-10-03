package com.guardian.app

/**
 * v1.13 router attack shield (DNS rebinding protection). A web page can make
 * an ordinary-looking name (evil.example) answer with a PRIVATE address —
 * your router (192.168.1.1), a camera, a printer — and then talk to that
 * device from inside your browser. Public names have no reason to point
 * into your home network, so such answers are refused. Local names
 * (router.local, fritz.box, plex.direct, ...) are left alone, and anything
 * on the user's "Allowed sites" list skips this check.
 */
object RebindShield {
    const val LABEL = "Router attack shield · Rebinding"

    /** Names that legitimately resolve to home/LAN addresses. */
    private val LOCAL_SUFFIXES = listOf(
        "local", "lan", "home", "home.arpa", "internal", "localdomain", "intranet", "corp",
        "fritz.box", "box", "router", "plex.direct", "ts.net", "nip.io", "sslip.io",
        "localhost", "localtest.me", "lvh.me", "in-addr.arpa", "ip6.arpa")

    /** Pure, unit-tested: may [host] point into a private network? */
    fun exempt(host: String): Boolean {
        val h = host.trim().lowercase().removeSuffix(".")
        if (!h.contains('.')) return true                      // single-label LAN name
        return LOCAL_SUFFIXES.any { h == it || h.endsWith(".$it") }
    }

    /** Pure, unit-tested: is this A/AAAA address inside a home/LAN/loopback range?
     *  0.0.0.0 / :: are NOT counted (that's how blocking resolvers say "blocked"),
     *  nor is 100.64/10 (carrier NAT, Tailscale). */
    fun isPrivate(a: ByteArray): Boolean {
        if (a.size == 4) {
            val b0 = a[0].toInt() and 0xFF; val b1 = a[1].toInt() and 0xFF
            return b0 == 10 || b0 == 127 ||
                (b0 == 172 && b1 in 16..31) ||
                (b0 == 192 && b1 == 168) ||
                (b0 == 169 && b1 == 254)
        }
        if (a.size == 16) {
            val b0 = a[0].toInt() and 0xFF; val b1 = a[1].toInt() and 0xFF
            if (a.copyOfRange(0, 15).all { it == 0.toByte() } && a[15] == 1.toByte()) return true   // ::1
            if (b0 and 0xFE == 0xFC) return true                                                    // fc00::/7
            if (b0 == 0xFE && b1 and 0xC0 == 0x80) return true                                      // fe80::/10
            // IPv4-mapped ::ffff:a.b.c.d
            if (a.copyOfRange(0, 10).all { it == 0.toByte() } && a[10] == 0xFF.toByte() && a[11] == 0xFF.toByte())
                return isPrivate(a.copyOfRange(12, 16))
        }
        return false
    }

    /** Should this upstream answer for [host] be refused? */
    fun refuse(host: String, reply: ByteArray, len: Int): Boolean {
        if (exempt(host)) return false
        return DnsPacket.answerAddresses(reply, len).any { isPrivate(it) }
    }
}
