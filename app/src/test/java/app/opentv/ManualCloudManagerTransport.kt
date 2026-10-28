/* Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv

import java.io.IOException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.Timeout

/** Manual transport shared by client lifecycle and real ViewModel/screen integration tests. */
internal class ManualCloudManagerTransport(
    private val onCall: (Request) -> Unit = {},
) : Call.Factory, WebSocket.Factory {
    val calls = mutableListOf<ManualCall>()
    val sockets = mutableListOf<ManualSocket>()
    override fun newCall(request: Request): Call = ManualCall(request).also { onCall(request); calls.add(it) }
    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
        ManualSocket(request, listener).also { sockets.add(it) }

    class ManualCall(private val request: Request) : Call {
        private lateinit var callback: Callback
        private var canceled = false
        private var executed = false
        override fun request() = request
        override fun enqueue(responseCallback: Callback) { callback = responseCallback; executed = true }
        override fun execute(): Response = error("Async calls only")
        override fun cancel() { canceled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = canceled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = ManualCall(request)
        fun respond(pairUntil: Long = SERVER + 300_000, serverNow: Long = SERVER,
                    body: String = "{\"ok\":true,\"managerVersion\":2,\"pairUntil\":$pairUntil,\"serverNow\":$serverNow}") {
            callback.onResponse(this, response(request, 201).newBuilder().body(body.toResponseBody()).build())
        }
    }

    class ManualSocket(private val request: Request, private val listener: WebSocketListener) : WebSocket {
        var closed = false
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String) = !closed
        override fun send(bytes: ByteString) = !closed
        override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
        override fun cancel() { closed = true }
        fun open() = listener.onOpen(this, response(request, 101))
        fun paired() = listener.onMessage(this, "{\"type\":\"paired\"}")
        fun disconnect() = listener.onFailure(this, IOException("Disconnected"), null)
    }

    private companion object {
        const val SERVER = 1_800_000_000_000L
        fun response(request: Request, code: Int): Response = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test").build()
    }
}
