package com.guardian.app

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 1.14.6 signed home channel, "noxa-home-v2" (security audit of 26 Oct 2026).
 *
 * Before, every keyed call sent the key itself in a header over plain HTTP, and
 * Noxa applied whatever rules came back. Anyone else on the Wi-Fi who got in the
 * middle (ARP spoofing) could copy the key and answer in the server's place, for
 * example "family filter off". Now the key never travels: each request is signed
 * with it, and the home server signs its answer with the same key, so Noxa can
 * tell its own home server's answer from a stranger's. Pure functions, unit-tested
 * against the same test vectors as the home server's tests. See docs/HOME-SERVER.md.
 *
 *   K         = SHA-256 of the key as typed (the server keeps only that hash)
 *   request   = HMAC-SHA256(K, "noxa-home-v2\n" + path + "\n" + device id (lower case) + "\n" +
 *               unix seconds + "\n" + hex SHA-256 of the exact body)
 *   reply     = HMAC-SHA256(K, "noxa-home-v2-reply\n" + request signature + "\n" + status + "\n" +
 *               hex SHA-256 of the exact reply body)
 */
object HomeSig {
    const val DEVICE = "X-Home-Device"
    const val TIME = "X-Home-Time"
    const val SIG = "X-Home-Sig"
    const val REPLY_SIG = "X-Home-Reply-Sig"
    private const val REQUEST_TAG = "noxa-home-v2"
    private const val REPLY_TAG = "noxa-home-v2-reply"

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) { val v = b.toInt() and 0xFF; sb.append(digits[v ushr 4]).append(digits[v and 0x0F]) }
        return sb.toString()
    }

    /** The signing key: SHA-256 of the key as typed, so the server needs only the hash it already keeps. */
    fun hmacKey(key: String): ByteArray = sha256(key.toByteArray(Charsets.UTF_8))

    private fun hmac(k: ByteArray, text: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(k, "HmacSHA256"))
        return hex(mac.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    /** The request's signature (header X-Home-Sig). The time and body are in it, so it can't be replayed later
     *  or put on another body. */
    fun requestSig(k: ByteArray, path: String, deviceId: String, timeSec: Long, body: ByteArray): String =
        hmac(k, "$REQUEST_TAG\n$path\n${deviceId.lowercase()}\n$timeSec\n${hex(sha256(body))}")

    /** The answer's signature (header X-Home-Reply-Sig). The request's signature is in it, so an old answer
     *  can't be passed off as the answer to this request. */
    fun replySig(k: ByteArray, requestSig: String, status: Int, replyBody: ByteArray): String =
        hmac(k, "$REPLY_TAG\n$requestSig\n$status\n${hex(sha256(replyBody))}")

    /** Did the home server sign this status and exact body for [requestSig]? No header = no. Constant-time. */
    fun verifyReply(k: ByteArray, requestSig: String, status: Int, replyBody: ByteArray, header: String?): Boolean {
        if (header == null || requestSig.isEmpty()) return false
        val want = replySig(k, requestSig, status, replyBody).toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(want, header.trim().lowercase().toByteArray(Charsets.US_ASCII))
    }

    /** A header's value in a raw HTTP reply, the name in any case; null if it isn't there (or the head never ended). */
    fun header(reply: String, name: String): String? {
        val head = reply.substringBefore("\r\n\r\n", "")
        for (line in head.split("\r\n").drop(1)) {                 // drop the status line
            val i = line.indexOf(':')
            if (i > 0 && line.substring(0, i).trim().equals(name, ignoreCase = true)) return line.substring(i + 1).trim()
        }
        return null
    }
}
