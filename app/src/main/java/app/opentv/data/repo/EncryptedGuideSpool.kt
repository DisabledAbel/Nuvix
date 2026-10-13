/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv.data.repo

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Encrypts temporary XML in independently authenticated 64 KiB frames. A single GCM stream can
 * retain the entire document until its tag is verified on some providers, defeating a streaming
 * parser's memory bound. Frames keep both encryption and decryption bounded; the one-use key
 * never reaches disk. This is an internal temporary format, not a portable guide export format.
 */
internal object EncryptedGuideSpool {
    private const val FRAME_BYTES = 64 * 1024

    suspend fun write(input: InputStream, target: File, maxBytes: Long): SecretKey {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        DataOutputStream(target.outputStream().buffered()).use { output ->
            val buffer = ByteArray(FRAME_BYTES)
            var total = 0L
            var frameIndex = 0L
            while (true) {
                var filled = 0
                while (filled < buffer.size) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer, filled, buffer.size - filled)
                    if (count < 0) break
                    if (count == 0) continue
                    filled += count
                    total += count
                    check(total <= maxBytes) { "Guide exceeds the 256 MB download limit." }
                }
                if (filled == 0) break
                writeFrame(output, key, buffer, filled, frameIndex++)
            }
            // An authenticated zero-length frame marks completion, distinguishing EOF/truncation.
            writeFrame(output, key, buffer, 0, frameIndex)
        }
        return key
    }

    private fun writeFrame(output: DataOutputStream, key: SecretKey, buffer: ByteArray, count: Int, index: Long) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(frameIdentity(index, count))
        }
        val encrypted = cipher.doFinal(buffer, 0, count)
        check(cipher.iv.size == 12) { "Unexpected GCM initialization vector size." }
        output.writeInt(count)
        output.write(cipher.iv)
        output.write(encrypted)
    }

    fun open(file: File, key: SecretKey): InputStream = object : InputStream() {
        private val input = DataInputStream(file.inputStream().buffered())
        private var frame = ByteArray(0)
        private var position = 0
        private var ended = false
        private var frameIndex = 0L

        private fun nextFrame(): Boolean {
            if (ended) return false
            val count = input.readInt() // EOF before the completion frame is an error.
            if (count !in 0..FRAME_BYTES) throw IOException("Invalid encrypted guide frame.")
            val iv = ByteArray(12).also { input.readFully(it) }
            val encrypted = ByteArray(count + 16).also { input.readFully(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                updateAAD(frameIdentity(frameIndex++, count))
            }
            frame = try { cipher.doFinal(encrypted) } catch (_: Exception) {
                throw IOException("Encrypted guide integrity verification failed.")
            }
            position = 0
            if (count == 0) {
                if (input.read() != -1) throw IOException("Unexpected encrypted guide trailing data.")
                ended = true
                return false
            }
            return true
        }

        override fun read(): Int {
            if (position == frame.size && !nextFrame()) return -1
            return frame[position++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
            if (length == 0) return 0
            if (position == frame.size && !nextFrame()) return -1
            val count = minOf(length, frame.size - position)
            frame.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        override fun close() { input.close() }
    }

    private fun frameIdentity(index: Long, count: Int): ByteArray = ByteBuffer.allocate(12).putLong(index).putInt(count).array()
}
