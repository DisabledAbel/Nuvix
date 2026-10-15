/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.player

import android.app.Application
import androidx.room.Room
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.EpgRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MiniGuideEpgTest {
    private lateinit var database: OpenTvDatabase
    private lateinit var repository: EpgRepository
    private var feed = 0L

    @Before fun prepare() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).build()
        val http = OkHttpClient.Builder().addInterceptor { error("Mini Guide must not access the network") }.build()
        repository = EpgRepository(database.programmes(), database.epgFeeds(), database.epgAliases(),
            database.channels(), database.sources(), XtreamApi(http), http, database, context.cacheDir)
        feed = database.epgFeeds().insert(EpgFeed(name = "Local fixture"))
    }

    @After fun close() { database.close() }

    @Test fun `query returns only current and next for the requested channel`() = runBlocking {
        database.programmes().upsertAll((0..200).map { programme("test", it * 100L, (it + 1) * 100L) } + programme("other", 0, 100))
        val rows = withTimeout(5_000) { repository.observeMiniGuide(listOf("test"), 50).first() }
        assertThat(rows.map { it.startUtcMillis }).containsExactly(0L, 100L).inOrder()
    }

    @Test fun `candidate precedence skips stale ids and disabled feeds`() = runBlocking {
        val disabled = database.epgFeeds().insert(EpgFeed(name = "Disabled", enabled = false))
        database.programmes().upsertAll(listOf(
            programme("override", 0, 20), programme("provider", 0, 100).copy(feedId = disabled),
            programme("matched", 0, 100), programme("fallback", 0, 100),
        ))
        val rows = withTimeout(5_000) { repository.observeMiniGuide(listOf("override", "provider", "matched", "fallback"), 50).first() }
        assertThat(rows.map { it.epgChannelId }).containsExactly("matched")
    }

    @Test fun `empty ids gaps and expired schedules return friendly fallback data`() = runBlocking {
        database.programmes().upsertAll(listOf(programme("old", 0, 20), programme("gap", 200, 300)))
        assertThat(repository.observeMiniGuide(emptyList(), 50).first()).isEmpty()
        assertThat(repository.observeMiniGuide(listOf("old"), 50).first()).isEmpty()
        val rows = repository.observeMiniGuide(listOf("gap"), 50).first()
        assertThat(MiniGuideProgrammes.at(rows, 50).current).isNull()
        assertThat(MiniGuideProgrammes.at(rows, 50).next?.startUtcMillis).isEqualTo(200)
    }

    @Test fun `new boundary query selects newly live programme from the existing cache`() = runBlocking {
        database.programmes().upsertAll(listOf(programme("test", 0, 100), programme("test", 100, 200)))
        val before = repository.observeMiniGuide(listOf("test"), 99).first()
        val after = repository.observeMiniGuide(listOf("test"), 100).first()
        assertThat(MiniGuideProgrammes.at(before, 99).current?.startUtcMillis).isEqualTo(0)
        assertThat(MiniGuideProgrammes.at(after, 100).current?.startUtcMillis).isEqualTo(100)
    }

    private fun programme(id: String, start: Long, end: Long) = Programme(
        feedId = feed, epgChannelId = id, startUtcMillis = start, endUtcMillis = end, title = "$id $start",
    )
}
