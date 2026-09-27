package com.jarvis.remote

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Holds the one thing that makes pairing permanent: a 256-bit secret,
 * received exactly once (at pairing time) and never sent again. Every
 * later request instead sends totpProof() — HMAC-SHA256(secret, current
 * time step), computed fresh, offline, from nothing but this stored secret
 * and the phone's own clock. This is why nothing here needs to survive as
 * a "session": there IS no session, only this secret, so a phone restart,
 * an app restart, or a JARVIS restart changes nothing about whether the
 * next proof this computes is accepted.
 *
 * Stored in EncryptedSharedPreferences (AES-256, backed by the Android
 * Keystore) rather than plain SharedPreferences, since this secret is the
 * device's entire identity to JARVIS going forward — losing it to another
 * app reading plain-text prefs would be exactly as bad as losing a
 * permanent password.
 *
 * IMPORTANT: totpProof()'s algorithm must byte-for-byte match
 * remote_pairing.py's _totp_proof() on the server (same HMAC-SHA256,
 * same "seconds since epoch // step_seconds" as the message, same lower-
 * case hex encoding of the digest) or every request will be rejected as
 * an invalid proof. This was cross-checked against the actual Python
 * implementation before shipping — see the project notes.
 */
class PairingRepository(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "jarvis_remote_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** Generated once, kept forever — this is what remote_app_list_devices
     * shows JARVIS as identifying this phone, separate from the secret. */
    val deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (existing != null) return existing
            val fresh = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
            return fresh
        }

    var deviceName: String?
        get() = prefs.getString(KEY_DEVICE_NAME, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    private var secretHex: String?
        get() = prefs.getString(KEY_SECRET, null)
        set(value) = prefs.edit().putString(KEY_SECRET, value).apply()

    var stepSeconds: Int
        get() = prefs.getInt(KEY_STEP_SECONDS, 30)
        set(value) = prefs.edit().putInt(KEY_STEP_SECONDS, value).apply()

    val isPaired: Boolean
        get() = secretHex != null && serverUrl != null

    fun completePairing(url: String, secretHexValue: String, stepSecondsValue: Int) {
        serverUrl = url
        secretHex = secretHexValue
        stepSeconds = stepSecondsValue
    }

    fun clearPairing() {
        prefs.edit().remove(KEY_SECRET).remove(KEY_SERVER_URL).apply()
    }

    /** HMAC-SHA256(secret, floor(unix_seconds / step_seconds)), lowercase
     * hex — see the class doc: must match remote_pairing.py exactly. */
    fun totpProof(): String {
        val secret = hexToBytes(secretHex ?: return "")
        val step = System.currentTimeMillis() / 1000L / stepSeconds
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val digest = mac.doFinal(step.toString().toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun hexToBytes(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SECRET = "secret_hex"
        private const val KEY_STEP_SECONDS = "step_seconds"
    }
}
