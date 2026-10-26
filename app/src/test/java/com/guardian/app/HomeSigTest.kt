package com.guardian.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.14.6 signed home channel (noxa-home-v2, audit of 26 Oct): the shared test vectors — the home server's
 *  own tests check the very same ones, so both sides sign alike. */
class HomeSigTest {
    private val keyTyped = "home-TESTKEY-not-real-123"
    private val k = HomeSig.hmacKey(keyTyped)
    private val device = "aaaaaaaa-0000-0000-0000-000000000001"
    private val time = 1793000000L
    private val path = "/api/home/report"
    private val body = """{"format":"noxa-home-report","device":{"id":"aaaaaaaa-0000-0000-0000-000000000001"}}""".toByteArray(Charsets.UTF_8)
    private val requestSig = "ff6009f5b6e11d004acf33b4fc34e6d8acc55cea6cbf9e537a09225011d3e89e"
    private val replyBody = """{"ok": true, "rules": {}, "home_list_version": "abc"}"""
    private val replySig = "bde50926c85ec00760e2c2b6927328acecb10d9c6951920b81613c145cbae3c4"

    private fun raw(status: String, body: String, sigLine: String?) =
        "HTTP/1.0 $status\r\nServer: BaseHTTP/0.6 Python/3.12\r\nContent-Type: application/json\r\n" +
            (if (sigLine == null) "" else "$sigLine\r\n") + "\r\n" + body

    @Test fun theSigningKeyIsTheSha256OfTheTypedKey() {
        assertEquals("3c9b7b762080cf6382dd004b3ea95c867d4f445dda274e4334b68b6b788e4b93", HomeSig.hex(k))
        assertEquals("a23150cee2441ee4be2756ff3913de9fdeb303e26d8b1d5d473a6536f769a8c3", HomeSig.hex(HomeSig.sha256(body)))
    }

    @Test fun theRequestSignatureMatchesTheVector() {
        assertEquals(requestSig, HomeSig.requestSig(k, path, device, time, body))
        assertEquals(requestSig, HomeSig.requestSig(k, path, device.uppercase(), time, body))    // the id in lower case
        assertNotEquals(requestSig, HomeSig.requestSig(k, path, device, time + 1, body))
        assertNotEquals(requestSig, HomeSig.requestSig(k, "/api/home/list", device, time, body))
        assertNotEquals(requestSig, HomeSig.requestSig(k, path, device, time, body + ' '.code.toByte()))
        assertNotEquals(requestSig, HomeSig.requestSig(HomeSig.hmacKey("home-other-key-1234"), path, device, time, body))
    }

    @Test fun theReplySignatureMatchesTheVector() {
        assertEquals(replySig, HomeSig.replySig(k, requestSig, 200, replyBody.toByteArray(Charsets.UTF_8)))
    }

    @Test fun aReplyIsAcceptedOnlyForThisStatusThisBodyAndThisRequest() {
        val b = replyBody.toByteArray(Charsets.UTF_8)
        assertTrue(HomeSig.verifyReply(k, requestSig, 200, b, replySig))
        assertTrue(HomeSig.verifyReply(k, requestSig, 200, b, " ${replySig.uppercase()} "))      // hex in any case
        assertFalse(HomeSig.verifyReply(k, requestSig, 200, replyBody.replace("abc", "abd").toByteArray(), replySig))
        assertFalse(HomeSig.verifyReply(k, requestSig, 500, b, replySig))
        assertFalse(HomeSig.verifyReply(k, requestSig, 200, b, null))                            // no signature
        assertFalse(HomeSig.verifyReply(k, requestSig, 200, b, ""))
        assertFalse(HomeSig.verifyReply(k, requestSig, 200, b, replySig.dropLast(1)))
        assertFalse(HomeSig.verifyReply(k, "0".repeat(64), 200, b, replySig))                    // an old answer, another request
        assertFalse(HomeSig.verifyReply(k, "", 200, b, replySig))
        assertFalse(HomeSig.verifyReply(HomeSig.hmacKey("home-other-key-1234"), requestSig, 200, b, replySig))
    }

