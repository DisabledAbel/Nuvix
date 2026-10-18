/*
 * This file is part of Nuvix, an independent modified version of OpenTV.
 * Copyright (C) 2026 The Nuvix Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.security

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import app.opentv.core.DatabaseKeyStore
import com.google.common.truth.Truth.assertThat
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Regression tests for database key store. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DatabaseKeyStoreTest {
    private lateinit var context: Application
    private lateinit var preferences: SharedPreferences
    private lateinit var wrappingKey: SecretKey

    /** Creates isolated fixtures before each test. */
    @Before
    fun reset() {
        context = RuntimeEnvironment.getApplication()
        preferences = context.getSharedPreferences("nuvix_database_key", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        wrappingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    /** Checks: new database receives a random 256 bit key that survives store recreation. */
    @Test
    fun `new database receives a random 256 bit key that survives store recreation`() {
        val key = store().getOrCreate()
        val restored = store().getOrCreate(encryptedDatabaseExists = true)

        assertThat(key).hasLength(32)
        assertThat(key.any { it != 0.toByte() }).isTrue()
        assertThat(restored).isEqualTo(key)

        preferences.edit().clear().commit()
        assertThat(store().getOrCreate()).isNotEqualTo(key)
    }

    /** Checks: only the authenticated encrypted key envelope is persisted. */
    @Test
    fun `only the authenticated encrypted key envelope is persisted`() {
        val key = store().getOrCreate()
        val envelope = preferences.getString("wrapped_key", null)!!
        val parts = envelope.split(':')

        assertThat(preferences.all.keys).containsExactly("wrapped_key")
        assertThat(parts).hasSize(3)
        assertThat(parts[0]).isEqualTo("v1")
        assertThat(Base64.decode(parts[1], Base64.NO_WRAP)).hasLength(12)
        assertThat(Base64.decode(parts[2], Base64.NO_WRAP)).hasLength(32 + 16)
        assertThat(envelope).doesNotContain(Base64.encodeToString(key, Base64.NO_WRAP))

        val decryptor = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
            updateAAD("nuvix-room-database-key:v1".toByteArray(Charsets.UTF_8))
        }
        assertThat(decryptor.doFinal(Base64.decode(parts[2], Base64.NO_WRAP))).isEqualTo(key)
    }

    /** Checks: clearing returned passphrase bytes does not erase the persisted database key. */
    @Test
    fun `clearing returned passphrase bytes does not erase the persisted database key`() {
        val store = store()
        val key = store.getOrCreate()
        val expected = key.copyOf()
        key.fill(0)

        assertThat(store.getOrCreate(encryptedDatabaseExists = true)).isEqualTo(expected)
        assertThat(store().getOrCreate(encryptedDatabaseExists = true)).isEqualTo(expected)
    }

    /** Checks: tampered ciphertext is rejected without replacing the envelope. */
    @Test
    fun `tampered ciphertext is rejected without replacing the envelope`() {
        store().getOrCreate()
        val parts = preferences.getString("wrapped_key", null)!!.split(':')
        val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
        ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
        val altered = "${parts[0]}:${parts[1]}:${Base64.encodeToString(ciphertext, Base64.NO_WRAP)}"
        preferences.edit().putString("wrapped_key", altered).commit()

        assertThrows(IllegalStateException::class.java) {
            store().getOrCreate(encryptedDatabaseExists = true)
        }

        assertThat(preferences.getString("wrapped_key", null)).isEqualTo(altered)
    }

    /** Checks: malformed envelope is rejected without regenerating a key. */
    @Test
    fun `malformed envelope is rejected without regenerating a key`() {
        val malformed = "v1:AA==:AA=="
        preferences.edit().putString("wrapped_key", malformed).commit()

        assertThrows(IllegalStateException::class.java) { store().getOrCreate() }

        assertThat(preferences.getString("wrapped_key", null)).isEqualTo(malformed)
    }

    /** Checks: missing wrapped key for an existing encrypted database fails closed. */
    @Test
    fun `missing wrapped key for an existing encrypted database fails closed`() {
        assertThrows(IllegalStateException::class.java) {
            store().getOrCreate(encryptedDatabaseExists = true)
        }

        assertThat(preferences.all).isEmpty()
    }

    /** Checks: lost wrapping key cannot silently replace an existing data key. */
    @Test
    fun `lost wrapping key cannot silently replace an existing data key`() {
        store().getOrCreate()
        val savedEnvelope = preferences.getString("wrapped_key", null)
        val unavailable = DatabaseKeyStore(context) { throw IllegalStateException("Keystore key unavailable") }

        assertThrows(IllegalStateException::class.java) {
            unavailable.getOrCreate(encryptedDatabaseExists = true)
        }

        assertThat(preferences.getString("wrapped_key", null)).isEqualTo(savedEnvelope)
    }

    /** Checks: wrong wrapping key is rejected while preserving recovery data. */
    @Test
    fun `wrong wrapping key is rejected while preserving recovery data`() {
        store().getOrCreate()
        val savedEnvelope = preferences.getString("wrapped_key", null)
        val wrongKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        assertThrows(IllegalStateException::class.java) {
            DatabaseKeyStore(context) { wrongKey }.getOrCreate(encryptedDatabaseExists = true)
        }

        assertThat(preferences.getString("wrapped_key", null)).isEqualTo(savedEnvelope)
    }

    /** Creates a database-key store using the test's storage and wrapping key. */
    private fun store() = DatabaseKeyStore(context) { wrappingKey }
}
