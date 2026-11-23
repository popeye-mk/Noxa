package com.guardian.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.16.1: the tunnel must not be a way around the family filter. */
class V1161Test {
    private val cfg = """
        [Interface]
        PrivateKey = abc
        Address = 10.2.0.2/32
        DNS = 10.2.0.1

        [Peer]
        PublicKey = def
        Endpoint = 1.2.3.4:51820
        AllowedIPs = 0.0.0.0/0
    """.trimIndent()

    @Test fun familyComesFirst() {
        assertEquals(TunnelDns.FAMILY, TunnelDns.choose(blockTrackers = true, familyOn = true))
        assertEquals(TunnelDns.FAMILY, TunnelDns.choose(blockTrackers = false, familyOn = true))   // the switch can't undo it
        assertEquals(TunnelDns.BLOCKING, TunnelDns.choose(blockTrackers = true, familyOn = false))
        assertNull(TunnelDns.choose(blockTrackers = false, familyOn = false))
    }

    @Test fun dnsLineIsReplacedOnce() {
        val out = TunnelDns.withDns(cfg, TunnelDns.FAMILY)
        assertTrue(out.contains("DNS = 94.140.14.15, 94.140.15.16"))
        assertFalse(out.contains("10.2.0.1"))
        assertEquals(1, out.lines().count { it.trim().startsWith("DNS") })
        assertTrue(out.indexOf("DNS =") < out.indexOf("[Peer]"))                 // inside [Interface]
        assertTrue(out.contains("PrivateKey = abc") && out.contains("Endpoint = 1.2.3.4:51820"))
    }

    @Test fun dnsLineIsAddedWhenTheConfigHasNone() {
        val noDns = cfg.lines().filterNot { it.trim().startsWith("DNS") }.joinToString("\n")
        val out = TunnelDns.withDns(noDns, TunnelDns.BLOCKING)
        assertEquals(1, out.lines().count { it.trim().startsWith("DNS") })
        assertTrue(out.indexOf("DNS =") < out.indexOf("[Peer]"))
        val onlyInterface = "[Interface]\nPrivateKey = abc"
        assertTrue(TunnelDns.withDns(onlyInterface, TunnelDns.BLOCKING).trim().endsWith(TunnelDns.BLOCKING))
    }
}
