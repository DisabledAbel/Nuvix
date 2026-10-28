/* Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.pairing

import android.os.SystemClock
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
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
import kotlinx.serialization.Serializable
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
class CloudManagerClient internal constructor(
    private val scope: CoroutineScope,
    private val execute: (CloudManagerCommand) -> CloudManagerReply,
    private val calls: Call.Factory,
    private val sockets: WebSocket.Factory,
    private val elapsedRealtime: () -> Long,
) {
    constructor(scope: CoroutineScope, manager: ManagerServer) :
        this(scope, manager::executeRemote, CLIENT, CLIENT, SystemClock::elapsedRealtime)

    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data class Ready(
            val url: String,
            val code: String,
            val paired: Boolean = false,
            val remainingSeconds: Long? = null,
        ) : State
        data object Expired : State
        data class Failed(val reason: String) : State
    }
    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state = mutableState.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private var generation = 0L
    private var call: Call? = null
    private var socket: WebSocket? = null
    private var timer: Job? = null
    private var pairingDeadline: CloudPairingDeadline? = null
    private var activeOrigin = ""
    private var activeCode = ""
    private var activeToken = ""
    private val commands = Mutex()
    private val commandSlots = Semaphore(4)
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
        val requestStarted = elapsedRealtime()
        call = calls.newCall(Request.Builder().url("$origin/api/create")
            .post(json.encodeToString(payload).toRequestBody(JSON)).build()).also { request ->
            request.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) = failed(current)
                override fun onResponse(call: Call, response: Response) {
                    var incompatible = false
                    val deadline = response.use {
                        if (it.code != 201) null else runCatching {
                            val created = json.decodeFromString<Created>(it.body!!.string())
                            incompatible = created.managerVersion != 2
                            if (incompatible) null else CloudPairingDeadline.fromServer(created.pairUntil, created.serverNow, requestStarted)
                        }.getOrNull()
                    }
                    synchronized(this@CloudManagerClient) {
                        if (current != generation) return
                        if (incompatible) {
                            stop()
                            mutableState.value = State.Failed("Update the manager Worker before using this APK. Manager protocol 2 is required.")
                            return
                        }
                        if (deadline == null) { failed(current); return }
                        pairingDeadline = deadline
                        if (refreshPairing(current) == null) return
                        connect(current, origin, code, token)
                        timer = scope.launch {
                            while (true) {
                                val wait = synchronized(this@CloudManagerClient) { refreshPairing(current) }
                                    ?: return@launch
                                delay(wait)
                            }
                        }
                    }
                }
            })
        }
    }

    private fun connect(current: Long, origin: String, code: String, token: String) {
        socket = sockets.newWebSocket(Request.Builder()
            .url("${origin.replaceFirst("https://", "wss://")}/api/device/$code")
            .header("Authorization", "Bearer $token").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(this@CloudManagerClient) {
                    if (generation != current) { webSocket.close(1000, "Closed"); return }
                    if (refreshPairing(current) == null) return
                    val remaining = pairingDeadline!!.remainingMillis(elapsedRealtime())
                    if (remaining == 0L) { refreshPairing(current); return }
                    mutableState.value = State.Ready(origin, code,
                        remainingSeconds = CloudPairingDeadline.secondsRemaining(remaining))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.toByteArray(Charsets.UTF_8).size > CloudManagerProtocol.MAX_REQUEST_BYTES + 8192) {
                    failed(current); return
                }
                synchronized(this@CloudManagerClient) {
                    if (generation != current) return
                    if (runCatching { json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content == "paired" }.getOrDefault(false)) {
                        val state = when (val state = mutableState.value) {
                            is State.Ready -> state
                            State.Expired -> State.Ready(origin, code)
                            else -> return
                        }
                        if (!state.paired) {
                            timer?.cancel(); timer = null
                            pairingDeadline = null
                            mutableState.value = state.copy(paired = true, remainingSeconds = null)
                            // The server owns the hard 30-minute deadline, including pre-pair time.
                        }
                        return
                    }
                }
                val command = runCatching { json.decodeFromString<CloudManagerCommand>(text) }.getOrNull() ?: return
                if (command.id.length !in 1..64) return
                if (!commandSlots.tryAcquire()) {
                    webSocket.send(json.encodeToString(CloudManagerReply(command.id, 429, "{\"error\":\"TV busy\"}")))
                    return
                }
                scope.launch(Dispatchers.IO) {
                    try { commands.withLock {
                        synchronized(this@CloudManagerClient) {
                            if (generation != current || !completedIds.add(command.id)) return@launch
                            // Never replay a mutation after transport interruption.
                            if (completedIds.size > 512) {
                                val oldest = completedIds.iterator()
                                oldest.next()
                                oldest.remove()
                            }
                        }
                        val reply = try { execute(command) }
                        catch (_: Exception) { CloudManagerReply(command.id, 500, "{\"error\":\"Management action failed\"}") }
                        synchronized(this@CloudManagerClient) {
                            if (generation == current) webSocket.send(json.encodeToString(reply))
                        }
                    } } finally { commandSlots.release() }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = failed(current)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, "Closed"); failed(current)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = failed(current)
        })
    }

    /** Called under the client lock; collectors and recomposition never own or reset this timer. */
    private fun refreshPairing(current: Long): Long? {
        if (generation != current) return null
        val deadline = pairingDeadline ?: return null
        val remaining = deadline.remainingMillis(elapsedRealtime())
        if (remaining == 0L) {
            // Hide the code locally, but let the Worker decide whether pairing succeeded.
            // Its accepted pairing notification may still be in flight at this deadline.
            timer?.cancel(); timer = null
            pairingDeadline = null
            mutableState.value = State.Expired
            return null
        }
        val ready = mutableState.value as? State.Ready
        if (ready != null && !ready.paired) {
            mutableState.value = ready.copy(remainingSeconds = CloudPairingDeadline.secondsRemaining(remaining))
        }
        // Align updates to second boundaries, but always re-read elapsedRealtime after a delay.
        return (remaining - 1) % 1000 + 1
    }

    @Synchronized
    private fun failed(current: Long) {
        if (generation != current) return
        val expired = mutableState.value == State.Expired ||
            pairingDeadline?.remainingMillis(elapsedRealtime()) == 0L
        stop()
        mutableState.value = if (expired) State.Expired else
            State.Failed("Manager disconnected or unavailable. Check the internet connection and start a new session.")
    }

    @Synchronized
    fun stop() {
        generation++
        call?.cancel(); call = null
        timer?.cancel(); timer = null
        pairingDeadline = null
        socket?.close(1000, "Session ended"); socket = null
        if (activeOrigin.isNotEmpty() && activeToken.isNotEmpty()) {
            val body = buildJsonObject { put("code", activeCode) }
            calls.newCall(Request.Builder().url("$activeOrigin/api/end")
                .header("Authorization", "Bearer $activeToken")
                .post(json.encodeToString(body).toRequestBody(JSON)).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) = Unit
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
        }
        activeOrigin = ""; activeCode = ""; activeToken = ""
        mutableState.value = State.Idle
    }

    @Serializable
    private data class Created(val pairUntil: Long, val serverNow: Long, val managerVersion: Int = 1)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val CLIENT = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS).build()
    }
}
