/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.cloud

import androidx.room.withTransaction
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.CustomChannelGroup
import app.opentv.data.repo.LocalCustomGroups

/** Names/deletions, rank and membership merge separately; local IDs never enter the protocol. */
internal class AdvancedGroupData(private val database: OpenTvDatabase) {
    suspend fun snapshot(deviceId: String, nowMillis: Long): List<AdvancedCloudRecord> = database.withTransaction {
        buildList {
            for (group in database.customGroups().all()) {
                add(AdvancedCloudRecord(group.cloudId, "group_name", nowMillis, deviceId,
                    deleted = group.deleted, textValue = group.name.takeUnless { group.deleted }))
                if (!group.deleted) {
                    add(AdvancedCloudRecord(group.cloudId, "group_rank", nowMillis, deviceId, intValue = group.rank))
                    add(AdvancedCloudRecord(group.cloudId, "group_members", nowMillis, deviceId, entries = group.members))
                }
            }
        }.filter { it.isSafe(nowMillis) }
    }

    suspend fun apply(records: List<AdvancedCloudRecord>, expectedLocal: List<AdvancedCloudRecord>? = null): Int = database.withTransaction {
        val dao = database.customGroups()
        val expected = expectedLocal?.associateBy { it.identity() }
        val original = if (expected != null) dao.all().associateBy { it.cloudId } else emptyMap()
        fun unchanged(record: AdvancedCloudRecord): Boolean {
            if (expected == null || record.deleted) return true
            val local = original[record.recordKey]
            val prior = expected[record.identity()]
            if (local == null) return prior == null
            if (prior == null) return false
            return when (record.kind) {
                "group_name" -> prior.deleted == local.deleted && (local.deleted || prior.textValue == local.name)
                "group_rank" -> prior.intValue == local.rank
                "group_members" -> prior.entries == local.members
                else -> false
            }
        }
        val safe = records.filter { it.kind.startsWith("group_") && it.isSafe(System.currentTimeMillis()) && unchanged(it) }
        var changed = 0
        // A deletion also creates a mapping if its name record has not been seen on this TV.
        for (record in safe.filter { it.kind == "group_name" && it.deleted }) {
            if (dao.byCloudId(record.recordKey)?.deleted != true) { dao.tombstone(record.recordKey); changed++ }
        }
        var activeCount = dao.all().count { !it.deleted }
        for (record in safe.filter { it.kind == "group_name" && !it.deleted }) {
            val existing = dao.byCloudId(record.recordKey)
            if (existing == null && activeCount < LocalCustomGroups.MAX_GROUPS) {
                dao.insert(CustomChannelGroup(cloudId = record.recordKey, name = record.textValue!!))
                activeCount++; changed++
            } else if (existing != null && !existing.deleted && existing.name != record.textValue) {
                dao.setName(record.recordKey, record.textValue!!); changed++
            }
        }
        for (record in safe.filter { it.kind == "group_members" || it.kind == "group_rank" }) {
            val existing = dao.byCloudId(record.recordKey)?.takeUnless { it.deleted } ?: continue
            if (record.kind == "group_members" && existing.members != record.entries) {
                dao.setMembers(record.recordKey, record.entries); changed++
            } else if (record.kind == "group_rank" && existing.rank != record.intValue) {
                dao.setRank(record.recordKey, record.intValue!!); changed++
            }
        }
        changed
    }
}
