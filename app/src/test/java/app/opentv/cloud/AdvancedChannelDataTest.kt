/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.cloud

import android.app.Application
import androidx.room.Room
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.data.parser.ChannelNameNormalizer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
class AdvancedChannelDataTest {
    private lateinit var database: OpenTvDatabase
    private val blocked = mutableSetOf<String>()
    private val device = "00000000-0000-0000-0000-000000000001"
    private lateinit var adapter: AdvancedChannelData
    private val streamCanary = "https://provider.invalid/private-user/private-pass/live/42.m3u8"

    @Before fun setUp() = runBlocking {
        database = newDatabase()
        adapter = AdvancedChannelData(database) { blocked }
        database.sources().insert(source())
        Unit
    }

    @After fun tearDown() { database.close() }

    private fun newDatabase(): OpenTvDatabase = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(), OpenTvDatabase::class.java).allowMainThreadQueries().build()

    private fun source(id: Long = 1, enabled: Boolean = true) = Source(id = id, name = "Provider", kind = SourceKind.XTREAM,
        url = "https://provider.invalid", username = "private-user", password = "private-pass", enabled = enabled)

    private fun channel(id: Long = 1, source: Long = 1, name: String = "BBC One", epg: String? = "bbc.one", category: String? = "general") =
        Channel(id = id, sourceId = source, streamId = "provider-local-$id", name = name, displayName = name,
            categoryId = category, logoUrl = null, epgChannelId = epg, number = null, streamUrl = streamCanary)

    private fun movie(id: Long = 1, source: Long = 1, name: String = "Movie", tmdb: String = "123") = Movie(
        id = id, sourceId = source, streamId = "movie-$id", name = name, categoryId = "films", posterUrl = null,
        rating = null, year = 2025, plot = null, durationSeconds = null, containerExtension = "mp4",
        streamUrl = streamCanary, tmdbId = tmdb)

    private fun series(id: Long = 1, source: Long = 1, name: String = "Series", tmdb: String = "321") = Series(
        id = id, sourceId = source, seriesId = "series-$id", name = name, categoryId = "shows", posterUrl = null,
        rating = null, year = 2024, plot = null, tmdbId = tmdb)

    private fun record(kind: String, key: String = CloudIdentity.channel("BBC One", "bbc.one"),
        text: String? = null, bool: Boolean? = null, number: Int? = null) = AdvancedCloudRecord(
        key, kind, System.currentTimeMillis(), device, textValue = text, boolValue = bool, intValue = number)

    @Test fun snapshotsOnlyPublicIdentityAndOrganizationFields() = runBlocking {
        database.channels().upsertAll(listOf(channel().copy(hidden = true, customName = "News", sortIndex = 5)))
        database.movies().upsertAll(listOf(movie()))
        database.series().upsertAll(listOf(series()))
        val records = adapter.snapshot(device, System.currentTimeMillis())
        assertEquals(setOf("channel_hidden", "channel_name", "channel_order", "channel_favourite_state",
            "movie_favourite_state", "series_favourite_state"), records.map { it.kind }.toSet())
        assertEquals("News", records.single { it.kind == "channel_name" }.textValue)
        assertEquals(5, records.single { it.kind == "channel_order" }.intValue)
        assertEquals(false, records.single { it.kind == "channel_favourite_state" }.boolValue)
        val encoded = Json.encodeToString(ListSerializer(AdvancedCloudRecord.serializer()), records)
        for (canary in listOf("provider.invalid", "private-user", "private-pass", "provider-local-1", "movie-1", "series-1")) {
            assertFalse("Cloud snapshot leaked $canary", canary in encoded)
        }
    }

    @Test fun matchingOtherTvUsesFingerprintAndNeverOverwritesPlayableFields() = runBlocking {
        database.channels().upsertAll(listOf(channel().copy(hidden = true, customName = "News", sortIndex = 7, favourite = true)))
        database.movies().upsertAll(listOf(movie().copy(favourite = true)))
        database.series().upsertAll(listOf(series().copy(favourite = true)))
        val records = adapter.snapshot(device, System.currentTimeMillis())
        val receiving = newDatabase()
        try {
            receiving.sources().insert(source(77))
            val local = channel(202, 77).copy(streamId = "local-stream-id", streamUrl = "https://local.invalid/play")
            receiving.channels().upsertAll(listOf(local))
            receiving.movies().upsertAll(listOf(movie(303, 77, "Translated movie")))
            receiving.series().upsertAll(listOf(series(404, 77, "Translated series")))
            assertEquals(6, AdvancedChannelData(receiving).apply(records))
            assertEquals(local.copy(hidden = true, customName = "News", sortIndex = 7, favourite = true),
                receiving.channels().byId(202))
            assertTrue(receiving.movies().byId(303)!!.favourite)
            assertTrue(receiving.series().byId(404)!!.favourite)
        } finally { receiving.close() }
    }

    @Test fun fieldUpdatesPreserveIndependentLocalEditsAndSupportClearingNames() = runBlocking {
        database.channels().upsertAll(listOf(channel().copy(hidden = true, customName = "Local name", sortIndex = 8, favourite = true)))
        assertEquals(1, adapter.apply(listOf(record("channel_order", number = 2))))
        assertEquals(channel().copy(hidden = true, customName = "Local name", sortIndex = 2, favourite = true), database.channels().byId(1))
        assertEquals(2, adapter.apply(listOf(record("channel_name", text = ""), record("channel_hidden", bool = false))))
        assertNull(database.channels().byId(1)!!.customName)
        assertFalse(database.channels().byId(1)!!.hidden)
        assertTrue(database.channels().byId(1)!!.favourite)
    }

    @Test fun explicitFavouriteFalseRemovesMatchingChannelMovieAndSeriesFavourites() = runBlocking {
        database.channels().upsertAll(listOf(channel().copy(favourite = true)))
        database.movies().upsertAll(listOf(movie().copy(favourite = true)))
        database.series().upsertAll(listOf(series().copy(favourite = true)))
        val removals = listOf(record("channel_favourite_state", bool = false),
            record("movie_favourite_state", CloudIdentity.media("movie", "Different title", 1990, "123"), bool = false),
            record("series_favourite_state", CloudIdentity.media("series", "Different title", 1990, "321"), bool = false))
        assertEquals(3, adapter.apply(removals))
        assertFalse(database.channels().byId(1)!!.favourite)
        assertFalse(database.movies().byId(1)!!.favourite)
        assertFalse(database.series().byId(1)!!.favourite)
    }

    @Test fun ambiguousChannelsAndMediaAreSkippedInBothDirections() = runBlocking {
        database.sources().insert(source(2))
        database.channels().upsertAll(listOf(channel(), channel(2, 2)))
        database.movies().upsertAll(listOf(movie(), movie(2, 2)))
        database.series().upsertAll(listOf(series(), series(2, 2)))
        assertTrue(adapter.snapshot(device, System.currentTimeMillis()).isEmpty())
        assertEquals(0, adapter.apply(listOf(record("channel_hidden", bool = true),
            record("channel_name", text = "Wrong"), record("channel_order", number = 4),
            record("channel_favourite_state", bool = true),
            record("movie_favourite_state", CloudIdentity.media("movie", "Movie", 2025, "123"), bool = true),
            record("series_favourite_state", CloudIdentity.media("series", "Series", 2024, "321"), bool = true))))
        assertEquals(channel(), database.channels().byId(1))
        assertEquals(channel(2, 2), database.channels().byId(2))
    }

    @Test fun missingChannelsAreRetriedWhenAvailableAndNeverCreated() = runBlocking {
        val remote = record("channel_name", text = "News")
        assertTrue(adapter.snapshot(device, System.currentTimeMillis()).isEmpty())
        assertEquals(0, adapter.apply(listOf(remote)))
        assertNull(database.channels().byId(1))
        database.channels().upsertAll(listOf(channel()))
        assertEquals(1, adapter.apply(listOf(remote)))
        assertEquals("News", database.channels().byId(1)!!.customName)
        database.channels().deleteForSource(1)
        assertTrue(adapter.snapshot(device, System.currentTimeMillis()).isEmpty())
    }

    @Test fun disabledSourcesDoNotUploadOrReceiveChangesAndDoNotCauseAmbiguity() = runBlocking {
        database.sources().insert(source(2, enabled = false))
        database.channels().upsertAll(listOf(channel(), channel(2, 2)))
        database.movies().upsertAll(listOf(movie(2, 2)))
        database.series().upsertAll(listOf(series(2, 2)))
        assertEquals(4, adapter.snapshot(device, System.currentTimeMillis()).size)
        assertEquals(1, adapter.apply(listOf(record("channel_hidden", bool = true))))
        assertTrue(database.channels().byId(1)!!.hidden)
        assertFalse(database.channels().byId(2)!!.hidden)
        database.sources().update(source(enabled = false))
        assertTrue(adapter.snapshot(device, System.currentTimeMillis()).isEmpty())
        assertEquals(0, adapter.apply(listOf(record("channel_hidden", bool = false))))
        assertTrue(database.channels().byId(1)!!.hidden)
    }

    @Test fun parentalRestrictionsStayLocalAndCannotBeOverriddenByCloud() = runBlocking {
        database.categories().upsertAll(listOf(Category("general", 1, "UK| ADULT HD", StreamKind.LIVE)))
        blocked += ChannelNameNormalizer.normalize("UK| ADULT HD").groupKey
        database.channels().upsertAll(listOf(channel().copy(hidden = true)))
        assertTrue(adapter.snapshot(device, System.currentTimeMillis()).isEmpty())
        assertEquals(0, adapter.apply(listOf(record("channel_hidden", bool = false), record("channel_name", text = "Safe"))))
        assertEquals(channel().copy(hidden = true), database.channels().byId(1))
    }

    @Test fun localEditAfterEngineSnapshotCannotBeOverwrittenByRemoteApply() = runBlocking {
        database.channels().upsertAll(listOf(channel().copy(customName = "Original", sortIndex = 1)))
        val expected = adapter.snapshot(device, System.currentTimeMillis())
        database.channels().setCustomName(1, "Local edit during apply")
        assertEquals(1, adapter.apply(listOf(record("channel_name", text = "Remote name"),
            record("channel_order", number = 9)), expected))
        assertEquals("Local edit during apply", database.channels().byId(1)!!.customName)
        assertEquals(9, database.channels().byId(1)!!.sortIndex)
    }

    @Test fun malformedUnknownFutureAndDeletedRecordsLeaveLocalDataIntact() = runBlocking {
        database.channels().upsertAll(listOf(channel()))
        val good = record("channel_hidden", bool = true)
        assertEquals(0, adapter.apply(listOf(good.copy(recordKey = streamCanary), good.copy(deviceId = "token"),
            good.copy(updatedAtMillis = System.currentTimeMillis() + 600_000), good.copy(deleted = true),
            good.copy(kind = "stream_url"), good.copy(boolValue = null),
            record("channel_order", number = -1))))
        assertEquals(channel(), database.channels().byId(1))
    }
}
