/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv

import android.app.Application
import androidx.room.Room
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.*
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

/** Regression tests for home channel queries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class HomeChannelQueriesTest {
    private lateinit var db: OpenTvDatabase
    /** Creates isolated fixtures before each test. */
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), OpenTvDatabase::class.java).allowMainThreadQueries().build()
    }
    /** Releases test resources and restores shared state after each test. */
    @After fun close() { db.close() }
    /** Provides a synthetic channel with the identity and state required by this test. */
    private fun channel(id: Long, source: Long = 1, category: String = "sports", hidden: Boolean = false, favorite: Boolean = false) =
        Channel(id = id, sourceId = source, streamId = "fixture-$id", name = "Channel $id", displayName = "Channel $id",
            categoryId = category, logoUrl = null, epgChannelId = null, number = id.toInt(), streamUrl = "https://example.invalid/$id", hidden = hidden, favourite = favorite)

    /** Checks: home Query Is Bounded Prioritizes Favorites And Excludes Hidden. */
    @Test fun homeQueryIsBoundedPrioritizesFavoritesAndExcludesHidden() = runBlocking {
        db.channels().upsertAll((1L..50L).map { channel(it, hidden = it == 1L, favorite = it == 50L) })
        val result = db.channels().observeHomeChannels(8).first()
        assertEquals(8, result.size)
        assertEquals(50L, result.first().id)
        assertFalse(result.any { it.hidden || it.id == 1L })
    }
    /** Checks: sports Uses Category Metadata From Matching Provider And Live Kind. */
    @Test fun sportsUsesCategoryMetadataFromMatchingProviderAndLiveKind() = runBlocking {
        db.categories().upsertAll(listOf(Category("sports", 1, "Sports HD", StreamKind.LIVE),
            Category("sports", 2, "Drama", StreamKind.LIVE), Category("sports", 3, "Sports movies", StreamKind.MOVIE)))
        db.channels().upsertAll(listOf(channel(1, 1), channel(2, 2), channel(3, 3), channel(4, 1, hidden = true)))
        assertEquals(listOf(1L), db.channels().observeSportsChannels().first().map { it.id })
    }
    /** Checks: deleted Or Hidden History Entries Do Not Become Launch Targets. */
    @Test fun deletedOrHiddenHistoryEntriesDoNotBecomeLaunchTargets() = runBlocking {
        db.channels().upsertAll(listOf(channel(1), channel(2, hidden = true)))
        assertEquals(listOf(1L), db.channels().observeByIds(listOf(1, 2, 999)).first().map { it.id })
        assertTrue(db.channels().observeByIds(emptyList()).first().isEmpty())
    }
}
