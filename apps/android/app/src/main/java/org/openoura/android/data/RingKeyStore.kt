package org.openoura.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TAG = "OpenOura"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"

/** Alias of the Keystore-resident AES key that wraps the ring key. Not the ring key itself. */
private const val WRAPPER_ALIAS = "ring_auth_key_wrapper"

private const val PREFS = "ring_key"
private const val PREF_PAYLOAD = "payload"

/** GCM's standard nonce length; the cipher picks the nonce and we prefix it to the blob. */
private const val IV_BYTES = 12
private const val GCM_TAG_BITS = 128

/**
 * The ring's 16-byte app-auth key, held under the Android Keystore.
 *
 * Without this key the ring refuses every history request, so it is the one secret this
 * app holds. Invariant 4 of `docs/android-client-plan.md` is that it never touches a plain
 * file — no `key.hex` on the device, and nothing readable by a backup or an adb pull.
 *
 * **Deviation from the plan doc:** it names `EncryptedSharedPreferences`, but
 * `androidx.security:security-crypto` deprecated that API, and it drags in Tink for what is
 * one 32-character string. This does the same job directly: an AES/GCM key generated inside
 * the Keystore (non-exportable — the raw bytes never enter the app process) encrypts the
 * ring key, and only the ciphertext lands in SharedPreferences. minSdk 26 covers this
 * comfortably; `KeyGenParameterSpec` is API 23+.
 *
 * The key is stored as its 32-char lowercase hex form, which is exactly what
 * `RingSession.sync(dbPath, keyHex, progress)` wants — no conversion at the call site.
 */
class RingKeyStore(private val ctx: Context) {

    private val prefs get() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val hasKey: Boolean get() = prefs.contains(PREF_PAYLOAD)

    /** The stored key as 32 lowercase hex chars, or null if none is stored / it can't be read. */
    fun read(): String? = runCatching {
        val encoded = prefs.getString(PREF_PAYLOAD, null) ?: return null
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            wrapperKey(),
            GCMParameterSpec(GCM_TAG_BITS, blob, 0, IV_BYTES),
        )
        String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.US_ASCII)
    }.getOrElse {
        // Decryption fails if the Keystore entry was invalidated (factory reset, or the
        // user removing their screen lock on some devices). The stored blob is then junk.
        Log.e(TAG, "reading ring key failed", it)
        null
    }

    /**
     * Validate and store `input`. Accepts the key with or without whitespace, colons or
     * dashes. Returns false if it isn't 32 hex characters or the Keystore rejected it.
     */
    fun save(input: String): Boolean = runCatching {
        val hex = normalize(input) ?: return false
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapperKey())
        val ciphertext = cipher.doFinal(hex.toByteArray(Charsets.US_ASCII))
        // commit(), not apply(): the caller reports success to the user immediately, so the
        // write must have actually happened by the time this returns.
        prefs.edit()
            .putString(PREF_PAYLOAD, Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP))
            .commit()
    }.getOrElse {
        Log.e(TAG, "storing ring key failed", it)
        false
    }

    /**
     * Forget the stored key. The Keystore wrapper alias is left in place — it is not itself
     * sensitive, and reusing it avoids a needless key generation on the next import.
     */
    fun clear() {
        prefs.edit().remove(PREF_PAYLOAD).commit()
    }

    /**
     * A short hash of the stored key, for showing *which* key is held without disclosing it.
     * The desktop client identifies a key the same way, so the two can be compared by eye.
     */
    fun fingerprint(): String? {
        val hex = read() ?: return null
        val raw = ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        return digest.take(3).joinToString("") { "%02x".format(it) }
    }

    /** Fetch the wrapping key, generating it on first use. Never leaves the Keystore. */
    private fun wrapperKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(WRAPPER_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAPPER_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Deliberately no user-authentication gate: a sync started by the
                // foreground service has to reach the key with the screen locked.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        /**
         * Reduce user input to the canonical 32-char lowercase hex key, or null if it isn't
         * one. Tolerates the shapes a key actually arrives in: a `.key` file's trailing
         * newline, a copy-paste with spaces, colon- or dash-separated byte pairs.
         */
        fun normalize(input: String): String? {
            val cleaned = input
                .filterNot { it.isWhitespace() || it == ':' || it == '-' }
                .lowercase()
            val isHex = cleaned.length == 32 && cleaned.all { it in "0123456789abcdef" }
            return if (isHex) cleaned else null
        }
    }
}
