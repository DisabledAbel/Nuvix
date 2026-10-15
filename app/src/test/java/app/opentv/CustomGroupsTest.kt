/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv

import android.app.Application
import androidx.room.Room
import app.opentv.cloud.AdvancedCloudRecord
import app.opentv.cloud.AdvancedGroupData
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.CustomChannelGroup
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.data.repo.LocalCustomGroups
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CustomGroupsTest {
    private lateinit var db: OpenTvDatabase
    private lateinit var store: LocalCustomGroups

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), OpenTvDatabase::class.java)
            .allowMainThreadQueries().build()
        store = LocalCustomGroups(db)
    }
    @After fun close() { db.close() }

    private fun channel(id: Long = 1, source: Long = 1, name: String = "News", hidden: Boolean = false) = Channel(
        id = id, sourceId = source, streamId = "fixture-$id", name = name, displayName = name,
        categoryId = "news", logoUrl = null, epgChannelId = "news", number = null,
        streamUrl = "https://example.invalid/$id", hidden = hidden,
    )

    @Test fun createRenameAndReorderPreservePersistentCloudMapping() = runBlocking {
        val first = store.create("First")
        val second = store.create("Second")
        assertEquals(first, UUID.fromString(first).toString())
        val localId = db.customGroups().byCloudId(first)!!.id
        store.rename(first, "Renamed")
        store.moveGroup(second, -1)
        val groups = db.customGroups().observeActive().first()
        assertEquals(listOf(second, first), groups.map { it.cloudId })
        assertEquals("Renamed", groups.last().name)
        assertEquals(localId, groups.last().id)
        // Remote upsert also preserves the mapping, even if the sender used another Room ID.
        db.customGroups().upsert(groups.last().copy(id = 987, name = "Remote rename"))
        assertEquals(localId, db.customGroups().byCloudId(first)!!.id)
    }

    @Test fun membershipOrderingDoesNotDropUnavailableReferences() = runBlocking {
        val id = store.create("Favorites")
        val one = LocalCustomGroups.fingerprint(channel())
        val missing = "b".repeat(64)
        store.addMember(id, one)
        store.addMember(id, missing)
        store.addMember(id, one)
        store.moveMember(id, missing, -1)
        val group = db.customGroups().byCloudId(id)!!
        assertEquals(listOf(missing, one), group.members)
        assertEquals(listOf(1L), LocalCustomGroups.resolve(group, listOf(channel()), emptyList(), emptySet(), false).map { it.id })
        assertEquals(listOf(missing, one), db.customGroups().byCloudId(id)!!.members)
        store.removeMember(id, missing)
        assertEquals(listOf(one), db.customGroups().byCloudId(id)!!.members)
    }

    @Test fun deletionIsStickyEvenWhenTombstoneArrivesBeforeCreation() = runBlocking {
        val id = UUID.randomUUID().toString()
        db.customGroups().tombstone(id)
        val localId = db.customGroups().byCloudId(id)!!.id
        db.customGroups().upsert(CustomChannelGroup(cloudId = id, name = "Stale creation", members = listOf("a".repeat(64))))
        store.rename(id, "Stale rename")
        store.addMember(id, "b".repeat(64))
        assertTrue(db.customGroups().observeActive().first().isEmpty())
        val tombstone = db.customGroups().byCloudId(id)!!
        assertEquals(localId, tombstone.id)
        assertTrue(tombstone.deleted)
        assertTrue(tombstone.members.isEmpty())
    }

    @Test fun matchingRequiresEnabledAuthorizedSourceAndRejectsAmbiguity() = runBlocking {
        db.sources().insert(Source(id = 1, name = "Enabled", kind = SourceKind.M3U, url = "https://example.invalid/one"))
        db.sources().insert(Source(id = 2, name = "Disabled", kind = SourceKind.M3U, url = "https://example.invalid/two", enabled = false))
        db.channels().upsertAll(listOf(channel(), channel(2, source = 2)))
        var authorized = db.customGroups().observeAuthorizedChannels().first()
        assertEquals(listOf(1L), authorized.map { it.id })
        val group = CustomChannelGroup(cloudId = UUID.randomUUID().toString(), name = "News", members = listOf(LocalCustomGroups.fingerprint(channel())))
        assertEquals(listOf(1L), LocalCustomGroups.resolve(group, authorized, emptyList(), emptySet(), false).map { it.id })
        db.channels().upsertAll(listOf(channel(3, source = 1)))
        authorized = db.customGroups().observeAuthorizedChannels().first()
        assertTrue(LocalCustomGroups.resolve(group, authorized, emptyList(), emptySet(), false).isEmpty())
        assertEquals(1, group.members.size)
        assertEquals(3, db.channels().allForMatching().size)
    }

    @Test fun visibilityCannotBypassHiddenChannelsOrLockedCategories() {
        val visible = channel()
        val group = CustomChannelGroup(cloudId = UUID.randomUUID().toString(), name = "News", members = listOf(LocalCustomGroups.fingerprint(visible)))
        val categories = listOf(Category("news", 1, "News", StreamKind.LIVE))
        assertTrue(LocalCustomGroups.resolve(group, listOf(visible.copy(hidden = true)), categories, emptySet(), true).isEmpty())
        assertTrue(LocalCustomGroups.resolve(group, listOf(visible), categories, setOf("news"), false).isEmpty())
        assertEquals(listOf(visible), LocalCustomGroups.resolve(group, listOf(visible), categories, setOf("news"), true))
        assertTrue(LocalCustomGroups.resolve(group.copy(deleted = true), listOf(visible), categories, emptySet(), true).isEmpty())
    }

    @Test fun groupCloudAdapterAppliesIndependentFieldsAndRetainsUnknownReferences() = runBlocking {
        val device = "00000000-0000-0000-0000-000000000001"
        val group = UUID.randomUUID().toString()
        val time = System.currentTimeMillis()
        val adapter = AdvancedGroupData(db)
        val members = AdvancedCloudRecord(group, "group_members", time, device, entries = listOf("a".repeat(64)))
        assertEquals(0, adapter.apply(listOf(members)))
        val name = AdvancedCloudRecord(group, "group_name", time, device, textValue = "From cloud")
        assertEquals(2, adapter.apply(listOf(name, members)))
        val localId = db.customGroups().byCloudId(group)!!.id
        adapter.apply(listOf(name.copy(textValue = "Renamed")))
        assertEquals(listOf("a".repeat(64)), db.customGroups().byCloudId(group)!!.members)
        assertEquals(localId, db.customGroups().byCloudId(group)!!.id)
        assertEquals(0, adapter.apply(listOf(name.copy(textValue = "Renamed"), members)))
        assertEquals(3, adapter.snapshot(device, time).size)
        adapter.apply(listOf(name.copy(deleted = true, textValue = null)))
        adapter.apply(listOf(name.copy(textValue = "Resurrect"), members))
        assertTrue(db.customGroups().byCloudId(group)!!.deleted)
        assertEquals(1, adapter.snapshot(device, time).size)
        assertTrue(adapter.snapshot(device, time).single().deleted)
    }

    @Test fun localGroupRenameDuringSyncDoesNotPreventIndependentRemoteMembership() = runBlocking {
        val device = "00000000-0000-0000-0000-000000000001"
        val group = store.create("Original")
        val time = System.currentTimeMillis()
        val adapter = AdvancedGroupData(db)
        val expected = adapter.snapshot(device, time)
        store.rename(group, "Local name")
        val remote = listOf(
            AdvancedCloudRecord(group, "group_name", time, device, textValue = "Remote name"),
            AdvancedCloudRecord(group, "group_members", time, device, entries = listOf("a".repeat(64))),
        )
        assertEquals(1, adapter.apply(remote, expected))
        assertEquals("Local name", db.customGroups().byCloudId(group)!!.name)
        assertEquals(listOf("a".repeat(64)), db.customGroups().byCloudId(group)!!.members)
    }

    @Test fun localEditsValidateNamesAndFingerprints() = runBlocking {
        assertFalse(LocalCustomGroups.validName("   "))
        assertFalse(LocalCustomGroups.validName("a".repeat(121)))
        assertFalse(LocalCustomGroups.validName("News\nTV"))
        assertTrue(LocalCustomGroups.validName("🛰".repeat(120)))
        val id = store.create("News")
        try { store.addMember(id, "https://private.invalid/live"); fail("URL accepted as a channel reference") }
        catch (_: IllegalArgumentException) { /* Expected: only public metadata hashes may persist. */ }
        assertTrue(db.customGroups().byCloudId(id)!!.members.isEmpty())
    }
}
