/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import android.util.Log
import app.opentv.pairing.PairingHttp
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Serves this device's watch-history bundle to another OpenTV on the same wifi.
 *
 * Deliberately the same shape as [app.opentv.pairing.PairingServer]: no cloud, a short-lived
 * server on the LAN, gated by a six-digit code shown on screen, private-network only, and torn
 * down on a timeout. The bundle is a snapshot taken when sharing starts, served as-is.
 */
class SyncServer(private val scope: CoroutineScope) {

    data class Session(val host: String, val port: Int, val code: String) {
        val address: String get() = "$host:$port"
    }

    sealed interface State {
        data object Idle : State
        data class Sharing(val session: Session) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var code: String = ""
    private var bundle: String = ""
    private var wrongAttempts = 0
    @Volatile private var running = false

    fun start(bundleJson: String) {
        if (running) return
        val host = lanAddress() ?: run {
            _state.value = State.Failed("This device isn't on a network, so it can't share.")
            return
        }
        val random = SecureRandom()
        code = "%06d".format(random.nextInt(1_000_000))
        bundle = bundleJson
        wrongAttempts = 0

        val socket = try {
            ServerSocket(0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open a socket", e)
            _state.value = State.Failed("Could not start sharing on this device.")
            return
        }
        serverSocket = socket
        running = true
        _state.value = State.Sharing(Session(host, socket.localPort, code))

        thread(name = "opentv-sync", isDaemon = true) {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                runCatching { handle(client) }.onFailure { Log.w(TAG, "Sync request failed", it) }
                runCatching { client.close() }
            }
        }

        scope.launch(Dispatchers.Default) {
            delay(TimeUnit.MINUTES.toMillis(TIMEOUT_MINUTES))
            if (running) {
                stop()
                _state.value = State.Failed("Sharing timed out. Start again when you're ready.")
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        code = ""
        bundle = ""
        if (_state.value is State.Sharing) _state.value = State.Idle
    }

    private fun handle(client: Socket) {
        client.soTimeout = SOCKET_TIMEOUT_MILLIS
        if (!PairingHttp.isPrivateAddress(client.inetAddress?.hostAddress)) {
            client.getOutputStream().write(
                PairingHttp.httpResponse("403 Forbidden", "text/plain", "Local network only."),
            )
            return
        }
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val (method, path, query) = PairingHttp.parseRequestLine(requestLine) ?: return
        // Drain headers.
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }

        val output = client.getOutputStream()
        val supplied = query["c"].orEmpty().filter { it.isDigit() }

        if (method != "GET" || !path.startsWith("/export")) {
            output.write(PairingHttp.httpResponse("404 Not Found", "text/plain", "Not found."))
            return
        }
        if (code.isEmpty() || !PairingHttp.secretsMatch(supplied, code)) {
            wrongAttempts++
            if (wrongAttempts >= MAX_ATTEMPTS) {
                output.write(PairingHttp.httpResponse("403 Forbidden", "text/plain", "Too many attempts."))
                stop()
                _state.value = State.Failed("Too many incorrect codes. Sharing cancelled.")
                return
            }
            output.write(PairingHttp.httpResponse("401 Unauthorized", "text/plain", "Wrong code."))
            return
        }
        output.write(PairingHttp.httpResponse("200 OK", "application/json", bundle))
    }

    private fun lanAddress(): String? {
        val candidates = mutableListOf<InetAddress>()
        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (address in nic.inetAddresses) {
                    if (address.isLoopbackAddress) continue
                    if (address.hostAddress?.contains(':') == true) continue // IPv4 only
                    if (PairingHttp.isPrivateAddress(address.hostAddress)) candidates += address
                }
            }
        }
        return candidates
            .sortedBy { it.hostAddress?.startsWith("169.254") == true }
            .firstOrNull()
            ?.hostAddress
    }

    private companion object {
        const val TAG = "OpenTV"
        const val TIMEOUT_MINUTES = 5L
        const val SOCKET_TIMEOUT_MILLIS = 10_000
        const val MAX_ATTEMPTS = 5
    }
}
