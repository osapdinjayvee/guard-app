package com.minsu.guardapp.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypts the bearer token with an AES-256/GCM key held in the AndroidKeyStore, and stores
 * only the ciphertext in DataStore. The key material never leaves the keystore, so the token
 * is unreadable from a backup, a rooted file pull, or `adb run-as` on a debuggable build.
 *
 * AndroidKeyStore AES/GCM is available from API 23, comfortably below this app's minSdk 24.
 */
@Singleton
class KeystoreTokenStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : TokenStore {

    override val hasToken: Flow<Boolean> =
        dataStore.data.map { it[CIPHERTEXT] != null }.distinctUntilChanged()

    override suspend fun token(): String? {
        val stored = dataStore.data.first()[CIPHERTEXT] ?: return null
        return runCatching { decrypt(stored) }.getOrElse {
            // The key is gone or no longer usable — a factory reset, a restored backup, or a
            // changed lock screen can all invalidate it. The ciphertext is now permanently
            // unreadable, so drop it and force a fresh login rather than failing forever.
            clear()
            null
        }
    }

    override suspend fun save(token: String) {
        val payload = encrypt(token)
        dataStore.edit { it[CIPHERTEXT] = payload }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(CIPHERTEXT) }
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // GCM's IV must be unique per encryption but need not be secret; prepend it.
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        iv.copyInto(combined, destinationOffset = 1)
        ciphertext.copyInto(combined, destinationOffset = 1 + iv.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        val ivSize = combined[0].toInt()
        val iv = combined.copyOfRange(1, 1 + ivSize)
        val ciphertext = combined.copyOfRange(1 + ivSize, combined.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No user-authentication requirement: a guard syncs attendance in the
                // background, when the device may be locked.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "guardapp.session.token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        val CIPHERTEXT = stringPreferencesKey("session_token_ciphertext")
    }
}
