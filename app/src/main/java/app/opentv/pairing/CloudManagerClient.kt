/* Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.pairing

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Outbound-only, explicitly opened session. No tokens, codes or commands are persisted. */
class CloudManagerClient(
    private val scope: CoroutineScope,
    private val manager: ManagerServer,
) {
    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data class Ready(val url: String, val code: String, val paired: Boolean = false) : State
        data class Failed(val reason: String) : State
    }
    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state = mutableState.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS).build()
    private var generation = 0L
    private var call: Call? = null
    private var socket: WebSocket? = null
    private var timer: Job? = null
    private var activeOrigin = ""
    private var activeCode = ""
    private var activeToken = ""
    private val commands = Mutex()
    private val completedIds = LinkedHashSet<String>()

    @Synchronized
    fun start(value: String) {
        stop()
        val origin = CloudManagerProtocol.origin(value)
        if (origin == null) {
            mutableState.value = State.Failed("Enter the manager HTTPS website address.")
            return
        }
        val current = generation
        val code = CloudManagerProtocol.newCode()
        val token = CloudManagerProtocol.newToken()
        activeOrigin = origin; activeCode = code; activeToken = token
        completedIds.clear()
        mutableState.value = State.Connecting
        val payload = buildJsonObject { put("code", code); put("tvToken", token) }
        call = client.newCall(Request.Builder().url("$origin/api/create")
            .post(json.encodeToString(payload).toRequestBody(JSON)).build()).also { request ->
            request.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) = failed(current)
                override fun onResponse(call: Call, response: Response) {
                    val success = response.use { it.code == 201 }
                    synchronized(this@CloudManagerClient) {
                        if (current != generation) return
                        if (!success) { failed(current); return }
                        connect(current, origin, code, token)
                    }
                }
            })
        }
    }

    private fun connect(current: Long, origin: String, code: String, token: String) {
        socket = client.newWebSocket(Request.Builder()
            .url("${origin.replaceFirst("https://", "wss://")}/api/device/$code")
            .header("Authorization", "Bearer $token").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(this@CloudManagerClient) {
                    if (generation != current) { webSocket.close(1000, "Closed"); return }
                    mutableState.value = State.Ready(origin, code)
                    timer = scope.launch {
                        delay(TimeUnit.MINUTES.toMillis(5))
                        synchronized(this@CloudManagerClient) {
                            val state = mutableState.value as? State.Ready
                            if (generation == current && state?.paired == false) {
                                stop(); mutableState.value = State.Failed("Code expired. Start a new session.")
                            }
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.toByteArray(Charsets.UTF_8).size > CloudManagerProtocol.MAX_REQUEST_BYTES + 8192) {
                    failed(current); return
                }
                synchronized(this@CloudManagerClient) {
                    if (generation != current) return
                    if (runCatching { json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content == "paired" }.getOrDefault(false)) {
                        val state = mutableState.value as? State.Ready ?: return
                        if (!state.paired) {
                            mutableState.value = state.copy(paired = true)
                            // The server owns the hard 30-minute deadline, including pre-pair time.
                        }
                        return
                    }
                }
                val command = runCatching { json.decodeFromString<CloudManagerCommand>(text) }.getOrNull() ?: return
                if (command.id.length !in 1..64) return
                scope.launch(Dispatchers.IO) {
                    commands.withLock {
                        synchronized(this@CloudManagerClient) {
                            if (generation != current || !completedIds.add(command.id)) return@launch
                            // Never replay a mutation after transport interruption.
                            if (completedIds.size > 512) {
                                val oldest = completedIds.iterator()
                                oldest.next()
                                oldest.remove()
                            }
                        }
                        val reply = try { manager.executeRemote(command) }
                        catch (_: Exception) { CloudManagerReply(command.id, 500, "{\"error\":\"Management action failed\"}") }
                        synchronized(this@CloudManagerClient) {
                            if (generation == current) webSocket.send(json.encodeToString(reply))
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = failed(current)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, "Closed"); failed(current)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = failed(current)
        })
    }

    @Synchronized
    private fun failed(current: Long) {
        if (generation != current) return
        stop()
        mutableState.value = State.Failed("Manager disconnected or unavailable. Check the internet connection and start a new session.")
    }

    @Synchronized
    fun stop() {
        generation++
        call?.cancel(); call = null
        timer?.cancel(); timer = null
        socket?.close(1000, "Session ended"); socket = null
        if (activeOrigin.isNotEmpty() && activeToken.isNotEmpty()) {
            val body = buildJsonObject { put("code", activeCode) }
            client.newCall(Request.Builder().url("$activeOrigin/api/end")
                .header("Authorization", "Bearer $activeToken")
                .post(json.encodeToString(body).toRequestBody(JSON)).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) = Unit
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
        }
        activeOrigin = ""; activeCode = ""; activeToken = ""
        mutableState.value = State.Idle
    }

    private companion object { val JSON = "application/json; charset=utf-8".toMediaType() }
}
