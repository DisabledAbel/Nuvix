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
}
