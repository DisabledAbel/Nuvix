/* Nuvix modifications, Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The SQLCipher password is 32 random bytes. Only an authenticated encrypted envelope is stored
 * in private preferences; its wrapping AES key never leaves AndroidKeyStore. Neither value is a
 * provider password. Key loss is an error, never permission to reset a user's database.
 */
class DatabaseKeyStore internal constructor(
    context: Context,
    private val keyProvider: () -> SecretKey,
) {
    private val storage = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    constructor(context: Context) : this(context, {
        val hasEnvelope = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .contains(WRAPPED_KEY)
        androidWrappingKey(requireExisting = hasEnvelope)
    })

    fun getOrCreate(encryptedDatabaseExists: Boolean = false): ByteArray = synchronized(lock) {
        try {
            if (storage.contains(WRAPPED_KEY)) {
                val encoded = storage.getString(WRAPPED_KEY, null) ?: error("Missing wrapped key envelope")
                val parts = encoded.split(':')
                check(parts.size == 3 && parts[0] == "v1") { "Invalid wrapped key envelope" }
                val iv = Base64.decode(parts[1], Base64.NO_WRAP)
                val encrypted = Base64.decode(parts[2], Base64.NO_WRAP)
                check(iv.size == 12 && encrypted.size == 48) { "Invalid wrapped key size" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, iv))
                cipher.updateAAD(associatedData)
                cipher.doFinal(encrypted).also { check(it.size == 32) { "Invalid database key size" } }
            } else {
                check(!encryptedDatabaseExists) { "Database exists without its wrapped key" }
                val password = ByteArray(32).also { SecureRandom().nextBytes(it) }
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
                    cipher.updateAAD(associatedData)
                    val encrypted = cipher.doFinal(password)
                    val envelope = "v1:${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(encrypted, Base64.NO_WRAP)}"
                    check(storage.edit().putString(WRAPPED_KEY, envelope).commit()) { "Wrapped key could not be saved" }
                    password
                } catch (failure: Exception) {
                    password.fill(0)
                    throw failure
                }
            }
        } catch (failure: Exception) {
            throw IllegalStateException("The encrypted database key is unavailable. Existing app data has been retained.", failure)
        }
    }

    companion object {
        private const val PREFERENCES = "nuvix_database_key"
        private const val WRAPPED_KEY = "wrapped_key"
        private const val ALIAS = "nuvix.room.wrapping-key.v1"
        private val associatedData = "nuvix-room-database-key:v1".toByteArray(Charsets.UTF_8)
        private val lock = Any()

        private fun androidWrappingKey(requireExisting: Boolean): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(ALIAS)) {
                return (keyStore.getEntry(ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
            }
            check(!requireExisting) { "AndroidKeyStore wrapping key is missing" }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    // Playback/recording must remain usable with a locked or sleeping television.
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
