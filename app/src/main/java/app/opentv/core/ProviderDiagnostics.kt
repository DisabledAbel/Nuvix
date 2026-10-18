/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv.core

import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Never surface exception text: request URLs and signed playlist query strings can be credentials. */
object ProviderDiagnostics {
    /** Turns provider failures into display-safe diagnostics without exposing request credentials. */
    fun describe(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        return when {
            causes.any { it is UnknownHostException } -> "Cannot reach the provider. Check the server address and network connection."
            causes.any { it is SocketTimeoutException } -> "The provider timed out. Try again in a moment."
            causes.any { it is SSLException } -> "A secure connection could not be established. Check the provider's HTTPS address and certificate."
            error is XtreamApi.XtreamException && error.message == "Username or password rejected by the server." ->
                "The provider rejected the username or password."
            error is StalkerApi.StalkerException -> "The portal did not accept this request. Check the portal address and authorized MAC."
            error is IllegalArgumentException -> "The source address or settings are invalid."
            causes.any { it is IOException } -> "The provider connection failed. Check the network and try again."
            else -> "The provider request failed. Check account access, source type, and server settings."
        }
    }
}
