/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.cloud

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Small pinned REST protocol surface, using the application's existing OkHttp dependency. */
internal class SupabaseCloudApi(private val config: CloudConfig) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        // Never forward API keys or bearer tokens to a redirect target.
        .followRedirects(false).followSslRedirects(false).build()

    suspend fun sendEmailCode(email: String) {
        require(email.length <= 254 && email.contains('@')) { "Enter a valid email address." }
        request("/auth/v1/otp", body = buildJsonObject { put("email", email); put("create_user", true) }.toString())
    }

    suspend fun verifyEmailCode(email: String, code: String): CloudSession {
        require(code.matches(Regex("[0-9]{6,10}"))) { "Enter the numeric code from your email." }
        val response = request("/auth/v1/verify", body = buildJsonObject {
            put("email", email); put("token", code); put("type", "email")
        }.toString())
        return session(response)
    }

    suspend fun refresh(refreshToken: String): CloudSession = session(request("/auth/v1/token?grant_type=refresh_token",
        body = buildJsonObject { put("refresh_token", refreshToken) }.toString()))

    suspend fun signOut(token: String) { request("/auth/v1/logout?scope=local", token, "{}") }

    suspend fun push(token: String, records: List<CloudRecord>) {
        for (batch in records.chunked(200)) {
            val body = buildJsonObject {
                put("records", json.encodeToJsonElement(ListSerializer(CloudRecord.serializer()), batch))
            }.toString()
            request("/rest/v1/rpc/nuvix_merge_records", token, body)
        }
    }

    suspend fun pull(token: String, userId: String): List<CloudRecord> {
        // Stable keyset pagination also works when PostgREST's project row limit is under 1000.
        val result = mutableListOf<CloudRecord>()
        var after: String? = null
        while (true) {
            val cursor = after?.let { "&id=gt.$it" }.orEmpty()
            val body = request("/rest/v1/nuvix_sync_records?owner_id=eq.$userId&select=id,record_key,kind,profile_key,position_ms,duration_ms,updated_at_ms,device_id&order=id.asc&limit=500$cursor", token)
            val page = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonArray
                ?: throw IOException("Cloud sync returned an invalid response.")
            if (page.isEmpty()) break
            for (element in page) result += json.decodeFromJsonElement(CloudRecord.serializer(), element)
            after = page.last().jsonObject["id"]?.jsonPrimitive?.content
                ?: throw IOException("Cloud sync returned an invalid cursor.")
            require(after.matches(Regex("[0-9]+"))) { "Cloud sync returned an invalid cursor." }
            check(result.size <= 100_000) { "Cloud library is too large for this device." }
        }
        return result
    }

    private fun session(body: String): CloudSession {
        val parsed = json.parseToJsonElement(body).jsonObject
        val user = parsed["user"]?.jsonObject ?: throw IOException("Sign-in did not return a user.")
        val id = UUID.fromString(user["id"]?.jsonPrimitive?.content).toString()
        fun field(name: String): String = parsed[name]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: throw IOException("Sign-in did not return a valid session.")
        val lifetime = parsed["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        return CloudSession(id, user["email"]?.jsonPrimitive?.contentOrNull.orEmpty(), field("access_token"),
            field("refresh_token"), System.currentTimeMillis() + lifetime.coerceIn(1, 86400) * 1000)
    }

    private suspend fun request(path: String, token: String? = null, body: String? = null): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(config.url + path).header("apikey", config.publishableKey)
            .header("Accept", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw CloudHttpException(response.code)
            val responseBody = response.body ?: return@withContext ""
            // Guard against an unexpectedly large or hostile configured server response.
            val source = responseBody.source()
            val limit = 4L * 1024 * 1024
            source.request(limit + 1)
            if (source.buffer.size > limit) throw IOException("Cloud response is too large.")
            source.readUtf8()
        }
    }
}

internal class CloudHttpException(val status: Int) : IOException(when (status) {
    401, 403 -> "Cloud authentication or access was denied. Check sign-in and row security setup."
    404 -> "Cloud sync schema was not found. Install the supplied migration."
    429 -> "Too many requests. Wait before trying again."
    else -> "Cloud service returned HTTP $status. Local data is safe; sync can be retried."
})
