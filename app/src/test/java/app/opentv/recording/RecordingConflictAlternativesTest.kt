/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import app.opentv.connections.*
import app.opentv.data.model.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RecordingConflictAlternativesTest {
    private fun channel(id: Long, epg: String? = "sports") = Channel(id = id, sourceId = id, streamId = "$id",
        name = "Sports HD", groupKey = "same-name", categoryId = null, logoUrl = null, epgChannelId = epg,
        number = null, streamUrl = "https://example.invalid/private-token")
    private fun account(id: Long, key: String = "account-$id", maximum: Int? = 1) =
        AccountConnectionSnapshot(key, setOf(id), AccountConnectionPolicy(maximum, false), emptyList())

    @Test fun matchingNamesAndInferredGuideIdsDoNotAuthorizeAlternative() {
        assertFalse(exactGuideBroadcast(channel(1, null), channel(2, null)))
        assertFalse(exactGuideBroadcast(channel(1, null).copy(matchedEpgId = "sports"), channel(2)))
        assertFalse(exactGuideBroadcast(channel(1), channel(2, "news")))
        assertTrue(exactGuideBroadcast(channel(1), channel(2)))
    }
    @Test fun explicitGuideOverrideIsAuthoritative() {
        assertFalse(exactGuideBroadcast(channel(1).copy(epgOverrideId = "news"), channel(2)))
        assertTrue(exactGuideBroadcast(channel(1).copy(epgOverrideId = "news"), channel(2, "news")))
    }
    @Test fun confirmedSportsBroadcastsPrecedeExactChannelAlternativesWithoutAutoSelecting() = runTest {
        val result = eligibleRecordingAlternatives(channel(1), listOf(channel(2) to false, channel(3, "other") to true),
            "account-1", { true }, { "Provider $it" to account(it) })
        assertEquals(listOf(3L, 2L), result.map { it.channel.id })
        assertTrue(result.first().verified)
        assertEquals("0/1 local connections reserved", result.first().capacity)
    }
    @Test fun sharedUnavailableHiddenAndFullProvidersAreExcluded() = runTest {
        val ledger = ConnectionAllocator()
        ledger.registerSource(5, "full", AccountConnectionPolicy(1, false))
        ledger.reserve(ConnectionRequest("viewer", 1, 5, ConnectionPurpose.LIVE))
        val result = eligibleRecordingAlternatives(channel(1), (2L..6L).map { channel(it) to false }, "shared",
            { it.id != 4L }, { id -> when (id) {
                2L -> "Linked playlist" to account(id, "shared")
                3L -> null
                5L -> "Full" to ledger.snapshotForSource(5)!!
                else -> "Available" to account(id)
            } })
        assertEquals(listOf(6L), result.map { it.channel.id })
    }
    @Test fun unknownAllowanceIsExplicitAndDiscoveryNeverReserves() = runTest {
        val ledger = ConnectionAllocator()
        ledger.registerSource(2, "independent", AccountConnectionPolicy(null, false))
        val result = eligibleRecordingAlternatives(channel(1), listOf(channel(2) to false), "original", { true },
            { "Unknown" to ledger.snapshotForSource(2)!! })
        assertTrue(result.single().capacity.contains("requires confirmation"))
        assertEquals(0, ledger.snapshotForSource(2)!!.locallyReserved)
    }
    @Test fun scheduledRecordingDemandExcludesAnOtherwiseIdleAlternativeAccount() = runTest {
        val result = eligibleRecordingAlternatives(channel(1), listOf(channel(2) to false, channel(3) to false),
            "original", { true }, { "Provider" to account(it) }, scheduledDemand = { if (it == 2L) 1 else 0 })
        assertEquals(listOf(3L), result.map { it.channel.id })
    }
}
