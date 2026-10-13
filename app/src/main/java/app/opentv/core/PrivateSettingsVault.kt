/* Nuvix modifications, Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Local encrypted strings for secrets that live outside Room (NAS, API keys, add-on URLs). */
class PrivateSettingsVault internal constructor(context: Context, private val keyProvider: () -> SecretKey) {
    private val storage = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    constructor(context: Context) : this(context, {
        val existing = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).all.isNotEmpty()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(ALIAS)) (keyStore.getEntry(ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        else {
            check(!existing) { "Private settings wrapping key is missing" }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
            }.generateKey()
        }
    })

    fun getString(key: String, defaultValue: String? = null): String? = synchronized(lock) {
        protect {
            val stored = storage.getString(key, null) ?: return@protect defaultValue
            val parts = stored.split(':')
            check(parts.size == 3 && parts[0] == "v1") { "Invalid encrypted setting envelope" }
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[2], Base64.NO_WRAP)
            check(iv.size == 12 && encrypted.size >= 16) { "Invalid encrypted setting size" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, iv))
            cipher.updateAAD(associatedData(key))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }
    }

    fun putString(key: String, value: String) = synchronized(lock) {
        protect {
            check(storage.edit().putString(key, encrypt(key, value)).commit()) { "Encrypted setting could not be saved" }
        }
    }

    /** Write and verify all selected legacy values before removing any plaintext copy. */
    fun migrateFrom(plain: SharedPreferences, keys: Set<String>) = synchronized(lock) {
        protect {
            val legacy = keys.filter { plain.contains(it) }.associateWith { plain.getString(it, null) }
            if (legacy.isEmpty()) return@protect
            // Detect any conflict before preparing/writing another key. All new encrypted copies
            // are committed together, so an interrupted preparation cannot leave a partial batch.
            for ((key, value) in legacy) {
                if (value == null) continue
                if (storage.contains(key)) check(getString(key) == value) { "Encrypted and legacy settings conflict" }
            }
            val prepared = legacy.filter { (key, value) -> value != null && !storage.contains(key) }
                .mapValues { (key, value) -> encrypt(key, requireNotNull(value)) }
            if (prepared.isNotEmpty()) {
                val encryptedEditor = storage.edit()
                prepared.forEach { (key, envelope) -> encryptedEditor.putString(key, envelope) }
                check(encryptedEditor.commit()) { "Encrypted settings could not be saved" }
            }
            for ((key, value) in legacy) {
                if (value == null) continue
                check(getString(key) == value) { "Encrypted setting could not be verified" }
            }
            val editor = plain.edit()
            legacy.keys.forEach { editor.remove(it) }
            check(editor.commit()) { "Legacy secret removal could not be saved" }
        }
    }

    private fun encrypt(key: String, value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD(associatedData(key))
        val bytes = value.toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.doFinal(bytes) } finally { bytes.fill(0) }
        return "v1:${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(encrypted, Base64.NO_WRAP)}"
    }

    private fun <T> protect(action: () -> T): T = try { action() } catch (failure: Exception) {
        throw IllegalStateException("Private settings could not be unlocked. Existing settings have been retained.", failure)
    }

    companion object {
        private const val PREFERENCES = "nuvix_private_settings"
        private const val ALIAS = "nuvix.settings.wrapping-key.v1"
        private val lock = Any()
        private fun associatedData(key: String) = "nuvix-private-setting:v1:$key".toByteArray(Charsets.UTF_8)
    }
}
