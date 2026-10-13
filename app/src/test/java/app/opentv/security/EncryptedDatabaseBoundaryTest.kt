/*
 * This file is part of Nuvix, an independent modified version of OpenTV.
 * Copyright (C) 2026 The Nuvix Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.security

import app.opentv.data.db.EncryptedDatabaseMigration
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedDatabaseBoundaryTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `missing database is not treated as a plaintext migration`() {
        val absent = File(files.root, "not-created.db")

        assertThat(EncryptedDatabaseMigration.isPlaintext(absent)).isFalse()
        EncryptedDatabaseMigration.convertIfNeeded(absent, ByteArray(32))
        assertThat(absent.exists()).isFalse()
    }

    @Test
    fun `empty and truncated headers are not classified as plaintext databases`() {
        val empty = files.newFile("empty.db")
        val truncated = files.newFile("truncated.db").apply {
            writeBytes("SQLite format 3".toByteArray(Charsets.US_ASCII))
        }

        assertThat(EncryptedDatabaseMigration.isPlaintext(empty)).isFalse()
        assertThat(EncryptedDatabaseMigration.isPlaintext(truncated)).isFalse()
    }

    @Test
    fun `legacy SQLite header is recognized at the beginning of the file`() {
        val plaintext = files.newFile("legacy.db").apply {
            writeBytes(sqliteHeader + ByteArray(64))
        }

        assertThat(EncryptedDatabaseMigration.isPlaintext(plaintext)).isTrue()
    }

    @Test
    fun `similar headers and embedded SQLite strings do not trigger conversion`() {
        val incorrect = files.newFile("incorrect.db").apply {
            writeBytes(sqliteHeader.copyOf().also { it[it.lastIndex] = 1 } + ByteArray(64))
        }
        val embedded = files.newFile("embedded.db").apply {
            writeBytes(byteArrayOf(1) + sqliteHeader + ByteArray(64))
        }

        assertThat(EncryptedDatabaseMigration.isPlaintext(incorrect)).isFalse()
        assertThat(EncryptedDatabaseMigration.isPlaintext(embedded)).isFalse()
    }

    @Test
    fun `opaque database bytes are retained without attempting plaintext conversion`() {
        val bytes = ByteArray(64) { 0x5A.toByte() }
        val opaque = files.newFile("opaque.db").apply { writeBytes(bytes) }

        assertThat(EncryptedDatabaseMigration.isPlaintext(opaque)).isFalse()
        EncryptedDatabaseMigration.convertIfNeeded(opaque, ByteArray(32))

        assertThat(opaque.readBytes()).isEqualTo(bytes)
        assertThat(File(files.root, "opaque.db.encrypted-tmp").exists()).isFalse()
    }

    private val sqliteHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
}
