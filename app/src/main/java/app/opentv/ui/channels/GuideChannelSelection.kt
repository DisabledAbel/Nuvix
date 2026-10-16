/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.channels

import app.opentv.core.GuideChannelFilter
import app.opentv.core.GuideChannelSort
import app.opentv.data.model.Channel
import app.opentv.data.model.CustomChannelGroup
import app.opentv.data.model.shownName
import app.opentv.data.repo.LocalCustomGroups
import java.util.Locale

/** A read-only projection over the existing catalogue, favorites, recent IDs and group order. */
internal fun selectGuideChannels(
    channels: List<Channel>,
    filter: GuideChannelFilter,
    sort: GuideChannelSort,
    recentIds: List<Long> = emptyList(),
    group: CustomChannelGroup? = null,
    epgIds: Set<String> = emptySet(),
): List<Channel> {
    val recentRank = recentIds.withIndex().associate { it.value to it.index }
    val groupRank = group?.members.orEmpty().withIndex().associate { it.value to it.index }
    val uniqueGroupChannels = if (filter == GuideChannelFilter.GROUP) LocalCustomGroups.matchingChannels(channels) else emptyMap()
    val filtered = channels.filter { channel ->
        !channel.hidden && when (filter) {
            GuideChannelFilter.ALL, GuideChannelFilter.PROVIDER -> true
            GuideChannelFilter.FAVOURITES -> channel.favourite
            GuideChannelFilter.RECENT -> channel.id in recentRank
            GuideChannelFilter.GROUP -> group?.deleted == false &&
                uniqueGroupChannels[LocalCustomGroups.fingerprint(channel)]?.id == channel.id &&
                LocalCustomGroups.fingerprint(channel) in groupRank
            GuideChannelFilter.WITH_EPG, GuideChannelFilter.AIRING -> channel.epgCandidates.any { it in epgIds }
        }
    }
    val byName = compareBy<Channel> { it.shownName.lowercase(Locale.ROOT) }.thenBy { it.id }
    return when (sort) {
        // Local row ids retain first-import provider order even after a manual reorder.
        GuideChannelSort.PROVIDER_ORDER -> filtered.sortedWith(compareBy<Channel> { it.sourceId }.thenBy { it.id })
        GuideChannelSort.CHANNEL_NUMBER -> filtered.sortedWith(compareBy<Channel> { it.number ?: Int.MAX_VALUE }.then(byName))
        GuideChannelSort.ALPHABETICAL -> filtered.sortedWith(byName)
        GuideChannelSort.FAVOURITES_FIRST -> filtered.sortedWith(compareByDescending<Channel> { it.favourite }.then(byName))
        GuideChannelSort.CUSTOM -> if (filter == GuideChannelFilter.GROUP) {
            filtered.sortedBy { groupRank[LocalCustomGroups.fingerprint(it)] ?: Int.MAX_VALUE }
        } else filtered.sortedWith(compareBy<Channel> { it.sortIndex }.thenBy { it.id })
    }.let { sorted ->
        // Recently watched is a time-ordered collection unless a user chooses another sort.
        if (filter == GuideChannelFilter.RECENT && sort == GuideChannelSort.PROVIDER_ORDER)
            sorted.sortedBy { recentRank[it.id] ?: Int.MAX_VALUE } else sorted
    }
}
