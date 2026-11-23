package com.guardian.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.16.2 (independent review of 23 Nov 2026): the filter comes back when the tunnel ends. */
class V1162Test {
    @Test fun filterComesBackAfterTheTunnel() {
        assertTrue(TunnelHandover.bringBack(filterWasOn = true, familyDevice = false))    // it was on: back on
        assertFalse(TunnelHandover.bringBack(filterWasOn = false, familyDevice = false))  // the user had it off: stays off
        assertTrue(TunnelHandover.bringBack(filterWasOn = false, familyDevice = true))    // a child's device is never left open
        assertTrue(TunnelHandover.bringBack(filterWasOn = true, familyDevice = true))
    }

    @Test fun wifiGuardOnlyRestsOnAChildsDevice() {                                   // v1.16.3
        assertFalse(TunnelHandover.guardStaysOff(parentLock = false, bedtime = false))    // family filter alone: the guard works
        assertTrue(TunnelHandover.guardStaysOff(parentLock = true, bedtime = false))
        assertTrue(TunnelHandover.guardStaysOff(parentLock = false, bedtime = true))
    }

    @Test fun interfaceHeaderWithACommentStillGetsTheDns() {
        val cfg = "[Interface] # my phone\nPrivateKey = abc\nDNS = 10.2.0.1\n\n[Peer] # server\nPublicKey = def"
        val out = TunnelDns.withDns(cfg, TunnelDns.FAMILY)
        assertTrue(out.contains(TunnelDns.FAMILY))
        assertFalse(out.contains("10.2.0.1"))
        assertEquals(1, out.lines().count { it.trim().startsWith("DNS") })
        assertTrue(out.indexOf("DNS =") < out.indexOf("[Peer]"))
    }

    @Test fun aDnsLineOutsideInterfaceIsLeftAlone() {
        val cfg = "[Interface]\nPrivateKey = abc\n[Peer]\nPublicKey = def\nDNS = 9.9.9.9"       // not valid WireGuard, but never ours to touch
        val out = TunnelDns.withDns(cfg, TunnelDns.BLOCKING)
        assertTrue(out.contains("DNS = 9.9.9.9"))
        assertTrue(out.indexOf(TunnelDns.BLOCKING) < out.indexOf("[Peer]"))
    }
}
