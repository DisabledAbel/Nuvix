/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.channels

import app.opentv.core.GuideChannelFilter
import app.opentv.core.GuideChannelSort
import app.opentv.data.model.Channel
import app.opentv.data.model.CustomChannelGroup
import app.opentv.data.repo.LocalCustomGroups
import org.junit.Assert.*
import org.junit.Test

class GuideChannelSelectionTest {
    private fun channel(
        id: Long,
        name: String = "Channel $id",
        sourceId: Long = 1,
        favourite: Boolean = false,
        hidden: Boolean = false,
        number: Int? = null,
        sortIndex: Int = 0,
        epgId: String? = null,
    ) = Channel(
        id = id, sourceId = sourceId, streamId = "stream-$id", name = name,
        displayName = name, categoryId = "live", logoUrl = null,
        epgChannelId = epgId, number = number, streamUrl = "https://example.invalid/$id",
        favourite = favourite, hidden = hidden, sortIndex = sortIndex,
    )

    private fun group(vararg channels: Channel) = CustomChannelGroup(
        id = 99, cloudId = "00000000-0000-0000-0000-000000000001", name = "Guide group",
        members = channels.map(LocalCustomGroups::fingerprint),
    )

    private fun List<Channel>.ids() = map { it.id }

    @Test fun allAndProviderFiltersUseTheSuppliedCatalogue() {
        val channels = listOf(channel(3, sourceId = 2), channel(2, hidden = true), channel(1))
        listOf(GuideChannelFilter.ALL, GuideChannelFilter.PROVIDER).forEach { filter ->
            assertEquals(listOf(1L, 3L), selectGuideChannels(channels, filter, GuideChannelSort.PROVIDER_ORDER).ids())
            assertTrue(selectGuideChannels(emptyList(), filter, GuideChannelSort.PROVIDER_ORDER).isEmpty())
        }
        // Provider scoping belongs to the caller; this projection preserves that scoped input.
        assertEquals(listOf(3L), selectGuideChannels(channels.filter { it.sourceId == 2L },
            GuideChannelFilter.PROVIDER, GuideChannelSort.PROVIDER_ORDER).ids())
    }

    @Test fun favouritesFilterUsesCurrentFlagsAndExcludesHiddenFavourites() {
        val channels = listOf(channel(1), channel(2, favourite = true), channel(3, favourite = true, hidden = true))
        assertEquals(listOf(2L), selectGuideChannels(channels, GuideChannelFilter.FAVOURITES,
            GuideChannelSort.PROVIDER_ORDER).ids())
        assertTrue(selectGuideChannels(channels.map { it.copy(favourite = false) },
            GuideChannelFilter.FAVOURITES, GuideChannelSort.PROVIDER_ORDER).isEmpty())
    }

    @Test fun recentDefaultsToHistoryOrderAndHonoursAnExplicitAlternativeSort() {
        val channels = listOf(channel(10, "Alpha", sortIndex = 2), channel(20, hidden = true),
            channel(30, "Zulu", sourceId = 2, sortIndex = 1), channel(40))
        val recent = listOf(999L, 30L, 20L, 10L)
        assertEquals(listOf(30L, 10L), selectGuideChannels(channels, GuideChannelFilter.RECENT,
            GuideChannelSort.PROVIDER_ORDER, recentIds = recent).ids())
        assertEquals(listOf(10L, 30L), selectGuideChannels(channels, GuideChannelFilter.RECENT,
            GuideChannelSort.ALPHABETICAL, recentIds = recent).ids())
        assertEquals(listOf(30L, 10L), selectGuideChannels(channels, GuideChannelFilter.RECENT,
            GuideChannelSort.CUSTOM, recentIds = recent).ids())
        assertTrue(selectGuideChannels(channels, GuideChannelFilter.RECENT,
            GuideChannelSort.PROVIDER_ORDER).isEmpty())
    }

    @Test fun epgFiltersUseAnyAvailableCandidateAndExcludeMissingGuideData() {
        val channels = listOf(
            channel(1, epgId = "missing").copy(epgOverrideId = "manual"),
            channel(2, epgId = "provider"),
            channel(3, epgId = "absent").copy(matchedEpgId = "fallback"),
            channel(4), channel(5, epgId = "unavailable"),
            channel(6, hidden = true, epgId = "manual"),
            channel(7, epgId = "Manual"),
            channel(8).copy(epgOverrideId = "stale", matchedEpgId = "fallback"),
        )
        listOf(GuideChannelFilter.WITH_EPG, GuideChannelFilter.AIRING).forEach { filter ->
            assertEquals(listOf(1L, 2L, 3L, 8L), selectGuideChannels(channels, filter,
                GuideChannelSort.PROVIDER_ORDER, epgIds = setOf("manual", "provider", "fallback")).ids())
            assertTrue(selectGuideChannels(channels, filter, GuideChannelSort.PROVIDER_ORDER).isEmpty())
        }
        assertTrue(selectGuideChannels(channels, GuideChannelFilter.ALL,
            GuideChannelSort.PROVIDER_ORDER).ids().containsAll(listOf(4L, 5L)))
    }

