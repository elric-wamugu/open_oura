package org.openoura.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TAG = "OpenOuraBackup"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val WRAPPER_ALIAS = "backup_passphrase_wrapper"
private const val PREFS = "backup_secret"
private const val PREF_PAYLOAD = "payload"
private const val IV_BYTES = 12
private const val GCM_TAG_BITS = 128

/**
 * The passphrase for *automatic* backups, held under the Android Keystore.
 *
 * A manual backup asks for its passphrase and forgets it. A scheduled one runs with nobody
 * there to ask, so the passphrase has to be somewhere — and this is the trade the user
 * makes by switching automatic backup on. It is kept the way the ring key is
 * ([RingKeyStore]): encrypted by an AES/GCM key that never leaves the Keystore, with only
 * the ciphertext in SharedPreferences. That keeps it out of adb pulls and other apps; it
 * does not keep it from this app's own process on an unlocked, compromised phone.
 *
 * Its own wrapper key and its own file, so turning automatic backup off removes the
 * passphrase without going near the ring key.
 */
class BackupSecretStore(private val ctx: Context) {

    private val prefs get() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val hasPassphrase: Boolean get() = prefs.contains(PREF_PAYLOAD)

    fun read(): String? = runCatching {
        val encoded = prefs.getString(PREF_PAYLOAD, null) ?: return null
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrapperKey(), GCMParameterSpec(GCM_TAG_BITS, blob, 0, IV_BYTES))
        String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
    }.getOrElse {
        // The Keystore entry can be invalidated (a restore onto another phone, for one).
        Log.e(TAG, "reading the backup passphrase failed", it)
        null
    }

    fun save(passphrase: String): Boolean = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapperKey())
        val ciphertext = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))
        // Committed, not applied: the caller schedules backups on the strength of this.
        prefs.edit(commit = true) {
            putString(PREF_PAYLOAD, Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP))
        }
        true
    }.getOrElse {
        Log.e(TAG, "storing the backup passphrase failed", it)
        false
    }

    /** Forget the passphrase and the key that wrapped it. */
    fun clear() {
        prefs.edit(commit = true) { remove(PREF_PAYLOAD) }
        runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(WRAPPER_ALIAS) }
    }

    private fun wrapperKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(WRAPPER_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(WRAPPER_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // No user-authentication gate: the backup runs overnight with the screen locked.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}
