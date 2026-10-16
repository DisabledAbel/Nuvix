/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.cloud

import java.util.UUID
import app.opentv.ui.home.HomePresentation
import app.opentv.ui.home.HomeShelfOrder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Advanced categories are deliberately opt-in, independently of the existing cloud opt-in. */
enum class AdvancedSyncCategory(val defaultEnabled: Boolean = false) {
    FAVOURITES_PROGRESS(true),
    CHANNEL_ORGANIZATION,
    CUSTOM_GROUPS,
    HOME_PREFERENCES,
    APP_FAVOURITES,
    COMPATIBLE_SETTINGS,
    FAVOURITE_REMOVALS;

    companion object {
        val DEFAULTS: Set<AdvancedSyncCategory> = entries.filter { it.defaultEnabled }.toSet()
    }
}

/** The advanced protocol must never silently accept arbitrary credential-bearing fields. */
internal val advancedCloudJson = Json { ignoreUnknownKeys = false; encodeDefaults = true }

/** Additive protocol: the original CloudRecord and its deployed table remain unchanged. */
@Serializable
data class AdvancedCloudRecord(
    @SerialName("record_key") val recordKey: String,
    val kind: String,
    @SerialName("updated_at_ms") val updatedAtMillis: Long,
    @SerialName("device_id") val deviceId: String,
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val deleted: Boolean = false,
    @SerialName("text_value") val textValue: String? = null,
    @SerialName("bool_value") val boolValue: Boolean? = null,
    @SerialName("int_value") val intValue: Int? = null,
    val entries: List<String> = emptyList(),
) {
    val category: AdvancedSyncCategory
        get() = when (kind) {
            "group_name", "group_rank", "group_members" -> AdvancedSyncCategory.CUSTOM_GROUPS
            "channel_hidden", "channel_name", "channel_order" -> AdvancedSyncCategory.CHANNEL_ORGANIZATION
            "channel_favourite_state", "movie_favourite_state", "series_favourite_state" -> AdvancedSyncCategory.FAVOURITE_REMOVALS
            "app_order" -> AdvancedSyncCategory.APP_FAVOURITES
            "home_shelf", "home_order", "home_layout", "home_group" -> AdvancedSyncCategory.HOME_PREFERENCES
            "setting" -> AdvancedSyncCategory.COMPATIBLE_SETTINGS
            else -> throw IllegalArgumentException("Unsupported advanced cloud record kind.")
        }

    /** Every kind has exactly one allowed payload shape; unused slots are forbidden. */
    fun isSafe(nowMillis: Long): Boolean {
        val upperBound = if (nowMillis > Long.MAX_VALUE - MAX_CLOCK_SKEW_MS) Long.MAX_VALUE else nowMillis + MAX_CLOCK_SKEW_MS
        if (schemaVersion != 1 || updatedAtMillis < 1 || updatedAtMillis > upperBound || !canonicalUuid(deviceId)) return false
        if (deleted && kind != "group_name") return false
        fun text(minimum: Int = 0) = textValue != null && safeText(textValue, minimum) &&
            boolValue == null && intValue == null && entries.isEmpty()
        fun boolean() = boolValue != null && textValue == null && intValue == null && entries.isEmpty()
        fun rank() = intValue != null && intValue in 0..MAX_RANK && textValue == null && boolValue == null && entries.isEmpty()
        fun collection(limit: Int, valid: (String) -> Boolean) = textValue == null && boolValue == null && intValue == null &&
            entries.size <= limit && entries.size == entries.toSet().size && entries.all(valid)
        return when (kind) {
            "group_name" -> canonicalUuid(recordKey) && if (deleted) {
                textValue == null && boolValue == null && intValue == null && entries.isEmpty()
            } else text(1)
            "group_rank" -> canonicalUuid(recordKey) && rank()
            "group_members" -> canonicalUuid(recordKey) && collection(MAX_GROUP_MEMBERS) { HASH.matches(it) }
            "channel_hidden", "channel_favourite_state", "movie_favourite_state", "series_favourite_state" -> HASH.matches(recordKey) && boolean()
            "channel_name" -> HASH.matches(recordKey) && text()
            "channel_order" -> HASH.matches(recordKey) && rank()
            "app_order" -> recordKey == "apps" && collection(MAX_APPS) { it.length <= 255 && PACKAGE.matches(it) }
            "home_shelf" -> recordKey in HOME_SHELVES && boolean()
            "home_group" -> HomeShelfOrder.isGroup(recordKey) && boolean()
            "home_order" -> recordKey == "shelves_v1" && collection(HomeShelfOrder.MAX_SHELVES, HomeShelfOrder::portable)
            "home_layout" -> recordKey == "presentation_v1" && textValue == null && boolValue == null && intValue == null &&
                HomePresentation.parse(entries) != null
            "setting" -> when (recordKey) {
                "theme_mode" -> textValue in setOf("SYSTEM", "DARK", "LIGHT") && text()
                "channel_layout" -> textValue in setOf("GRID", "LIST") && text()
                "startup_destination" -> textValue in setOf("HOME", "LIVE_TV", "GUIDE", "LAST_WATCHED", "APPS") && text()
                "subtitles_enabled" -> boolean()
                else -> false
            }
            else -> false
        }
    }

    internal fun samePayload(other: AdvancedCloudRecord): Boolean = identity() == other.identity() &&
        schemaVersion == other.schemaVersion && deleted == other.deleted && textValue == other.textValue &&
        boolValue == other.boolValue && intValue == other.intValue && entries == other.entries

    /** Matches the SQL C-collation tie-breaker; lists are a single ordered merge unit. */
    internal fun payloadOrderKey(): String = when {
        textValue != null -> textValue
        boolValue != null -> if (boolValue) "1" else "0"
        intValue != null -> intValue.toString().padStart(7, '0')
        else -> entries.joinToString(",")
    }

    companion object {
        const val MAX_CLOCK_SKEW_MS = 300_000L
        const val MAX_RANK = 1_000_000
        const val MAX_GROUP_MEMBERS = 2_000
        const val MAX_APPS = 200
        val KINDS = setOf("group_name", "group_rank", "group_members", "channel_hidden", "channel_name", "channel_order",
            "channel_favourite_state", "movie_favourite_state", "series_favourite_state", "app_order", "home_shelf", "home_order", "home_layout", "home_group", "setting")
        val HOME_SHELVES = setOf("continue", "recent", "favourites", "on_now", "movies", "series", "apps")
        private val HASH = Regex("[a-f0-9]{64}")
        private val PACKAGE = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")

        private fun safeText(value: String, minimum: Int): Boolean =
            value.codePointCount(0, value.length) in minimum..120 && value.none { it.code < 32 || it.code == 127 } &&
                // Unpaired UTF-16 surrogates do not have a valid PostgreSQL UTF-8 representation.
                value.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == value
    }
}