    @Test fun everySortHasDeterministicTieBreaksAndDoesNotReorderTheCatalogue() {
        val channels = listOf(
            channel(40, "Zulu", sourceId = 2, number = 12, sortIndex = 1, favourite = true),
            channel(10, "beta", number = 2, sortIndex = 3),
            channel(30, "Alpha", number = 2, sortIndex = 2, favourite = true),
            channel(20, "alpha", sortIndex = 2),
            channel(50, "Hidden", number = 0, sortIndex = -1, hidden = true, favourite = true),
        )
        val original = channels.toList()
        val expected = mapOf(
            GuideChannelSort.PROVIDER_ORDER to listOf(10L, 20L, 30L, 40L),
            GuideChannelSort.CHANNEL_NUMBER to listOf(30L, 10L, 40L, 20L),
            GuideChannelSort.ALPHABETICAL to listOf(20L, 30L, 10L, 40L),
            GuideChannelSort.FAVOURITES_FIRST to listOf(30L, 40L, 20L, 10L),
            GuideChannelSort.CUSTOM to listOf(40L, 20L, 30L, 10L),
        )
        assertEquals(GuideChannelSort.entries.toSet(), expected.keys)
        expected.forEach { (sort, ids) ->
            val selected = selectGuideChannels(channels, GuideChannelFilter.ALL, sort)
            assertEquals(sort.name, ids, selected.ids())
            selected.forEach { row -> assertSame(channels.single { it.id == row.id }, row) }
        }
        assertEquals(original, channels)
    }

    @Test fun nameSortsUseManualNamesAndFallBackFromBlankManualNames() {
        val channels = listOf(channel(1, "Zulu").copy(customName = "Aardvark"),
            channel(2, "Beta").copy(customName = "   "), channel(3, "Alpha"))
        listOf(GuideChannelSort.ALPHABETICAL, GuideChannelSort.CHANNEL_NUMBER,
            GuideChannelSort.FAVOURITES_FIRST).forEach { sort ->
            assertEquals(listOf(1L, 3L, 2L), selectGuideChannels(channels, GuideChannelFilter.ALL, sort).ids())
        }
    }

    @Test fun groupCustomOrderKeepsUnavailableReferencesWithoutCreatingRows() {
        val first = channel(1)
        val second = channel(2)
        val third = channel(3)
        val group = group(third, first).copy(members = listOf(LocalCustomGroups.fingerprint(third),
            "f".repeat(64), LocalCustomGroups.fingerprint(first)))
        val originalMembers = group.members.toList()
        assertEquals(listOf(3L, 1L), selectGuideChannels(listOf(first, second, third),
            GuideChannelFilter.GROUP, GuideChannelSort.CUSTOM, group = group).ids())
        assertEquals(listOf(1L, 3L), selectGuideChannels(listOf(first, second, third),
            GuideChannelFilter.GROUP, GuideChannelSort.PROVIDER_ORDER, group = group).ids())
        assertEquals(originalMembers, group.members)
    }

    @Test fun ambiguousGroupFingerprintsAcrossSourcesStayUnresolved() {
        val first = channel(1, "News", sourceId = 1, epgId = "news")
        val ambiguous = channel(2, "News", sourceId = 2, epgId = "news")
        val unique = channel(3, "Sports", epgId = "sports")
        assertEquals(LocalCustomGroups.fingerprint(first), LocalCustomGroups.fingerprint(ambiguous))
        GuideChannelSort.entries.forEach { sort ->
            assertEquals(listOf(3L), selectGuideChannels(listOf(first, ambiguous, unique),
                GuideChannelFilter.GROUP, sort, group = group(first, unique)).ids())
        }
    }

    @Test fun deletedMissingAndUnavailableGroupsNeverReturnChannels() {
        val channel = channel(1)
        GuideChannelSort.entries.forEach { sort ->
            listOf(null, group(channel).copy(deleted = true), group(channel).copy(members = listOf("f".repeat(64))))
                .forEach { group ->
                    assertTrue(selectGuideChannels(listOf(channel), GuideChannelFilter.GROUP, sort, group = group).isEmpty())
                }
        }
    }

    @Test fun everyFilterAndSortExcludesHiddenRowsAndPreservesStableIdentity() {
        val visible = channel(1, favourite = true, epgId = "visible")
        val hidden = channel(2, favourite = true, hidden = true, epgId = "hidden")
        val channels = listOf(hidden, visible)
        GuideChannelFilter.entries.forEach { filter ->
            GuideChannelSort.entries.forEach { sort ->
                val selected = selectGuideChannels(channels, filter, sort, recentIds = listOf(2, 1),
                    group = group(hidden, visible), epgIds = setOf("hidden", "visible"))
                assertEquals("$filter / $sort", listOf(1L), selected.ids())
                assertSame(visible, selected.single())
            }
        }
    }

    @Test fun twentyThousandChannelsProduceSparseSelectionsWithBoundedCataloguePasses() {
        val catalogue = (1L..20_000L).map { id ->
            channel(id, favourite = id % 400L == 0L, hidden = id % 2_000L == 0L)
        }
        val counted = CountingList(catalogue)
        val expected = (1L..20_000L).filter { it % 400L == 0L && it % 2_000L != 0L }
        assertEquals(expected, selectGuideChannels(counted, GuideChannelFilter.FAVOURITES,
            GuideChannelSort.PROVIDER_ORDER).ids())
        assertTrue("Favourite selection must not repeatedly scan the catalogue", counted.reads <= catalogue.size * 2)

        counted.reads = 0
        val members = expected.reversed().map { catalogue[(it - 1).toInt()] }
        val selected = selectGuideChannels(counted, GuideChannelFilter.GROUP,
            GuideChannelSort.CUSTOM, group = group(*members.toTypedArray()))
        assertEquals(expected.reversed(), selected.ids())
        assertEquals(40, selected.size)
        assertTrue("Group resolution must use a bounded number of catalogue passes", counted.reads <= catalogue.size * 2)
        selected.forEach { row -> assertSame(catalogue[(row.id - 1).toInt()], row) }
    }

    private class CountingList(private val channels: List<Channel>) : AbstractList<Channel>() {
        var reads = 0
        override val size: Int get() = channels.size
        override fun get(index: Int): Channel {
            reads++
            return channels[index]
        }
    }
}
