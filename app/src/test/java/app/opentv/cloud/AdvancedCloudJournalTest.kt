package app.opentv.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for advanced cloud journal. */
class AdvancedCloudJournalTest {
    private val ownerA = "11111111-1111-1111-1111-111111111111"
    private val ownerB = "22222222-2222-2222-2222-222222222222"
    private val device = "00000000-0000-0000-0000-000000000001"
    private val group = "33333333-3333-3333-3333-333333333333"
    private val enabled = setOf(AdvancedSyncCategory.CUSTOM_GROUPS, AdvancedSyncCategory.APP_FAVOURITES,
        AdvancedSyncCategory.HOME_PREFERENCES, AdvancedSyncCategory.COMPATIBLE_SETTINGS)
    /** Mutable in-memory journal storage used to simulate reloads and persistence failures. */
    private class Storage {
        var encryptedValue: String? = null
        var failWrites = false
        /** Creates a journal backed by the fixture's mutable in-memory storage. */
        fun journal() = AdvancedCloudJournal({ encryptedValue }, {
            if (failWrites) throw java.io.IOException("Simulated interrupted durable write")
            encryptedValue = it
        })
    }
    /** Builds a synthetic custom-group name mutation for cloud merge tests. */
    private fun name(value: String = "News", time: Long = 1000) =
        AdvancedCloudRecord(group, "group_name", time, device, textValue = value)
    /** Builds a synthetic ordered application-favorites record for cloud tests. */
    private fun apps(entries: List<String>, time: Long = 1000) =
        AdvancedCloudRecord("apps", "app_order", time, device, entries = entries)

    /** Checks: offline Outbox And Owner Survive A Process Restart. */
    @Test fun offlineOutboxAndOwnerSurviveAProcessRestart() {
        val store = Storage()
        val first = store.journal()
        first.bindOwner(ownerA)
        first.capture(ownerA, listOf(name()), enabled, device, 1000)
        val reopened = store.journal()
        reopened.bindOwner(ownerA)
        assertEquals("News", reopened.pending(ownerA, enabled, 1000).single().textValue)
        assertEquals(1L, reopened.pending(ownerA, enabled, 1000).single().updatedAtMillis)
    }

    /** Checks: rapidly Changing One Record Coalesces To Final Intent. */
    @Test fun rapidlyChangingOneRecordCoalescesToFinalIntent() {
        val journal = Storage().journal()
        journal.bindOwner(ownerA)
        for (value in listOf("A", "B", "C")) journal.capture(ownerA, listOf(name(value)), enabled, device, 1000)
        val queued = journal.pending(ownerA, enabled, 1000).single()
        assertEquals("C", queued.textValue)
        assertEquals(1001L, queued.updatedAtMillis)
    }

    /** Checks: initial Default Seed Cannot Override An Existing Older Cloud Edit. */
    @Test fun initialDefaultSeedCannotOverrideAnExistingOlderCloudEdit() {
        val journal = Storage().journal()
        journal.bindOwner(ownerA)
        journal.capture(ownerA, listOf(name("Default")), enabled, device, 900_000)
        val remote = name("From another TV", 1000)
        assertEquals(remote, journal.mergeRemote(ownerA, listOf(remote), enabled, 900_000).single())
        assertEquals(0, journal.pendingCount(ownerA))
    }

    /** Checks: initial Default Seed Loses To Other Device Seed Regardless Device Id Tie. */
    @Test fun initialDefaultSeedLosesToOtherDeviceSeedRegardlessDeviceIdTie() {
        val journal = Storage().journal()
        journal.bindOwner(ownerA)
        journal.capture(ownerA, listOf(apps(listOf("com.local.default"))), enabled, device, 1000)
        val remote = apps(listOf("com.existing.selection"), 1).copy(deviceId = "00000000-0000-0000-0000-000000000000")
        assertEquals(remote, journal.mergeRemote(ownerA, listOf(remote), enabled, 1000).single())
        assertEquals(0, journal.pendingCount(ownerA))
    }

    /** Checks: newly Available Local Content Does Not Author A Change Over Known Remote Value. */
    @Test fun newlyAvailableLocalContentDoesNotAuthorAChangeOverKnownRemoteValue() {
        val journal = Storage().journal()
        journal.bindOwner(ownerA)
        journal.mergeRemote(ownerA, listOf(name("Remote")), enabled, 1000)
        journal.capture(ownerA, listOf(name("Local import default")), enabled, device, 2000)
        assertEquals(0, journal.pendingCount(ownerA))
        assertEquals("Remote", journal.mergeRemote(ownerA, emptyList(), enabled, 2000).single().textValue)
    }