internal fun canonicalUuid(value: String): Boolean = value.length == 36 &&
    runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

fun AdvancedCloudRecord.identity(): String = "$kind:$recordKey"

/** A deleted group UUID is permanently retired; creation requires a fresh UUID. */
fun newestAdvancedRecord(first: AdvancedCloudRecord, second: AdvancedCloudRecord): AdvancedCloudRecord {
    require(first.identity() == second.identity()) { "Cannot merge different advanced cloud records." }
    return when {
        first.kind == "group_name" && first.deleted != second.deleted -> if (first.deleted) first else second
        first.updatedAtMillis != second.updatedAtMillis -> if (first.updatedAtMillis > second.updatedAtMillis) first else second
        first.deviceId != second.deviceId -> if (first.deviceId > second.deviceId) first else second
        compareCloudUtf8(first.payloadOrderKey(), second.payloadOrderKey()) >= 0 -> first
        else -> second
    }
}

/** PostgreSQL COLLATE C compares UTF-8 bytes, while String.compareTo compares UTF-16. */
private fun compareCloudUtf8(first: String, second: String): Int {
    val a = first.toByteArray(Charsets.UTF_8)
    val b = second.toByteArray(Charsets.UTF_8)
    for (index in 0 until minOf(a.size, b.size)) {
        val difference = (a[index].toInt() and 255) - (b[index].toInt() and 255)
        if (difference != 0) return difference
    }
    return a.size.compareTo(b.size)
}
