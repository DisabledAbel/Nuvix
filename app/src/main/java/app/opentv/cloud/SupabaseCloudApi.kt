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
    private val advancedJson = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        // Never forward API keys or bearer tokens to a redirect target.
        .followRedirects(false).followSslRedirects(false).build()

    /** Requests a one-time email code from the configured authentication endpoint. */
    suspend fun sendEmailCode(email: String) {
        require(email.length <= 254 && email.contains('@')) { "Enter a valid email address." }
        request("/auth/v1/otp", body = buildJsonObject { put("email", email); put("create_user", true) }.toString())
    }

    /** Verifies an email code and parses the authenticated session response. */
    suspend fun verifyEmailCode(email: String, code: String): CloudSession {
        require(code.matches(Regex("[0-9]{6,10}"))) { "Enter the numeric code from your email." }
        val response = request("/auth/v1/verify", body = buildJsonObject {
            put("email", email); put("token", code); put("type", "email")
        }.toString())
        return session(response)
    }

    /** Exchanges a refresh token for a renewed authenticated session. */
    suspend fun refresh(refreshToken: String): CloudSession = session(request("/auth/v1/token?grant_type=refresh_token",
        body = buildJsonObject { put("refresh_token", refreshToken) }.toString()))

    /** Requests sign-out for the supplied access token. */
    suspend fun signOut(token: String) { request("/auth/v1/logout?scope=local", token, "{}") }

    /** Uploads safe legacy records through the merge RPC in bounded batches. */
    suspend fun push(token: String, records: List<CloudRecord>) {
        for (batch in records.chunked(200)) {
            val body = buildJsonObject {
                put("records", json.encodeToJsonElement(ListSerializer(CloudRecord.serializer()), batch))
            }.toString()
            request("/rest/v1/rpc/nuvix_merge_records", token, body)
        }
    }

    /** Fetches legacy records for the authenticated owner. */
    suspend fun pull(token: String, userId: String): List<CloudRecord> {
        require(UUID.fromString(userId).toString() == userId) { "Invalid cloud account." }
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

    /** Uploads validated advanced records through the advanced merge RPC. */
    suspend fun pushAdvanced(token: String, records: List<AdvancedCloudRecord>) {
        for (batch in records.chunked(100)) {
            require(batch.all { it.isSafe(System.currentTimeMillis()) }) { "Invalid advanced cloud record." }
            val body = buildJsonObject {
                put("records", advancedJson.encodeToJsonElement(ListSerializer(AdvancedCloudRecord.serializer()), batch))
            }.toString()
            request("/rest/v1/rpc/nuvix_merge_advanced_records", token, body)
        }
    }

    /** Fetches the owner's records for the explicitly enabled advanced categories. */
    suspend fun pullAdvanced(token: String, userId: String, categories: Set<AdvancedSyncCategory>): List<AdvancedCloudRecord> {
        require(UUID.fromString(userId).toString() == userId) { "Invalid cloud account." }
        val kinds = AdvancedCloudRecord.KINDS.filter { kind ->
            AdvancedCloudRecord("", kind, 1, "").category in categories
        }
        if (kinds.isEmpty()) return emptyList()
        val result = mutableListOf<AdvancedCloudRecord>()
        var after: String? = null
        var receivedBytes = 0L
        while (true) {
            val cursor = after?.let { "&id=gt.$it" }.orEmpty()
            val body = request("/rest/v1/nuvix_advanced_records?owner_id=eq.$userId&kind=in.(${kinds.joinToString(",")})&select=id,schema_version,kind,record_key,updated_at_ms,device_id,deleted,text_value,bool_value,int_value,entries&order=id.asc&limit=20$cursor", token)
            receivedBytes += body.toByteArray(Charsets.UTF_8).size
            check(receivedBytes <= 16L * 1024 * 1024) { "Cloud library is too large for this device." }
            val page = advancedJson.parseToJsonElement(body) as? kotlinx.serialization.json.JsonArray
                ?: throw IOException("Cloud sync returned an invalid response.")
            if (page.isEmpty()) break
            for (element in page) {
                val fields = JsonObject(element.jsonObject.filterKeys { it != "id" })
                val record = advancedJson.decodeFromJsonElement(AdvancedCloudRecord.serializer(), fields)
                // Validate shape here; the journal defers records ahead of this TV's clock.
                require(record.isSafe(Long.MAX_VALUE)) { "Invalid advanced cloud record." }
                result += record
            }
            val next = page.last().jsonObject["id"]?.jsonPrimitive?.content
                ?: throw IOException("Cloud sync returned an invalid cursor.")
            require(next.matches(Regex("[1-9][0-9]{0,18}")) && (after == null || next.toLong() > after.toLong())) {
                "Cloud sync returned an invalid cursor."
            }
            after = next
            check(result.size <= 100_000) { "Cloud library is too large for this device." }
        }
        return result
    }

    /** Parses authentication JSON into the local session and expiry representation. */
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

    /** Performs a cloud HTTP request with the public project key and optional access token. */
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

/** Cloud HTTP failure with a status code and a message safe for display. */
internal class CloudHttpException(val status: Int) : IOException(when (status) {
    401, 403 -> "Cloud authentication or access was denied. Check sign-in and row security setup."
    404 -> "Cloud sync schema was not found. Install the supplied migration."
    429 -> "Too many requests. Wait before trying again."
    else -> "Cloud service returned HTTP $status. Local data is safe; sync can be retried."
})