    /** Checks: catalogue Disappearance And Reimport Defaults Never Become Favourite Removals Or Resets. */
    @Test fun catalogueDisappearanceAndReimportDefaultsNeverBecomeFavouriteRemovalsOrResets() {
        val selected = setOf(AdvancedSyncCategory.CHANNEL_ORGANIZATION, AdvancedSyncCategory.FAVOURITE_REMOVALS)
        val cloudValues = listOf(
            AdvancedCloudRecord("a".repeat(64), "channel_favourite_state", 1000, device, boolValue = true),
            AdvancedCloudRecord("b".repeat(64), "movie_favourite_state", 1000, device, boolValue = true),
            AdvancedCloudRecord("c".repeat(64), "series_favourite_state", 1000, device, boolValue = true),
            AdvancedCloudRecord("a".repeat(64), "channel_hidden", 1000, device, boolValue = true),
            AdvancedCloudRecord("a".repeat(64), "channel_name", 1000, device, textValue = "Custom name"),
            AdvancedCloudRecord("a".repeat(64), "channel_order", 1000, device, intValue = 9),
        )
        val journal = Storage().journal()
        journal.bindOwner(ownerA)
        journal.mergeRemote(ownerA, cloudValues, selected, 1000)
        journal.markApplied(ownerA, cloudValues, selected)
        journal.capture(ownerA, emptyList(), selected, device, 2000)
        // Untouched imported fields are omitted by the sparse catalogue adapter.
        journal.capture(ownerA, emptyList(), selected, device, 3000)
        assertEquals(0, journal.pendingCount(ownerA))
        assertEquals(cloudValues.toSet(), journal.mergeRemote(ownerA, emptyList(), selected, 3000).toSet())
    }

    /** Checks: missing And Reimported Content Preserves Unsynced Local Intent As Well As Cloud State. */
    @Test fun missingAndReimportedContentPreservesUnsyncedLocalIntentAsWellAsCloudState() {
        val selected = setOf(AdvancedSyncCategory.FAVOURITE_REMOVALS)
        val journal = Storage().journal()
        journal.bindOwner(ownerA)
        val initial = AdvancedCloudRecord("a".repeat(64), "channel_favourite_state", 1000, device, boolValue = false)
        journal.capture(ownerA, listOf(initial), selected, device, 1000)
        journal.capture(ownerA, listOf(initial.copy(boolValue = true)), selected, device, 2000)
        val pending = journal.pending(ownerA, selected, 2000)
        journal.capture(ownerA, emptyList(), selected, device, 3000)
        journal.capture(ownerA, emptyList(), selected, device, 4000)
        assertEquals(pending, journal.pending(ownerA, selected, 4000))
        assertTrue(journal.pending(ownerA, selected, 4000).single().boolValue!!)
    }

    /** Checks: explicit Sparse Reset After Absence Is Still A New Edit. */
    @Test fun explicitSparseResetAfterAbsenceIsStillANewEdit() {
        val selected = setOf(AdvancedSyncCategory.FAVOURITE_REMOVALS)
        val initial = AdvancedCloudRecord("a".repeat(64), "channel_favourite_state", 1000, device, boolValue = true)
        val journal = Storage().journal()
        journal.bindOwner(ownerA)
        journal.mergeRemote(ownerA, listOf(initial), selected, 1000)
        journal.markApplied(ownerA, listOf(initial), selected)
        journal.capture(ownerA, emptyList(), selected, device, 2000)
        journal.capture(ownerA, listOf(initial.copy(boolValue = false)), selected, device, 3000)
        val pending = journal.pending(ownerA, selected, 3000).single()
        assertEquals(false, pending.boolValue)
        assertEquals(3000L, pending.updatedAtMillis)
    }

    /** Checks: exact Acknowledgement Does Not Drop An Edit Made During Upload. */
    @Test fun exactAcknowledgementDoesNotDropAnEditMadeDuringUpload() {
        val journal = Storage().journal()
        journal.bindOwner(ownerA)
        journal.capture(ownerA, listOf(name("First")), enabled, device, 1000)
        val uploaded = journal.pending(ownerA, enabled, 1000)
        journal.capture(ownerA, listOf(name("Second")), enabled, device, 2000)
        journal.acknowledge(ownerA, uploaded)
        assertEquals("Second", journal.pending(ownerA, enabled, 2000).single().textValue)
        journal.acknowledge(ownerA, journal.pending(ownerA, enabled, 2000))
        assertEquals(0, journal.pendingCount(ownerA))
    }

    /** Checks: interrupted Upload Replays Identical Immutable Versions. */
    @Test fun interruptedUploadReplaysIdenticalImmutableVersions() {
        val store = Storage()
        val journal = store.journal()
        journal.bindOwner(ownerA)
        journal.capture(ownerA, listOf(name()), enabled, device, 1000)
        val firstRequest = journal.pending(ownerA, enabled, 1000)
        // Server accepted, but process stopped before acknowledgement.
        assertEquals(firstRequest, store.journal().pending(ownerA, enabled, 1000))
        journal.acknowledge(ownerA, firstRequest)
        journal.acknowledge(ownerA, firstRequest)
        assertEquals(0, journal.pendingCount(ownerA))
    }

