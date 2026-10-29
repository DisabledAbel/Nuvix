/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Turns a [PlaybackException] into something a person can act on.
 *
 * "ERROR_CODE_IO_BAD_HTTP_STATUS" tells a user nothing. Worse, the habit of showing a raw
 * error code on screen is what led one player to *remove* its 403 display entirely as a
 * "fix" — hiding the symptom while the stream still failed. The right answer is to say what
 * probably went wrong and what to try.
 */
@OptIn(UnstableApi::class)
object PlaybackErrors {

    /** Bounded cause inspection; all stored results are fixed categories. */
    fun recoveryFailure(error: PlaybackException): RecoveryFailure {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.let {
            return when (it.responseCode) {
                401 -> RecoveryFailure.AUTHENTICATION
                403, 451 -> RecoveryFailure.AUTHORIZATION
                404, 410 -> RecoveryFailure.MISSING_STREAM
                429 -> RecoveryFailure.RATE_LIMIT
                408, 504 -> RecoveryFailure.TIMEOUT
                in 500..599 -> RecoveryFailure.NETWORK
                else -> RecoveryFailure.UNKNOWN
            }
        }
        if (causes.any { it is UnknownHostException }) return RecoveryFailure.DNS
        if (causes.any { it is SocketTimeoutException }) return RecoveryFailure.TIMEOUT
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> RecoveryFailure.NETWORK
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> RecoveryFailure.TIMEOUT
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> RecoveryFailure.LIVE_WINDOW
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> RecoveryFailure.SOURCE_READ
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> RecoveryFailure.MISSING_STREAM
            in 3000..3999 -> RecoveryFailure.UNSUPPORTED
            in 4000..4999 -> RecoveryFailure.DECODER
            in 5000..5999 -> RecoveryFailure.DEVICE_RESOURCE
            in 6000..6999 -> RecoveryFailure.DRM
            else -> RecoveryFailure.UNKNOWN
        }
    }

    /** Retry-After delta seconds and HTTP dates; never retry earlier than a provider asks. */
    fun retryAfterMs(error: Throwable, wallTimeMs: Long = System.currentTimeMillis()): Long {
        val response = generateSequence(error) { it.cause }.take(8)
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull() ?: return 0
        val value = response.headerFields.entries.firstOrNull { it.key.equals("Retry-After", true) }
            ?.value?.firstOrNull()?.trim() ?: return 0
        value.toLongOrNull()?.let { return it.coerceIn(0, Long.MAX_VALUE / 1000) * 1000 }
        return runCatching {
            (java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli() - wallTimeMs).coerceAtLeast(0)
        }.getOrDefault(0)
    }

    /** Recovery UI uses the same fixed actionable messages even after Media3 clears its error. */
    fun describeRecovery(category: RecoveryFailure): String = when (category) {
        RecoveryFailure.AUTHENTICATION -> describeHttpStatus(401)
        RecoveryFailure.AUTHORIZATION -> describeHttpStatus(403)
        RecoveryFailure.RATE_LIMIT -> describeHttpStatus(429)
        RecoveryFailure.MISSING_STREAM -> describeHttpStatus(404)
        RecoveryFailure.CONNECTION_LIMIT -> "This account's configured connections are in use. Retry when capacity is available."
        RecoveryFailure.UNSUPPORTED, RecoveryFailure.DECODER -> "This device cannot play this format. Choose another supported quality or authorized source."
        RecoveryFailure.DRM -> "This protected stream requires provider-supported access. Choose another authorized source."
        RecoveryFailure.DEVICE_RESOURCE -> "Playback stopped because device resources are unavailable. Retry or use fewer playback panels."
        RecoveryFailure.DNS, RecoveryFailure.NETWORK, RecoveryFailure.TIMEOUT -> "Unable to reconnect. Check the device network and provider availability, then Retry."
        else -> "Playback interrupted. Unable to reconnect to this channel. Retry or choose another authorized source."
    }

    /** Maps player failures to actionable messages without exposing private stream credentials. */
    fun describe(error: PlaybackException): String {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.let {
            return describeHttpStatus(it.responseCode)
        }
        if (causes.any { it is UnknownHostException }) {
            return "Can't reach the server. Check this device is online and the address is right."
        }
        if (causes.any { it is SocketTimeoutException }) {
            return "The server stopped responding. It may be overloaded — try again in a moment."
        }

        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Playback timed out. Check the connection and try again."
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                "Lost connection to the server. Check the network and try again."

            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
                "The server sent a web page instead of a stream. The link may have expired."

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                "This stream is malformed and can't be played."

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                "This device has no decoder for that format."

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                "This device can't decode that video format. A different quality may work."

            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
                "Audio playback failed. Try another audio track or restart playback."
            PlaybackException.ERROR_CODE_DECODING_FAILED ->
                if ((error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat?.sampleMimeType?.startsWith("audio/") == true)
                    "The audio decoder failed. Try another audio track or supported format."
                else "The video decoder failed. Try another quality or supported format."
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "The playback source is unavailable. Check access to the stream or recording."
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                "Playback fell too far behind the live edge. Reloading."

            else -> "Playback failed. Try another stream or check source access."
        }
    }

    /** Maps an HTTP response status to a playback-facing explanation. */
    fun describeHttpStatus(code: Int): String = when (code) {
        401 -> "The server rejected these credentials. Check the username and password."
        403 -> "The server refused this stream (403). Often this means the account is already " +
            "streaming on another device, or the provider is blocking this app's User-Agent — " +
            "you can change it in the source's advanced settings."
        404 -> "That channel no longer exists on the server. Refresh the channel list."
        405 -> "The server rejected the request (405). The stream address looks wrong for this " +
            "provider — try switching the source between Xtream and playlist mode."
        429 -> "Too many requests. The provider is rate-limiting this device."
        451 -> "Blocked for legal reasons in this region."
        in 500..599 -> "The provider's server is having problems ($code)."
        else -> "The server returned HTTP $code."
    }
}
