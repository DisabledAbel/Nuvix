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
import app.opentv.core.PrivateSettingsVault
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PrivateSettingsVaultTest {
    private lateinit var context: Application
    private lateinit var encrypted: SharedPreferences
    private lateinit var plain: SharedPreferences
    private lateinit var wrappingKey: SecretKey

    @Before
    fun reset() {
        context = RuntimeEnvironment.getApplication()
        encrypted = context.getSharedPreferences("nuvix_private_settings", Context.MODE_PRIVATE)
        plain = context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE)
        encrypted.edit().clear().commit()
        plain.edit().clear().commit()
        wrappingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    @Test
    fun `absent setting returns its default without accessing the keystore`() {
        val unavailable = PrivateSettingsVault(context) { error("Keystore must not be accessed") }

        assertThat(unavailable.getString("absent", "default")).isEqualTo("default")
        assertThat(unavailable.getString("absent")).isNull()
    }

    @Test
    fun `secret settings are encrypted with authenticated key specific metadata`() {
        vault().putString("smb_password", "private-nas-password")
        val envelope = encrypted.getString("smb_password", null)!!
        val parts = envelope.split(':')

        assertThat(envelope).doesNotContain("private-nas-password")
        assertThat(parts[0]).isEqualTo("v1")
        val decryptor = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
            updateAAD("nuvix-private-setting:v1:smb_password".toByteArray(Charsets.UTF_8))
        }
        assertThat(String(decryptor.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)), Charsets.UTF_8))
            .isEqualTo("private-nas-password")
        assertThat(vault().getString("smb_password")).isEqualTo("private-nas-password")
    }

    @Test
    fun `migration removes selected plaintext only after encrypted values are recoverable`() {
        plain.edit().putString("smb_password", "private-nas-password")
            .putString("tmdb_api_key", "private-api-key")
            .putString("theme_mode", "DARK").commit()

        vault().migrateFrom(plain, setOf("smb_password", "tmdb_api_key"))

        assertThat(plain.contains("smb_password")).isFalse()
        assertThat(plain.contains("tmdb_api_key")).isFalse()
        assertThat(plain.getString("theme_mode", null)).isEqualTo("DARK")
        assertThat(vault().getString("smb_password")).isEqualTo("private-nas-password")
        assertThat(vault().getString("tmdb_api_key")).isEqualTo("private-api-key")
    }

    @Test
    fun `interrupted migration finishes without replacing a matching encrypted copy`() {
        vault().putString("smb_password", "private-nas-password")
        val envelope = encrypted.getString("smb_password", null)
        plain.edit().putString("smb_password", "private-nas-password").commit()

        vault().migrateFrom(plain, setOf("smb_password"))

        assertThat(plain.contains("smb_password")).isFalse()
        assertThat(encrypted.getString("smb_password", null)).isEqualTo(envelope)
        assertThat(vault().getString("smb_password")).isEqualTo("private-nas-password")
    }

    @Test
    fun `migration conflict preserves both copies and all other pending keys`() {
        vault().putString("smb_password", "existing-password")
        plain.edit().putString("smb_password", "conflicting-password")
            .putString("tmdb_api_key", "private-api-key").commit()
        val originalEncrypted = encrypted.all.toMap()
        val originalPlain = plain.all.toMap()

        assertThrows(IllegalStateException::class.java) {
            vault().migrateFrom(plain, linkedSetOf("tmdb_api_key", "smb_password"))
        }

        assertThat(encrypted.all).isEqualTo(originalEncrypted)
        assertThat(plain.all).isEqualTo(originalPlain)
    }

    @Test
    fun `wrong wrapping key cannot remove plaintext during migration`() {
        vault().putString("smb_password", "private-nas-password")
        plain.edit().putString("smb_password", "private-nas-password").commit()
        val savedEnvelope = encrypted.getString("smb_password", null)
        val wrongKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        assertThrows(IllegalStateException::class.java) {
            PrivateSettingsVault(context) { wrongKey }.migrateFrom(plain, setOf("smb_password"))
        }

        assertThat(plain.getString("smb_password", null)).isEqualTo("private-nas-password")
        assertThat(encrypted.getString("smb_password", null)).isEqualTo(savedEnvelope)
    }

    @Test
    fun `ciphertext copied to another setting key is rejected without deleting it`() {
        vault().putString("smb_password", "private-nas-password")
        val envelope = encrypted.getString("smb_password", null)
        encrypted.edit().putString("tmdb_api_key", envelope).commit()

        assertThrows(IllegalStateException::class.java) { vault().getString("tmdb_api_key") }

        assertThat(encrypted.getString("tmdb_api_key", null)).isEqualTo(envelope)
        assertThat(vault().getString("smb_password")).isEqualTo("private-nas-password")
    }

    private fun vault() = PrivateSettingsVault(context) { wrappingKey }
}
