/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv

import app.opentv.data.repo.EncryptedGuideSpool
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class EncryptedGuideSpoolTest {
    @Test fun `short reads round trip across frame boundaries without exposing plaintext`() = runBlocking<Unit> {
        val file = Files.createTempFile("guide-spool-test", ".encrypted").toFile()
        try {
            val content = ("signed-artwork-password-private ".repeat(10_000)).toByteArray()
            val input = object : ByteArrayInputStream(content) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(length, 7))
            }
            val key = EncryptedGuideSpool.write(input, file, content.size.toLong())
            assertThat(file.readText(Charsets.ISO_8859_1)).doesNotContain("signed-artwork-password-private")
            assertThat(file.length()).isLessThan(content.size + 1_000L)
            assertThat(EncryptedGuideSpool.open(file, key).use { it.readBytes().toList() }).isEqualTo(content.toList())
        } finally { file.delete() }
    }

    @Test fun `authentication catches modified data and missing completion frames`() = runBlocking<Unit> {
        val file = Files.createTempFile("guide-spool-test", ".encrypted").toFile()
        try {
            val key = EncryptedGuideSpool.write("<tv>guide</tv>".byteInputStream(), file, 1_000)
            val original = file.readBytes()
            val tampered = original.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
            file.writeBytes(tampered)
            assertThrows(IOException::class.java) { EncryptedGuideSpool.open(file, key).use { it.readBytes() } }
            file.writeBytes(original.copyOf(original.size - 32))
            assertThrows(IOException::class.java) { EncryptedGuideSpool.open(file, key).use { it.readBytes() } }
        } finally { file.delete() }
    }

    @Test fun `download size guard prevents an unbounded cache file`() = runBlocking<Unit> {
        val file = Files.createTempFile("guide-spool-test", ".encrypted").toFile()
        try {
            val failure = runCatching { EncryptedGuideSpool.write(ByteArray(1_001).inputStream(), file, 1_000) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(file.length()).isEqualTo(0)
        } finally { file.delete() }
    }
}