    @Test fun aHeaderIsFoundInAnyCaseAndOnlyInTheHead() {
        val r = raw("200 OK", "{}", "x-home-reply-sig:  abc ")
        assertEquals("abc", HomeSig.header(r, "X-Home-Reply-Sig"))
        assertEquals("application/json", HomeSig.header(r, "content-type"))
        assertNull(HomeSig.header(r, "X-Home-Sig"))
        assertNull(HomeSig.header(raw("200 OK", "X-Home-Reply-Sig: $replySig", null), HomeSig.REPLY_SIG))  // in the body: no
        assertNull(HomeSig.header("HTTP/1.0 200 OK\r\nX-Home-Reply-Sig: $replySig\r\n", HomeSig.REPLY_SIG))   // head never ended
        assertNull(HomeSig.header("X-Home-Reply-Sig: $replySig\r\n\r\n{}", HomeSig.REPLY_SIG))      // that's the status line
    }

    @Test fun theSignedRequestCarriesTheSignatureAndNeverTheKey() {
        val r = HomeReport.request("192.168.1.20", 8790, body, path, keyTyped, device.uppercase(), time)
        assertEquals(requestSig, r.sig)
        val req = String(r.bytes, Charsets.ISO_8859_1)
        val head = req.substringBefore("\r\n\r\n")
        assertTrue(head.startsWith("POST $path HTTP/1.1\r\n"))
        assertTrue(head.contains("\r\nX-Home-Device: $device\r\n"))
        assertTrue(head.contains("\r\nX-Home-Time: $time\r\n"))
        assertTrue(head.contains("\r\nX-Home-Sig: $requestSig\r\n"))
        assertFalse(req.contains("X-Home-Key")); assertFalse(req.contains(keyTyped))
        // what the server does: the signature in the request verifies over the body that was sent
        val sent = req.substringAfter("\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        assertTrue(sent.contentEquals(body))
        val h = { name: String -> head.lines().first { it.startsWith("$name: ") }.substringAfter(": ").trim() }
        assertEquals(h("X-Home-Sig"), HomeSig.requestSig(k, path, h("X-Home-Device"), h("X-Home-Time").toLong(), sent))
    }

    @Test fun twoCallsInOneSecondNeverSignAlike() {
        val ms = 1_793_000_000_123L
        val a = HomeReport.signTime(ms)
        val b = HomeReport.signTime(ms)                       // a double tap: same second, same body
        assertTrue(a >= ms / 1000); assertTrue(b > a)
        assertTrue(b - ms / 1000 < 900)                        // still well inside the server's 15 minutes
    }

    @Test fun aRawReplyIsCheckedOverItsExactBytesAndStatus() {
        val good = raw("200 OK", replyBody, "X-Home-Reply-Sig: $replySig")
        assertTrue(HomeReport.signedBy(keyTyped, requestSig, good))
        assertFalse(HomeReport.signedBy(keyTyped, requestSig, raw("200 OK", replyBody.replace("{}", "{\"family_filter\": false}"), "X-Home-Reply-Sig: $replySig")))
        assertFalse(HomeReport.signedBy(keyTyped, requestSig, raw("500 Internal Server Error", replyBody, "X-Home-Reply-Sig: $replySig")))
        assertFalse(HomeReport.signedBy(keyTyped, requestSig, raw("200 OK", replyBody, null)))          // a plain receiver
        assertFalse(HomeReport.signedBy("home-other-key-1234", requestSig, good))
        assertFalse(HomeReport.signedBy(keyTyped, "", good))                                             // the unsigned join
        assertFalse(HomeReport.signedBy("", requestSig, good))
    }

    @Test fun aReplyWithNonAsciiBytesIsCheckedOverTheBytesSent() {
        // the reply is read as ISO-8859-1: every byte maps to one char, so the exact body bytes come back
        val utf8 = """{"ok": true, "message": "Welkom, Noé — 夜"}""".toByteArray(Charsets.UTF_8)
        val sig = HomeSig.replySig(k, requestSig, 200, utf8)
        val reply = raw("200 OK", String(utf8, Charsets.ISO_8859_1), "X-Home-Reply-Sig: $sig")
        assertTrue(HomeReport.signedBy(keyTyped, requestSig, reply))
    }

    @Test fun signingTimeFollowsTheClockAgainAfterAWrongDateIsPutRight() {
        val future = 4_000_000_000L                                      // a phone set years ahead…
        assertEquals(future, HomeReport.signTime(future * 1000))
        assertEquals(future + 1, HomeReport.signTime(future * 1000))      // same second: still never twice
        val real = 1_793_000_000L                                        // …and corrected back
        assertEquals(real, HomeReport.signTime(real * 1000))
    }
}
