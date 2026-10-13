/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.cloud

import android.content.Context
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.decodeBase64

@Serializable
internal data class CloudConfig(val url: String, val publishableKey: String)

@Serializable
internal data class CloudSession(
    val userId: String, val email: String, val accessToken: String, val refreshToken: String, val expiresAtMillis: Long,
)

internal class CloudSettings(context: Context) {
    private val prefs = context.getSharedPreferences("nuvix_cloud_flags", Context.MODE_PRIVATE)
    val vault = CloudVault(context)
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).commit() }
    var lastSynced: Long
        get() = prefs.getLong("last_synced", 0)
        set(value) { prefs.edit().putLong("last_synced", value).apply() }
    val deviceId: String get() = prefs.getString("device", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device", it).commit()
    }
    fun config(): CloudConfig? = vault.read("config")?.let { json.decodeFromString(CloudConfig.serializer(), it) }
    fun session(): CloudSession? = vault.read("session")?.let { json.decodeFromString(CloudSession.serializer(), it) }
    fun saveSession(session: CloudSession) { vault.write("session", json.encodeToString(CloudSession.serializer(), session)) }

    fun configure(url: String, key: String) {
        val parsed = url.trim().toHttpUrlOrNull()
        require(parsed != null && parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty() &&
            parsed.encodedPath == "/" && parsed.query == null && parsed.fragment == null) {
            "Use an HTTPS Supabase project URL without a path, login, or query."
        }
        require(isPublicClientKey(key.trim())) { "Use a publishable or legacy anon key. Secret/service-role keys are prohibited." }
        val previous = config()
        val config = CloudConfig(parsed.toString().trimEnd('/'), key.trim())
        if (previous != config) {
            enabled = false
            vault.remove("session")
            vault.remove("outbox")
            vault.remove("acknowledged")
            vault.remove("favourites")
            lastSynced = 0
        }
        vault.write("config", json.encodeToString(CloudConfig.serializer(), config))
    }

    fun clearSession() {
        enabled = false
        vault.remove("session")
        vault.remove("outbox")
        vault.remove("acknowledged")
        vault.remove("favourites")
    }

    fun reset() { clearSession(); vault.remove("config"); lastSynced = 0 }
}

/** Legacy JWT keys must explicitly claim anon; accepting arbitrary JWTs would admit service_role. */
internal fun isPublicClientKey(key: String): Boolean {
    if (key.startsWith("sb_publishable_") && key.length >= 25 && key.none { it.isWhitespace() }) return true
    if (key.startsWith("sb_secret_")) return false
    return runCatching {
        val parts = key.split('.')
        parts.size == 3 && Json.parseToJsonElement(parts[1].decodeBase64()?.utf8() ?: "")
            .jsonObject["role"]?.jsonPrimitive?.content == "anon"
    }.getOrDefault(false)
}
