/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.home

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class HomePreferencesStoreTest {
    @Before fun clearPreferences() {
        RuntimeEnvironment.getApplication().getSharedPreferences(HomePreferencesStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun `default home preserves every existing shelf`() {
        val store = HomePreferencesStore(RuntimeEnvironment.getApplication())
        assertThat(store.preferences.value.visibleSections).containsExactlyElementsIn(HomeSection.entries)
        store.close()
    }

    @Test fun `visibility choices persist and independent stores observe remote changes`() {
        val context = RuntimeEnvironment.getApplication()
        val first = HomePreferencesStore(context)
        val second = HomePreferencesStore(context)
        first.setVisible(HomeSection.CONTINUE, false)
        assertThat(second.preferences.value.isVisible(HomeSection.CONTINUE)).isFalse()
        assertThat(second.applyRemoteVisibility("apps", false)).isTrue()
        assertThat(first.preferences.value.isVisible(HomeSection.APPS)).isFalse()

        val restored = HomePreferencesStore(context)
        assertThat(restored.preferences.value.visibleSections)
            .containsExactly(HomeSection.RECENT, HomeSection.FAVOURITES, HomeSection.ON_NOW, HomeSection.MOVIES, HomeSection.SERIES)
        assertThat(second.applyRemoteVisibility("apps", true)).isTrue()
        assertThat(first.preferences.value.isVisible(HomeSection.APPS)).isTrue()
        first.close(); second.close(); restored.close()
    }

    @Test fun `unknown preference keys are rejected without writing them`() {
        val context = RuntimeEnvironment.getApplication()
        val store = HomePreferencesStore(context)
        assertThat(store.applyRemoteVisibility("provider_password", false)).isFalse()
        assertThat(context.getSharedPreferences(HomePreferencesStore.PREFERENCES, Context.MODE_PRIVATE)
            .contains("provider_password")).isFalse()
        store.close()
    }

    @Test fun `malformed local shelf value safely uses the existing visible default`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(HomePreferencesStore.PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("apps", "malformed").commit()
        val store = HomePreferencesStore(context)
        assertThat(store.preferences.value.isVisible(HomeSection.APPS)).isTrue()
        store.close()
    }

    @Test fun `defaults ordering and moving both directions are stable`() {
        val store = HomePreferencesStore(RuntimeEnvironment.getApplication())
        assertThat(store.currentPreferences.shelfOrder).containsExactlyElementsIn(HomeSection.entries.map { it.key }).inOrder()
        store.moveShelf("recent", -1)
        assertThat(store.currentPreferences.shelfOrder.take(2)).containsExactly("recent", "continue").inOrder()
        store.moveShelf("recent", 1)
        assertThat(store.currentPreferences.shelfOrder).containsExactlyElementsIn(HomeShelfOrder.defaults).inOrder()
        store.moveShelf("continue", -1)
        assertThat(store.currentPreferences.shelfOrder).containsExactlyElementsIn(HomeShelfOrder.defaults).inOrder()
        store.close()
    }

    @Test fun `hidden shelves retain positions and restore after store recreation`() {
        val context = RuntimeEnvironment.getApplication()
        val store = HomePreferencesStore(context)
        store.moveShelf("movies", -1)
        val order = store.currentPreferences.shelfOrder
        store.setVisible(HomeSection.MOVIES, false)
        store.close()
        val restored = HomePreferencesStore(context)
        assertThat(restored.currentPreferences.shelfOrder).isEqualTo(order)
        assertThat(restored.currentPreferences.isVisible(HomeSection.MOVIES)).isFalse()
        restored.setVisible(HomeSection.MOVIES, true)
        assertThat(restored.currentPreferences.shelfOrder).isEqualTo(order)
        restored.close()
    }

    @Test fun `visibility only migration and corrupted presentation recover independently`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(HomePreferencesStore.PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean("recent", false).putString(HomePreferencesStore.ORDER, "invalid JSON")
            .putInt(HomePreferencesStore.PRESENTATION, 8).commit()
        val store = HomePreferencesStore(context)
        assertThat(store.currentPreferences.isVisible(HomeSection.RECENT)).isFalse()
        assertThat(store.currentPreferences.shelfOrder).isEqualTo(HomeShelfOrder.defaults)
        assertThat(store.currentPreferences.presentation).isEqualTo(HomePresentation())
        store.close()
    }

    @Test fun `normalization deduplicates retains safe future keys and inserts missing shelves`() {
        val order = HomeShelfOrder.normalize(listOf("movies", "movies", "future_shelf", "https://secret", "apps"))
        assertThat(order).containsNoDuplicates()
        assertThat(order).containsAtLeastElementsIn(HomeShelfOrder.defaults)
        assertThat(order).contains("future_shelf")
        assertThat(order).doesNotContain("https://secret")
        val oversized = HomeShelfOrder.normalize(List(1000) { "future_$it" })
        assertThat(oversized.size).isAtMost(HomeShelfOrder.MAX_SHELVES)
        assertThat(oversized).containsAtLeastElementsIn(HomeShelfOrder.defaults)
        assertThat(HomeShelfOrder.normalize(listOf("continue", "recent", "favourites", "on_now", "movies", "series"))).contains("apps")
    }

    @Test fun `UUID groups can be positioned hidden restored and reset without modifying group data`() {
        val context = RuntimeEnvironment.getApplication()
        val group = "group:00000000-0000-0000-0000-000000000001"
        val store = HomePreferencesStore(context)
        store.moveShelf(group, -1, listOf(group))
        assertThat(store.currentPreferences.shelfOrder.indexOf(group)).isEqualTo(3)
        store.setVisible(group, false)
        assertThat(store.currentPreferences.isVisible(group)).isFalse()
        store.setPresentation(HomePresentation(layout = HomeLayout.CINEMA, movieSize = HomeCardSize.LARGE))
        store.close()
        val restored = HomePreferencesStore(context)
        assertThat(restored.currentPreferences.presentation.layout).isEqualTo(HomeLayout.CINEMA)
        restored.reset()
        assertThat(restored.currentPreferences.presentation).isEqualTo(HomePresentation())
        assertThat(restored.currentPreferences.shelfOrder).isEqualTo(HomeShelfOrder.defaults)
        assertThat(restored.currentPreferences.isVisible(group)).isTrue()
        assertThat(restored.groupVisibility[group]).isTrue()
        restored.close()
    }

    @Test fun `all layouts persist and reset order leaves visibility and presentation unchanged`() {
        val store = HomePreferencesStore(RuntimeEnvironment.getApplication())
        HomeLayout.entries.forEach { layout ->
            store.setPresentation(HomePresentation(layout = layout))
            assertThat(store.currentPreferences.presentation.layout).isEqualTo(layout)
        }
        store.setVisible(HomeSection.APPS, false)
        store.moveShelf("apps", -1)
        store.resetOrder()
        assertThat(store.currentPreferences.presentation.layout).isEqualTo(HomeLayout.CINEMA)
        assertThat(store.currentPreferences.isVisible(HomeSection.APPS)).isFalse()
        store.close()
    }

    @Test fun `rapid independent edits use current storage rather than an older displayed snapshot`() {
        val context = RuntimeEnvironment.getApplication()
        val first = HomePreferencesStore(context)
        val second = HomePreferencesStore(context)
        first.updatePresentation { it.copy(movieSize = HomeCardSize.SMALL) }
        second.updatePresentation { it.copy(seriesSize = HomeCardSize.LARGE) }
        assertThat(first.currentPreferences.presentation.movieSize).isEqualTo(HomeCardSize.SMALL)
        assertThat(first.currentPreferences.presentation.seriesSize).isEqualTo(HomeCardSize.LARGE)
        first.toggleVisibility("apps")
        second.toggleVisibility("apps")
        assertThat(first.currentPreferences.isVisible(HomeSection.APPS)).isTrue()
        first.close(); second.close()
    }
}