    /** Checks: remote Application Baseline Prevents An Upload Feedback Loop. */
    @Test fun remoteApplicationBaselinePreventsAnUploadFeedbackLoop() {
        val journal = Storage().journal()
        journal.bindOwner(ownerA)
        val remote = name("Remote", 2000)
        val winners = journal.mergeRemote(ownerA, listOf(remote), enabled, 2000)
        journal.markApplied(ownerA, winners.map { it.copy(updatedAtMillis = 3000) }, enabled)
        journal.capture(ownerA, listOf(remote.copy(updatedAtMillis = 9000)), enabled, device, 9000)
        assertEquals(0, journal.pendingCount(ownerA))
        journal.capture(ownerA, listOf(name("User changed it")), enabled, device, 10_000)
        assertEquals("User changed it", journal.pending(ownerA, enabled, 10_000).single().textValue)
    }

    /** Checks: disabled Categories Neither Upload Nor Download And Keep Their Queue. */
    @Test fun disabledCategoriesNeitherUploadNorDownloadAndKeepTheirQueue() {
        val journal = Storage().journal()
        journal.bindOwner(ownerA)
        journal.capture(ownerA, listOf(name()), enabled, device, 1000)
        assertTrue(journal.pending(ownerA, emptySet(), 1000).isEmpty())
        journal.capture(ownerA, listOf(name("Disabled edit")), emptySet(), device, 2000)
        assertTrue(journal.mergeRemote(ownerA, listOf(name("Remote", 2000)), emptySet(), 2000).isEmpty())
        assertEquals("News", journal.pending(ownerA, enabled, 2000).single().textValue)
        journal.capture(ownerA, listOf(name("Disabled edit")), enabled, device, 3000)
        assertEquals("Disabled edit", journal.pending(ownerA, enabled, 3000).single().textValue)
    }

    /** Checks: unavailable References Remain Known Until Later Reconciliation. */
    @Test fun unavailableReferencesRemainKnownUntilLaterReconciliation() {
        val journal = Storage().journal()
        journal.bindOwner(ownerA)
        val members = AdvancedCloudRecord(group, "group_members", 1000, device, entries = listOf("a".repeat(64)))
        journal.mergeRemote(ownerA, listOf(members), enabled, 1000)
        journal.markApplied(ownerA, emptyList(), enabled)
        journal.capture(ownerA, emptyList(), enabled, device, 2000)
        assertEquals(listOf(members), journal.mergeRemote(ownerA, emptyList(), enabled, 2000))
        assertEquals(0, journal.pendingCount(ownerA))
    }

    /** Checks: missing Apps Are Preserved And Explicit Unpin Propagates As Ordered Collection. */
    @Test fun missingAppsArePreservedAndExplicitUnpinPropagatesAsOrderedCollection() {
        val journal = Storage().journal()
        journal.bindOwner(ownerA)
        val desired = apps(listOf("com.installed.app", "com.missing.app"))
        journal.mergeRemote(ownerA, listOf(desired), enabled, 1000)
        journal.markApplied(ownerA, listOf(desired), enabled)
        journal.capture(ownerA, listOf(desired), enabled, device, 2000)
        assertEquals(0, journal.pendingCount(ownerA))
        journal.capture(ownerA, listOf(apps(listOf("com.missing.app"))), enabled, device, 3000)
        assertEquals(listOf("com.missing.app"), journal.pending(ownerA, enabled, 3000).single().entries)
    }

    /** Checks: independent Group Name And Membership Edits Survive Together. */
    @Test fun independentGroupNameAndMembershipEditsSurviveTogether() {
        val journal = Storage().journal()
        journal.bindOwner(ownerA)
        journal.capture(ownerA, listOf(name("Original")), enabled, device, 1000)
        journal.capture(ownerA, listOf(name("Renamed")), enabled, device, 2000)
        val members = AdvancedCloudRecord(group, "group_members", 3000, device, entries = listOf("a".repeat(64)))
        val winners = journal.mergeRemote(ownerA, listOf(members), enabled, 3000)
        assertEquals(setOf("group_name", "group_members"), winners.map { it.kind }.toSet())
        assertEquals("Renamed", winners.single { it.kind == "group_name" }.textValue)
        assertEquals(members.entries, winners.single { it.kind == "group_members" }.entries)
    }

