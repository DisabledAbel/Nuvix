/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.cloud

import android.app.Application
import android.content.Context
import app.opentv.ui.home.*
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Regression tests for home cloud preferences. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class HomeCloudPreferencesTest {
    private val device = "00000000-0000-0000-0000-000000000001"
    private val owner = "11111111-1111-1111-1111-111111111111"
    /** Builds a synthetic cloud record for validation and merge assertions. */
    private fun record(kind: String, key: String, entries: List<String>) = AdvancedCloudRecord(key, kind, 1000, device, entries = entries)

    /** Checks: portable local serialization remote application and legacy visibility coexist. */
    @Test fun `portable local serialization remote application and legacy visibility coexist`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(HomePreferencesStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        val store = HomePreferencesStore(context)
        store.setPresentation(HomePresentation(layout = HomeLayout.CINEMA, background = HomeBackground.DYNAMIC_ARTWORK))
        store.moveShelf("movies", -1)
        store.setVisible(HomeSection.RECENT, false)
        val group = "group:$device"
        store.setVisible(group, false)
        val data = AdvancedPreferenceData(context)
        val snapshot = data.snapshot(device, 1000).filter { it.category == AdvancedSyncCategory.HOME_PREFERENCES }
        assertThat(snapshot.all { it.isSafe(1000) }).isTrue()
        store.reset()
        snapshot.forEach { assertThat(data.apply(it)).isTrue() }
        assertThat(store.currentPreferences.presentation.layout).isEqualTo(HomeLayout.CINEMA)
        assertThat(store.currentPreferences.isVisible(HomeSection.RECENT)).isFalse()
        assertThat(store.currentPreferences.isVisible(group)).isFalse()
        assertThat(store.currentPreferences.shelfOrder.indexOf("movies")).isEqualTo(3)
        data.close(); store.close()
    }

    /** Checks: incoming group visibility applies before group import but a concurrent local edit wins. */
    @Test fun `incoming group visibility applies before group import but a concurrent local edit wins`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(HomePreferencesStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        val data = AdvancedPreferenceData(context)
        val store = HomePreferencesStore(context)
        val remote = AdvancedCloudRecord("group:$device", "home_group", 1000, device, boolValue = false)
        assertThat(data.applyIfUnchanged(remote, null, null)).isTrue()
        assertThat(store.currentPreferences.isVisible(remote.recordKey)).isFalse()
        val before = remote.copy(boolValue = true)
        assertThat(data.applyIfUnchanged(before, before, remote)).isFalse()
        assertThat(store.currentPreferences.isVisible(remote.recordKey)).isFalse()
        assertThat(data.applyIfUnchanged(remote, null, before)).isFalse()
        assertThat(data.applyIfUnchanged(record("home_order", "shelves_v1", HomeShelfOrder.defaults), null, null)).isFalse()
        data.close(); store.close()
    }

    /** Checks: strict record shape rejects duplicates unknown data credentials and unsupported versions. */
    @Test fun `strict record shape rejects duplicates unknown data credentials and unsupported versions`() {
        val valid = record("home_order", "shelves_v1", HomeShelfOrder.defaults)
        assertThat(valid.isSafe(1000)).isTrue()
        listOf(valid.copy(entries = listOf("apps", "apps")), valid.copy(entries = listOf("future_shelf")),
            valid.copy(entries = List(161) { "apps" }), valid.copy(entries = listOf("https://host?token=secret")),
            valid.copy(schemaVersion = 2), valid.copy(textValue = "secret"), valid.copy(recordKey = "shelves_v2"),
            record("home_layout", "presentation_v1", HomePresentation().portable() + "secret"),
            record("home_layout", "presentation_v1", HomePresentation().portable().map { if (it == "v1") "v2" else it }),
            AdvancedCloudRecord("group:invalid", "home_group", 1000, device, boolValue = false)
        ).forEach { assertThat(it.isSafe(1000)).isFalse() }
    }

    /** Checks: offline order revisions merge as a single list deterministically and remain account bound. */
    @Test fun `offline order revisions merge as a single list deterministically and remain account bound`() {
        var disk: String? = null
        var journal = AdvancedCloudJournal({ disk }, { disk = it })
        val categories = setOf(AdvancedSyncCategory.HOME_PREFERENCES)
        journal.bindOwner(owner)
        val defaults = record("home_order", "shelves_v1", HomeShelfOrder.defaults)
        journal.capture(owner, listOf(defaults), categories, device, 1000)
        val reordered = defaults.copy(entries = HomeShelfOrder.defaults.reversed())
        assertThat(newestAdvancedRecord(defaults, reordered)).isEqualTo(defaults)
        assertThat(newestAdvancedRecord(reordered, defaults)).isEqualTo(defaults)
        journal.capture(owner, listOf(reordered), categories, device, 2000)
        journal = AdvancedCloudJournal({ disk }, { disk = it })
        assertThat(journal.pending(owner, categories, 2000).single().entries).isEqualTo(reordered.entries)
        val remote = defaults.copy(updatedAtMillis = 2000, deviceId = "00000000-0000-0000-0000-000000000002")
        val winner = journal.mergeRemote(owner, listOf(remote), categories, 2000).single()
        assertThat(winner.entries).isEqualTo(defaults.entries)
        assertThat(newestAdvancedRecord(remote, reordered.copy(updatedAtMillis = 2000))).isEqualTo(remote)
        assertThat(newestAdvancedRecord(reordered.copy(updatedAtMillis = 2000), remote)).isEqualTo(remote)
        val otherOwner = "22222222-2222-2222-2222-222222222222"
        journal.bindOwner(otherOwner)
        assertThat(journal.pending(otherOwner, categories, 2000)).isEmpty()
    }

    /** Checks: disabled home category neither journals nor applies remote presentation. */
    @Test fun `disabled home category neither journals nor applies remote presentation`() {
        var disk: String? = null
        val journal = AdvancedCloudJournal({ disk }, { disk = it })
        journal.bindOwner(owner)
        val layout = record("home_layout", "presentation_v1", HomePresentation(layout = HomeLayout.COMPACT).portable())
        val categories = setOf(AdvancedSyncCategory.COMPATIBLE_SETTINGS)
        journal.capture(owner, listOf(layout), categories, device, 1000)
        assertThat(journal.pending(owner, categories, 1000)).isEmpty()
        assertThat(journal.mergeRemote(owner, listOf(layout), categories, 1000)).isEmpty()
        assertThat(AdvancedSyncCategory.DEFAULTS).doesNotContain(AdvancedSyncCategory.HOME_PREFERENCES)
    }
}
