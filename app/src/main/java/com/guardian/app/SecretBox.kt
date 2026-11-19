package com.guardian.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * v1.16 (security review of 19 Nov 2026): the WireGuard config holds a private
 * key and used to sit in the app's preferences as plain text. It is now
 * encrypted with an AES-256-GCM key that lives in the Android Keystore and
 * never leaves it, so a copy of the app's files alone is useless.
 *
 * Never loses a config: if the Keystore can't encrypt on some device, the old
 * plain form is kept, exactly as before.
 */
object SecretBox {
    private const val TAG = "Guardian"
    private const val ALIAS = "noxa_tunnel_v1"
    private const val PREFIX = "v1:"
    const val KEY_CONFIG_ENC = "wg_config_enc"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    private fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    private fun open(sealed: String): String {
        val raw = Base64.decode(sealed.removePrefix(PREFIX), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    /** The saved tunnel config ("" if none). Moves an old plain-text one into the encrypted form. */
    fun loadTunnelConfig(ctx: Context): String {
        val p = ctx.getSharedPreferences(TunnelActivity.PREFS, Context.MODE_PRIVATE)
        val enc = p.getString(KEY_CONFIG_ENC, "").orEmpty()
        if (enc.isNotEmpty()) {
            return try { open(enc) } catch (e: Exception) { Log.w(TAG, "tunnel config can't be opened: $e"); "" }
        }
        val plain = p.getString(TunnelActivity.KEY_CONFIG, "").orEmpty()
        if (plain.isNotEmpty()) saveTunnelConfig(ctx, plain)
        return plain
    }

    fun saveTunnelConfig(ctx: Context, text: String) {
        val p = ctx.getSharedPreferences(TunnelActivity.PREFS, Context.MODE_PRIVATE)
        if (text.isEmpty()) { p.edit().remove(KEY_CONFIG_ENC).remove(TunnelActivity.KEY_CONFIG).apply(); return }
        try {
            val sealed = seal(text)
            if (open(sealed) != text) throw IllegalStateException("round trip differs")
            p.edit().putString(KEY_CONFIG_ENC, sealed).remove(TunnelActivity.KEY_CONFIG).apply()
        } catch (e: Exception) {
            Log.w(TAG, "keystore unavailable, tunnel config kept as before: $e")
            p.edit().putString(TunnelActivity.KEY_CONFIG, text).remove(KEY_CONFIG_ENC).apply()
        }
    }
}
