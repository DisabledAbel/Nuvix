/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Local IDs never leave this encrypted database. [cloudId] is a persistent UUID mapping and
 * members contain only public-metadata fingerprints, including currently unavailable channels.
 * Deleted rows are retained so a stale device cannot recreate a deleted group.
 */
@Entity(tableName = "custom_channel_groups", indices = [Index(value = ["cloudId"], unique = true)])
data class CustomChannelGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cloudId: String,
    val name: String,
    val rank: Int = 0,
    val members: List<String> = emptyList(),
    val deleted: Boolean = false,
)

/** Room serialization for ordered portable custom-group member identities. */
class CustomGroupConverters {
    /** Encodes the ordered group member list as JSON for Room storage. */
    @TypeConverter fun membersToJson(value: List<String>): String = Json.encodeToString(value)
    /** Decodes stored group membership, falling back to an empty list for invalid JSON. */
    @TypeConverter fun jsonToMembers(value: String): List<String> = Json.decodeFromString(value)
}