    /** Checks: newer Remote Version Wins While Pending Exact Version Still Requires Acknowledgement. */
    @Test fun newerRemoteVersionWinsWhilePendingExactVersionStillRequiresAcknowledgement() {
        val journal = Storage().journal()
        journal.bindOwner(ownerA)
        journal.capture(ownerA, listOf(name("Initial")), enabled, device, 1000)
        journal.capture(ownerA, listOf(name("Local")), enabled, device, 2000)
        val sent = journal.pending(ownerA, enabled, 2000)
        val remote = name("Remote", 3000)
        assertEquals(listOf(remote), journal.mergeRemote(ownerA, listOf(remote), enabled, 3000))
        assertEquals(1, journal.pendingCount(ownerA))
        journal.acknowledge(ownerA, sent)
        assertEquals(listOf(remote), journal.mergeRemote(ownerA, emptyList(), enabled, 3000))
    }

    /** Checks: group Tombstone Survives Newer Remote And Local Rename. */
    @Test fun groupTombstoneSurvivesNewerRemoteAndLocalRename() {
        val journal = Storage().journal()
        journal.bindOwner(ownerA)
        val tombstone = name().copy(deleted = true, textValue = null)
        journal.capture(ownerA, listOf(tombstone), enabled, device, 1000)
        val result = journal.mergeRemote(ownerA, listOf(name("Stale live", 3000)), enabled, 3000)
        assertTrue(result.single().deleted)
        journal.acknowledge(ownerA, journal.pending(ownerA, enabled, 3000))
        journal.capture(ownerA, listOf(name("Unexpected resurrection")), enabled, device, 4000)
        assertEquals(0, journal.pendingCount(ownerA))
        assertTrue(journal.mergeRemote(ownerA, emptyList(), enabled, 4000).single().deleted)
    }

    /** Checks: future Clock Revision Waits Durably Instead Of Being Discarded. */
    @Test fun futureClockRevisionWaitsDurablyInsteadOfBeingDiscarded() {
        val journal = Storage().journal()
        journal.bindOwner(ownerA)
        journal.capture(ownerA, listOf(name("Initial")), enabled, device, 1000)
        journal.capture(ownerA, listOf(name("Future")), enabled, device, 900_000)
        assertEquals(1, journal.pendingCount(ownerA))
        assertTrue(journal.pending(ownerA, enabled, 1000).isEmpty())
        assertEquals("Future", journal.pending(ownerA, enabled, 900_000).single().textValue)
    }

    /** Checks: account Switch Clears Queue And Acknowledged State Before Any Replay. */
    @Test fun accountSwitchClearsQueueAndAcknowledgedStateBeforeAnyReplay() {
        val store = Storage()
        val journal = store.journal()
        journal.bindOwner(ownerA)
        journal.capture(ownerA, listOf(name("Private A")), enabled, device, 1000)
        journal.mergeRemote(ownerA, listOf(apps(listOf("com.private.app"))), enabled, 1000)
        journal.bindOwner(ownerB)
        assertEquals(0, journal.pendingCount(ownerB))
        assertTrue(journal.mergeRemote(ownerB, emptyList(), enabled, 1000).isEmpty())
        assertFalse(store.encryptedValue.orEmpty().contains("Private A"))
        assertFalse(store.encryptedValue.orEmpty().contains("com.private.app"))
    }

    /** Checks: wrong Account Cannot Read Queue Without Binding. */
    @Test(expected = IllegalStateException::class) fun wrongAccountCannotReadQueueWithoutBinding() {
        val journal = Storage().journal()
        journal.bindOwner(ownerA)
        journal.pending(ownerB, enabled, 1000)
    }

    /** Checks: interrupted Atomic Write Leaves Previous Queue Available. */
    @Test fun interruptedAtomicWriteLeavesPreviousQueueAvailable() {
        val store = Storage()
        val journal = store.journal()
        journal.bindOwner(ownerA)
        journal.capture(ownerA, listOf(name("Durable")), enabled, device, 1000)
        store.failWrites = true
        val result = runCatching { journal.capture(ownerA, listOf(name("Interrupted")), enabled, device, 2000) }
        assertTrue(result.isFailure)
        assertEquals("Durable", store.journal().pending(ownerA, enabled, 2000).single().textValue)
    }

    /** Checks: corrupt Encrypted Payload Fails Closed. */
    @Test(expected = IllegalStateException::class) fun corruptEncryptedPayloadFailsClosed() {
        val store = Storage()
        store.encryptedValue = "{\"owner\":\"$ownerA\",\"provider_password\":\"not allowed\"}"
        store.journal().bindOwner(ownerA)
    }

    /** Checks: malformed Local Records Never Enter The Outbox. */
    @Test fun malformedLocalRecordsNeverEnterTheOutbox() {
        val journal = Storage().journal()
        journal.bindOwner(ownerA)
        journal.capture(ownerA, listOf(name().copy(kind = "provider_password"), name().copy(schemaVersion = 2)), enabled, device, 1000)
        assertEquals(0, journal.pendingCount(ownerA))
    }
}
