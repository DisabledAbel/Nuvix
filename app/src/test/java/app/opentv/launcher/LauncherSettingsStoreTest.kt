/*
 * This file is part of Nuvix, an independent modified version of OpenTV.
 * Copyright (C) 2026 The Nuvix Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.launcher

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
class LauncherSettingsStoreTest {
    @Before
    fun clearPreferences() {
        RuntimeEnvironment.getApplication().getSharedPreferences("nuvix_launcher", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `first launch defaults to Nuvix Home with no pinned apps`() {
        val store = LauncherSettingsStore(RuntimeEnvironment.getApplication())

        assertThat(store.preferences.value.startupDestination).isEqualTo(StartupDestination.HOME)
        assertThat(store.preferences.value.favoritePackages).isEmpty()
    }

    @Test
    fun `startup and ordered favorites survive recreating the settings store`() {
        val context = RuntimeEnvironment.getApplication()
        val store = LauncherSettingsStore(context)
        store.setStartupDestination(StartupDestination.APPS)
        store.pin("tv.alpha")
        store.pin("tv.beta")
        store.pin("tv.gamma")
        store.moveFavorite("tv.gamma", -1)
        store.unpin("tv.alpha")

        val restored = LauncherSettingsStore(context).preferences.value

        assertThat(restored.startupDestination).isEqualTo(StartupDestination.APPS)
        assertThat(restored.favoritePackages).containsExactly("tv.gamma", "tv.beta").inOrder()
    }

    @Test
    fun `missing installed apps remain in the desired order after availability filtering`() {
        val context = RuntimeEnvironment.getApplication()
        val store = LauncherSettingsStore(context)
        store.setStartupDestination(StartupDestination.LAST_WATCHED)
        store.pin("tv.alpha")
        store.pin("tv.removed")
        store.pin("tv.beta")
        val visible = store.pruneFavorites(setOf("tv.beta", "tv.new", "tv.alpha"))

        val restored = LauncherSettingsStore(context).preferences.value

        assertThat(restored.startupDestination).isEqualTo(StartupDestination.LAST_WATCHED)
        assertThat(visible).containsExactly("tv.alpha", "tv.beta").inOrder()
        assertThat(restored.favoritePackages).containsExactly("tv.alpha", "tv.removed", "tv.beta").inOrder()
        assertThat(store.pruneFavorites(setOf("tv.removed", "tv.alpha", "tv.beta")))
            .containsExactly("tv.alpha", "tv.removed", "tv.beta").inOrder()
    }

    @Test
    fun `remote app order preserves missing apps and propagates an explicit unpin`() {
        val context = RuntimeEnvironment.getApplication()
        val store = LauncherSettingsStore(context)
        store.pin("tv.previous")
        assertThat(store.applyRemoteAppOrder(listOf("tv.missing", "tv.beta", "tv.alpha"))).isTrue()
        assertThat(store.pruneFavorites(setOf("tv.alpha", "tv.beta")))
            .containsExactly("tv.beta", "tv.alpha").inOrder()
        assertThat(store.applyRemoteAppOrder(listOf("tv.missing", "tv.alpha"))).isTrue()

        assertThat(LauncherSettingsStore(context).preferences.value.favoritePackages)
            .containsExactly("tv.missing", "tv.alpha").inOrder()
    }

    @Test
    fun `invalid remote app orders leave desired pins intact`() {
        val store = LauncherSettingsStore(RuntimeEnvironment.getApplication())
        store.pin("tv.alpha")

        assertThat(store.applyRemoteAppOrder(listOf("https://provider/password"))).isFalse()
        assertThat(store.applyRemoteAppOrder(listOf("tv.alpha", "tv.alpha"))).isFalse()
        assertThat(store.applyRemoteAppOrder((0..200).map { "tv.app$it" })).isFalse()
        assertThat(store.preferences.value.favoritePackages).containsExactly("tv.alpha")
    }

    @Test
    fun `existing local pin lists above the cloud bound are never silently truncated`() {
        val context = RuntimeEnvironment.getApplication()
        val legacy = (0..200).map { "tv.legacy$it" }
        context.getSharedPreferences("nuvix_launcher", Context.MODE_PRIVATE).edit()
            .putString("favorite_packages", org.json.JSONArray(legacy).toString()).commit()
        val store = LauncherSettingsStore(context)
        assertThat(store.currentPreferences.favoritePackages).containsExactlyElementsIn(legacy).inOrder()
        store.pin("tv.additional")
        assertThat(LauncherSettingsStore(context).currentPreferences.favoritePackages)
            .containsExactlyElementsIn(legacy + "tv.additional").inOrder()
        store.close()
    }

    @Test
    fun `corrupted stored preferences fall back to a usable home screen`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvix_launcher", Context.MODE_PRIVATE).edit()
            .putString("startup_destination", "RETIRED_DESTINATION")
            .putString("favorite_packages", "invalid json")
            .commit()

        val restored = LauncherSettingsStore(context).preferences.value

        assertThat(restored.startupDestination).isEqualTo(StartupDestination.HOME)
        assertThat(restored.favoritePackages).isEmpty()
    }
}
