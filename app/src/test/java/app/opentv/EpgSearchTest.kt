/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv

import android.app.Application
import androidx.room.Room
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.EpgSearchFilter
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EpgSearchTest {
    private lateinit var db: OpenTvDatabase
    private lateinit var repository: EpgRepository
    private val now = 1_900_000_000_000L
    private val hour = TimeUnit.HOURS.toMillis(1)

    @Before fun prepare(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).build()
        db.sources().insert(Source(id = 1, name = "One", kind = SourceKind.M3U, url = "https://example.invalid"))
        db.sources().insert(Source(id = 2, name = "Two", kind = SourceKind.M3U, url = "https://example.invalid"))
        db.sources().insert(Source(id = 3, name = "Disabled", kind = SourceKind.M3U, url = "https://example.invalid", enabled = false))
        db.epgFeeds().insert(EpgFeed(id = 1, name = "Guide"))
        db.epgFeeds().insert(EpgFeed(id = 2, name = "Disabled", enabled = false))
        val http = OkHttpClient()
        repository = EpgRepository(db.programmes(), db.epgFeeds(), db.epgAliases(), db.channels(), db.sources(),
            XtreamApi(http), http, db, context.cacheDir)
    }

    @After fun close() { db.close() }

    private fun channel(id: Long, epg: String = "one", source: Long = 1, category: String = "general") = Channel(
        id = id, sourceId = source, streamId = "fixture-$id", name = "Provider One", displayName = "Channel One",
        categoryId = category, logoUrl = null, epgChannelId = epg, number = id.toInt(), streamUrl = "https://example.invalid/$id",
    )

    private fun programme(id: Long, title: String = "Fixture", epg: String = "one", start: Long = now,
        end: Long = start + hour, category: String? = null) = Programme(
        id = id, feedId = 1, epgChannelId = epg, startUtcMillis = start, endUtcMillis = end, title = title, category = category,
    )

    private suspend fun search(query: String, filter: EpgSearchFilter = EpgSearchFilter.ALL) =
        repository.programmeSearch(query, filter, now).first()

    @Test fun `search matches titles channel names genres and descriptions`(): Unit = runBlocking {
        db.channels().upsertAll(listOf(channel(1).copy(customName = "Living Room")))
        db.programmes().upsertAll(listOf(programme(1, title = "Ocean Journey", category = "Nature").copy(description = "Following penguins")))
        for (query in listOf("ocean", "Provider", "Channel", "Living", "Nature", "penguins")) {
            val result = search(query).single()
            assertThat(result.programme.id).isEqualTo(1L)
            assertThat(result.channel.id).isEqualTo(1L)
        }
        assertThat(search("unavailable")).isEmpty()
        assertThat(search("o")).isEmpty()
    }

    @Test fun `LIKE wildcards are literal user input`(): Unit = runBlocking {
        db.channels().upsertAll(listOf(channel(1)))
        db.programmes().upsertAll(listOf(programme(1, "Report 100%_complete"), programme(2, "Other report", start = now + hour)))
        assertThat(search("%_").map { it.programme.id }).containsExactly(1L)
        assertThat(search("' OR 1=1 --")).isEmpty()
    }

    @Test fun `airing upcoming and horizon respect exact programme boundaries`(): Unit = runBlocking {
        db.channels().upsertAll(listOf(channel(1)))
        db.programmes().upsertAll(listOf(
            programme(1, "Fixture ended", start = now - hour, end = now),
            programme(2, "Fixture airing"),
            programme(3, "Fixture future", start = now + hour),
            programme(4, "Fixture distant", start = now + TimeUnit.DAYS.toMillis(7)),
        ))
        assertThat(search("Fixture", EpgSearchFilter.CURRENTLY_AIRING).map { it.programme.id }).containsExactly(2L)
        assertThat(search("Fixture", EpgSearchFilter.UPCOMING).map { it.programme.id }).containsExactly(3L)
        assertThat(search("Fixture").map { it.programme.id }).containsExactly(2L, 3L).inOrder()
    }

    @Test fun `sports film and series filters use supplied metadata only`(): Unit = runBlocking {
        db.channels().upsertAll(listOf(channel(1)))
        db.programmes().upsertAll(listOf(
            programme(1, "Fixture Football Final", category = "Documentary"),
            programme(2, "Fixture game", start = now + hour, category = "Sports"),
            programme(3, "Fixture cinema", start = now + 2 * hour, category = "Film"),
            programme(4, "Fixture episode", start = now + 3 * hour).copy(season = 1, episode = 2),
            programme(5, "Fixture comedy", start = now + 4 * hour, category = "Series"),
        ))
        assertThat(search("Fixture", EpgSearchFilter.SPORTS).map { it.programme.id }).containsExactly(2L)
        assertThat(search("Fixture", EpgSearchFilter.MOVIES).map { it.programme.id }).containsExactly(3L)
        assertThat(search("Fixture", EpgSearchFilter.SERIES).map { it.programme.id }).containsExactly(4L, 5L)
    }

    @Test fun `hidden channels disabled feeds disabled sources and orphan programmes stay out`(): Unit = runBlocking {
        db.channels().upsertAll(listOf(channel(1), channel(2, "hidden").copy(hidden = true),
            channel(3, "disabled-feed"), channel(4, "disabled-source", source = 3)))
        db.programmes().upsertAll(listOf(programme(1), programme(2, epg = "hidden"),
            programme(3, epg = "disabled-feed").copy(feedId = 2), programme(4, epg = "disabled-source"),
            programme(5, epg = "orphan")))
        assertThat(search("Fixture").map { it.programme.id }).containsExactly(1L)
    }

    @Test fun `manual override precedes provider and matching guide with fallback for missing data`(): Unit = runBlocking {
        db.channels().upsertAll(listOf(channel(1, "provider").copy(epgOverrideId = "override", matchedEpgId = "matched")))
        db.programmes().upsertAll(listOf(programme(1, epg = "override"), programme(2, epg = "provider"), programme(3, epg = "matched")))
        assertThat(search("Fixture").map { it.programme.id }).containsExactly(1L)
        db.programmes().deleteForFeed(1)
        db.programmes().upsertAll(listOf(programme(2, epg = "provider"), programme(3, epg = "matched")))
        assertThat(search("Fixture").map { it.programme.id }).containsExactly(2L)
    }

    @Test fun `quality variants collapse without mixing providers`(): Unit = runBlocking {
        db.channels().upsertAll(listOf(channel(1).copy(groupKey = "one"), channel(2).copy(groupKey = "one"),
            channel(3, source = 2).copy(groupKey = "one")))
        db.programmes().upsertAll(listOf(programme(1)))
        assertThat(search("Fixture").map { it.channel.sourceId }).containsExactly(1L, 2L)
    }

    @Test fun `parental categories exclude before the result limit and remain source scoped`(): Unit = runBlocking {
        val lockedCategory = "locked|category:é"
        db.channels().upsertAll(listOf(channel(1, "locked", category = lockedCategory), channel(2, "safe", category = "safe"),
            channel(3, "safe-provider", source = 2, category = lockedCategory)))
        db.programmes().upsertAll((1L..210L).map { programme(it, epg = "locked", start = now + it * 1000) } +
            listOf(programme(211, epg = "safe", start = now + hour), programme(212, epg = "safe-provider", start = now + 2 * hour)))
        val result = repository.programmeSearch("Fixture", EpgSearchFilter.ALL, now, limit = 200,
            excludedCategories = setOf(1L to lockedCategory)).first()
        assertThat(result.map { it.programme.id }).containsExactly(211L, 212L)
        assertThat(repository.programmeSearch("Fixture", EpgSearchFilter.ALL, now, limit = 9999).first()).hasSize(200)
    }

    @Test fun `viewport queries materialise only requested programme rows from a large guide`(): Unit = runBlocking {
        db.programmes().upsertAll((1L..2000L).map { programme(it, epg = "epg-$it") })
        val visibleIds = (1..60).map { "epg-$it" }
        assertThat(repository.observeWindowForChannels(visibleIds, now, now + hour).first()).hasSize(60)
        assertThat(repository.observeEpgChannelIds(now, now + hour).first()).hasSize(2000)
        assertThat(repository.observeAiringChannelIds(now).first()).hasSize(2000)
        assertThat(repository.observeWindowForChannels(emptyList(), now, now + hour).first()).isEmpty()
        assertThat(repository.observeWindowForChannels(visibleIds, now + hour, now).first()).isEmpty()
    }

    @Test fun `channel batches beyond the SQLite legacy bind limit retain every programme`(): Unit = runBlocking {
        db.programmes().upsertAll((1L..1201L).map { programme(it, epg = "epg-$it") })
        val ids = (1..1201).map { "epg-$it" }
        assertThat(repository.observeWindowForChannels(ids + ids.take(2), now, now + hour).first()).hasSize(1201)
    }
}
