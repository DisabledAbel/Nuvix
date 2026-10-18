/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.cloud

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Deliberate cloud allowlist. Never serialize SyncBundle, Source, URLs, or settings here. */
@Serializable
data class CloudRecord(
    @SerialName("record_key") val recordKey: String,
    val kind: String,
    @SerialName("profile_key") val profileKey: String = EMPTY_PROFILE,
    @SerialName("position_ms") val positionMillis: Long = 0,
    @SerialName("duration_ms") val durationMillis: Long = 0,
    @SerialName("updated_at_ms") val updatedAtMillis: Long,
    @SerialName("device_id") val deviceId: String,
) {
    companion object {
        val EMPTY_PROFILE = "0".repeat(64)
        val KINDS = setOf("channel_favourite", "movie_favourite", "series_favourite", "movie_progress", "episode_progress")
        private val HASH = Regex("[a-f0-9]{64}")
        const val MAX_DURATION_MS = 7L * 24 * 60 * 60 * 1000
    }

    /** Reject malformed remote records before any local write. */
    fun isSafe(nowMillis: Long): Boolean =
        kind in KINDS && HASH.matches(recordKey) && HASH.matches(profileKey) &&
            runCatching { UUID.fromString(deviceId).toString() == deviceId }.getOrDefault(false) &&
            updatedAtMillis in 1..(nowMillis + 5 * 60 * 1000) &&
            durationMillis in 0..MAX_DURATION_MS && positionMillis in 0..MAX_DURATION_MS &&
            (durationMillis == 0L || positionMillis <= durationMillis) &&
            (if (kind.endsWith("_favourite")) profileKey == EMPTY_PROFILE && positionMillis == 0L && durationMillis == 0L
             else profileKey != EMPTY_PROFILE)
}

/** Same deterministic conflict order is enforced atomically by the Postgres RPC. */
fun newestRecord(first: CloudRecord, second: CloudRecord): CloudRecord {
    require(first.recordKey == second.recordKey && first.kind == second.kind && first.profileKey == second.profileKey)
    return when {
        first.updatedAtMillis > second.updatedAtMillis -> first
        first.updatedAtMillis < second.updatedAtMillis -> second
        first.deviceId >= second.deviceId -> first
        else -> second
    }
}

/** Combines record kind, profile and media keys for legacy cloud conflict resolution. */
fun CloudRecord.identity(): String = "$kind:$profileKey:$recordKey"

/** Input is selected public metadata, never a provider URL, login, MAC, or local database ID. */
object CloudIdentity {
    /** Returns a SHA-256 identity without exposing the original identifying text. */
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Normalizes identity text with stable casing and whitespace. */
    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    /** Builds a portable profile identity from its normalized name. */
    fun profile(name: String): String = hash("profile:${normalized(name)}")
    /** Builds a portable channel identity from its normalized name and any safe EPG ID. */
    fun channel(name: String, epgId: String?): String {
        // Some unusual feeds put URLs in the ID field. Do not even fingerprint those values.
        val safeEpgId = epgId?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,128}")) }.orEmpty()
        return hash("channel:${normalized(name)}:${normalized(safeEpgId)}")
    }
    /** Builds a portable movie or series identity using metadata IDs when available. */
    fun media(kind: String, title: String, year: Int?, tmdbId: String?): String {
        val tmdb = tmdbId?.toLongOrNull()?.takeIf { it > 0 }
        return hash(if (tmdb != null) "$kind:tmdb:$tmdb" else "$kind:title:${normalized(title)}:${year ?: 0}")
    }
    /** Builds an episode identity from its parent series, season and episode number. */
    fun episode(seriesKey: String, season: Int, number: Int): String = hash("episode:$seriesKey:$season:$number")
}
