/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.opentv.data.model.Channel
import app.opentv.data.model.CustomChannelGroup
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CustomGroupDao {
    @Query("SELECT * FROM custom_channel_groups WHERE deleted = 0 ORDER BY rank, cloudId")
    abstract fun observeActive(): Flow<List<CustomChannelGroup>>

    @Query("SELECT * FROM custom_channel_groups ORDER BY rank, cloudId")
    abstract suspend fun all(): List<CustomChannelGroup>

    @Query("SELECT * FROM custom_channel_groups WHERE cloudId = :cloudId")
    abstract suspend fun byCloudId(cloudId: String): CustomChannelGroup?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(group: CustomChannelGroup): Long

    @Query("UPDATE custom_channel_groups SET name = :name WHERE cloudId = :cloudId AND deleted = 0")
    abstract suspend fun setName(cloudId: String, name: String)

    @Query("UPDATE custom_channel_groups SET rank = :rank WHERE cloudId = :cloudId AND deleted = 0")
    abstract suspend fun setRank(cloudId: String, rank: Int)

    @Query("UPDATE custom_channel_groups SET members = :members WHERE cloudId = :cloudId AND deleted = 0")
    abstract suspend fun setMembers(cloudId: String, members: List<String>)

    @Query("UPDATE custom_channel_groups SET deleted = 1 WHERE cloudId = :cloudId")
    abstract suspend fun markDeleted(cloudId: String)

    /** A tombstone may arrive before the group's creation record. Persist that mapping too. */
    @Transaction
    open suspend fun tombstone(cloudId: String) {
        insert(CustomChannelGroup(cloudId = cloudId, name = "", deleted = true))
        markDeleted(cloudId)
    }

    /** Updates preserve the local mapping and an existing tombstone always wins. */
    @Transaction
    open suspend fun upsert(group: CustomChannelGroup) {
        val existing = byCloudId(group.cloudId)
        if (existing == null) {
            insert(group.copy(id = 0))
        } else if (group.deleted) {
            markDeleted(group.cloudId)
        } else if (!existing.deleted) {
            setName(group.cloudId, group.name)
            setRank(group.cloudId, group.rank)
            setMembers(group.cloudId, group.members)
        }
    }

    /** Includes hidden rows when determining ambiguity; visibility is checked after matching. */
    @Query("SELECT channels.* FROM channels INNER JOIN sources ON sources.id = channels.sourceId WHERE sources.enabled = 1 ORDER BY channels.displayName, channels.id")
    abstract fun observeAuthorizedChannels(): Flow<List<Channel>>
}
