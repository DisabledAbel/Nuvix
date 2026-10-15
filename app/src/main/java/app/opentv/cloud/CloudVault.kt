/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM ciphertext lives in noBackupFilesDir; the key never leaves Android Keystore. */
internal class CloudVault(context: Context) {
    private val directory = File(context.noBackupFilesDir, "cloud_v1").apply { mkdirs() }
    private val alias = "nuvix.cloud.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    @Synchronized fun read(name: String): String? {
        val file = File(directory, name)
        if (!file.exists()) return null
        require(file.length() <= 96L * 1024 * 1024) { "Encrypted cloud storage is too large." }
        val bytes = Base64.decode(file.readText(), Base64.NO_WRAP)
        require(bytes.size > 12) { "Encrypted cloud storage is invalid; reset cloud setup." }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD(name.toByteArray())
            String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    }

    @Synchronized fun write(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val target = File(directory, name)
        val temp = File(directory, "$name.tmp")
        temp.outputStream().use { stream ->
            stream.write(Base64.encode(bytes, Base64.NO_WRAP))
            stream.fd.sync()
        }
        check(temp.renameTo(target)) { "Unable to save encrypted cloud state." }
    }

    @Synchronized fun remove(name: String) {
        for (file in listOf(File(directory, name), File(directory, "$name.tmp"))) {
            check(!file.exists() || file.delete()) { "Unable to remove encrypted cloud state." }
        }
    }
}
