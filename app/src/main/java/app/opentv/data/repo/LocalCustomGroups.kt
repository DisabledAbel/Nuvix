/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.data.repo

import androidx.room.withTransaction
import app.opentv.cloud.CloudIdentity
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.CustomChannelGroup
import app.opentv.data.parser.ChannelNameNormalizer
import java.util.UUID

/** Local-first group edits retain unresolved references rather than inventing playable rows. */
class LocalCustomGroups(private val database: OpenTvDatabase) {
    val dao get() = database.customGroups()

    /** Creates a named local group subject to name and group-count limits. */
    suspend fun create(name: String): String = database.withTransaction {
        require(validName(name))
        val active = dao.all().filterNot { it.deleted }
        require(active.size < MAX_GROUPS) { "Too many custom groups" }
        val id = UUID.randomUUID().toString()
        dao.insert(CustomChannelGroup(cloudId = id, name = name.trim(), rank = active.size))
        id
    }

    /** Validates and changes an existing group's display name. */
    suspend fun rename(cloudId: String, name: String) {
        require(validName(name))
        dao.setName(cloudId, name.trim())
    }

    /** Tombstones a group so its deletion can propagate through synchronization. */
    suspend fun delete(cloudId: String) = dao.tombstone(cloudId)

    /** Moves a group within the ordered active-group list. */
    suspend fun moveGroup(cloudId: String, delta: Int) = database.withTransaction {
        val ordered = dao.all().filterNot { it.deleted }.toMutableList()
        val from = ordered.indexOfFirst { it.cloudId == cloudId }
        if (from < 0) return@withTransaction
        val to = (from + delta).coerceIn(0, ordered.lastIndex)
        ordered.add(to, ordered.removeAt(from))
        ordered.forEachIndexed { rank, group -> dao.setRank(group.cloudId, rank) }
    }

    /** Adds a portable channel identity to a group without duplicating membership. */
    suspend fun addMember(cloudId: String, fingerprint: String) = editMembers(cloudId) { members ->
        require(HASH.matches(fingerprint))
        if (fingerprint in members) members else {
            require(members.size < MAX_MEMBERS) { "Too many group channels" }
            members + fingerprint
        }
    }

    /** Removes the portable channel identity from the group. */
    suspend fun removeMember(cloudId: String, fingerprint: String) = editMembers(cloudId) { it - fingerprint }

    /** Moves a member within the group's saved channel order. */
    suspend fun moveMember(cloudId: String, fingerprint: String, delta: Int) = editMembers(cloudId) { members ->
        val from = members.indexOf(fingerprint)
        if (from < 0) members else members.toMutableList().apply {
            add((from + delta).coerceIn(0, lastIndex), removeAt(from))
        }
    }

    /** Applies a membership edit while preserving validation and persistence rules. */
    private suspend fun editMembers(cloudId: String, edit: (List<String>) -> List<String>) = database.withTransaction {
        val group = dao.byCloudId(cloudId) ?: return@withTransaction
        if (!group.deleted) dao.setMembers(cloudId, edit(group.members))
    }

    companion object {
        const val MAX_GROUPS = 128
        const val MAX_MEMBERS = 2000
        const val MAX_NAME = 120
        private val HASH = Regex("[a-f0-9]{64}")
        /** Normalizes a group name and enforces the supported length and character limits. */
        fun validName(name: String) = name.trim().let { it.codePointCount(0, it.length) in 1..MAX_NAME } &&
            name.none { it.isISOControl() }
        /** Builds the portable channel identity used by group membership. */
        fun fingerprint(channel: Channel) = CloudIdentity.channel(channel.displayName, channel.epgChannelId)

        /** Supply only enabled, authorized source channels. Ambiguous matches stay unresolved. */
        fun matchingChannels(channels: List<Channel>): Map<String, Channel> = channels.groupBy(::fingerprint)
            .mapNotNull { (fingerprint, matches) -> matches.singleOrNull()?.let { fingerprint to it } }.toMap()

        /** Filters channel rows to those allowed by source, visibility and parental restrictions. */
        fun visible(channel: Channel, categories: List<Category>, hiddenCategoryKeys: Set<String>, unlocked: Boolean): Boolean {
            if (channel.hidden) return false
            if (unlocked || hiddenCategoryKeys.isEmpty()) return true
            return categories.none { category ->
                category.sourceId == channel.sourceId && category.id == channel.categoryId &&
                    ChannelNameNormalizer.normalize(category.name).groupKey.ifEmpty { category.id } in hiddenCategoryKeys
            }
        }

        /** Resolves ordered portable member identities to currently available local channels. */
        fun resolve(
            group: CustomChannelGroup,
            channels: List<Channel>,
            categories: List<Category>,
            hiddenCategoryKeys: Set<String>,
            unlocked: Boolean,
        ): List<Channel> {
            if (group.deleted) return emptyList()
            val matches = matchingChannels(channels)
            return group.members.distinct().mapNotNull { matches[it] }
                .filter { visible(it, categories, hiddenCategoryKeys, unlocked) }
        }
    }
}
