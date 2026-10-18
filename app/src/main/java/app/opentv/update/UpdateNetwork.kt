package app.opentv.update

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** A separate dispatcher and connection pool keep update work away from the player client. */
object UpdateNetwork {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 2; maxRequestsPerHost = 2 })
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                if (!chain.request().url.isHttps) throw IOException("Updates require HTTPS")
                chain.proceed(chain.request())
            }
            .addNetworkInterceptor { chain ->
                if (!chain.request().url.isHttps) throw IOException("Updates require HTTPS")
                chain.proceed(chain.request())
            }
            .build()
    }
}

/** Awaits an OkHttp response and cancels the underlying call when its coroutine is cancelled. */
internal suspend fun OkHttpClient.awaitResponse(request: Request): Response = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

/** Decodes the response as UTF-8 while enforcing the maximum byte count. */
internal fun ResponseBody.readLimited(maxBytes: Int): String = byteStream().use { stream ->
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val read = stream.read(buffer)
        if (read == -1) break
        if (output.size() + read > maxBytes) throw IOException("Update response too large")
        output.write(buffer, 0, read)
    }
    output.toString("UTF-8")
}
