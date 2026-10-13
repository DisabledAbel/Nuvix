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

    fun describe(error: PlaybackException): String {
        (error.cause as? HttpDataSource.InvalidResponseCodeException)?.let {
            return describeHttpStatus(it.responseCode)
        }
        if (error.cause is UnknownHostException) {
            return "Can't reach the server. Check this device is online and the address is right."
        }
        if (error.cause is SocketTimeoutException) {
            return "The server stopped responding. It may be overloaded — try again in a moment."
        }

        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Lost connection to the server."

            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
                "The server sent a web page instead of a stream. The link may have expired."

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                "This stream is malformed and can't be played."

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                "This device has no decoder for that format."

            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                "This device can't decode that video format. A different quality may work."

            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                "Playback fell too far behind the live edge. Reloading."

            else -> "Playback failed (${error.errorCodeName})."
        }
    }

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
