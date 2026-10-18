/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.sports

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Regression tests for sports preferences. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SportsPreferencesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    /** Creates isolated fixtures before each test. */
    @Before fun clear() { context.getSharedPreferences("nuvix_sports", Context.MODE_PRIVATE).edit().clear().commit() }
    /** Checks: defaults Keep Sports Channels And Possible Matches Visible Without Account. */
    @Test fun defaultsKeepSportsChannelsAndPossibleMatchesVisibleWithoutAccount() {
        val store = SportsPreferencesStore(context)
        assertEquals(SportsPreferences(), store.preferences.value)
        assertTrue(store.preferences.value.showPossible)
        store.close()
    }
    /** Checks: typed Settings Persist And Independent Observers Receive Edits. */
    @Test fun typedSettingsPersistAndIndependentObserversReceiveEdits() {
        val first = SportsPreferencesStore(context); val second = SportsPreferencesStore(context)
        val custom = SportsPreferences(SportsFilter.FAVORITE_TEAMS, SportsStreamSort.FAVORITES, false, false, false)
        first.set(custom)
        assertEquals(custom, second.preferences.value)
        assertEquals(custom, SportsPreferencesStore(context).also { it.close() }.preferences.value)
        first.close(); second.close()
    }
    /** Checks: corrupt Local Values Recover Independently. */
    @Test fun corruptLocalValuesRecoverIndependently() {
        context.getSharedPreferences("nuvix_sports", Context.MODE_PRIVATE).edit().putString("default_view", "INVALID")
            .putInt("show_scores", 42).putString("stream_sort", "PROVIDER").commit()
        val store = SportsPreferencesStore(context)
        assertEquals(SportsFilter.ALL, store.preferences.value.defaultView)
        assertTrue(store.preferences.value.showScores)
        assertEquals(SportsStreamSort.PROVIDER, store.preferences.value.streamSort)
        store.close()
    }
}
