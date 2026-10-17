/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv

import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.util.UnstableApi
import androidx.room.Room
import app.opentv.connections.AccountConnectionManager
import app.opentv.connections.ConnectionPurpose
import app.opentv.connections.ConnectionRequest
import app.opentv.connections.ReservationResult
import app.opentv.connections.ReservationState
import app.opentv.connections.ReservationToken
import app.opentv.core.AppSettings
import app.opentv.core.RecentChannels
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.player.LivePlaybackSession
import app.opentv.player.PlayerController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real session, repositories, Room and allocator; suspended players never load a network stream. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LiveConnectionAdmissionTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val store = ViewModelStore()
    private val externalReservations = mutableListOf<ReservationToken>()
    private lateinit var app: Application
    private lateinit var database: OpenTvDatabase
    private lateinit var graph: ServiceLocator.Graph
    private lateinit var connections: AccountConnectionManager
    private var savedGraph: Any? = null
    private var savedConnections: Any? = null
    private var savedRecent: Any? = null
    private var sessionCount = 0

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        listOf("opentv_settings", "nuvix_private_settings", "nuvix_recent_channels", "nuvix_connection_settings")
            .forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(app, OpenTvDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        val settings = AppSettings::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(app)
        graph = ServiceLocator.Graph(app)
        // Match the established Guide integration fixture: replace encrypted disk opening only.
        setGraphDelegate("database", database)
        setGraphDelegate("settings", settings)
        savedGraph = replaceSingleton(ServiceLocator::class.java, graph)
        connections = AccountConnectionManager.createForTest(app)
        savedConnections = replaceSingleton(AccountConnectionManager::class.java, connections)
        savedRecent = replaceSingleton(RecentChannels::class.java, null)
    }

    @After fun close() {
        store.clear()
        scheduler.runCurrent()
        externalReservations.forEach(connections::release)
        database.close()
        replaceSingleton(ServiceLocator::class.java, savedGraph)
        replaceSingleton(AccountConnectionManager::class.java, savedConnections)
        replaceSingleton(RecentChannels::class.java, savedRecent)
        Dispatchers.resetMain()
    }

    private fun setGraphDelegate(name: String, value: Any) {
        ServiceLocator.Graph::class.java.getDeclaredField("${name}\$delegate")
            .apply { isAccessible = true }.set(graph, lazyOf(value))
    }

    private fun replaceSingleton(type: Class<*>, value: Any?): Any? =
        type.getDeclaredField("instance").run {
            isAccessible = true
            get(null).also { set(null, value) }
        }

    private suspend fun source(id: Long, maximum: Int?): Source {
        val source = Source(id = id, name = "Offline account $id", kind = SourceKind.M3U,
            url = "https://account-$id.example.invalid/fixture.m3u")
        database.sources().insert(source)
        connections.registerSource(source)
        connections.setMaximumConnections(id, maximum)
        return source
    }

    private suspend fun channel(id: Long, sourceId: Long): Channel {
        val channel = Channel(id = id, sourceId = sourceId, streamId = "$id", name = "Channel $id",
            categoryId = null, logoUrl = null, epgChannelId = null, number = id.toInt(),
            streamUrl = "https://account-$sourceId.example.invalid/live/$id.ts")
        database.channels().upsertAll(listOf(channel))
        return channel
    }

    private fun session(): LivePlaybackSession = LivePlaybackSession(app).also {
        store.put("live-admission-${sessionCount++}", it)
        // Admission remains real. Suspension applies before play(), avoiding prepare() and I/O.
        it.setForeground(false)
    }

    private fun recording(sourceId: Long, id: String): ReservationToken {
        val token = (connections.reserve(ConnectionRequest(id, 1, sourceId, ConnectionPurpose.RECORDING))
            as ReservationResult.Reserved).token
        connections.markActive(token)
        externalReservations += token
        return token
    }

    @Test fun repeatedManualRecordingRequestsBeforeServiceDeliveryReuseOneDurableBooking() = runTest(dispatcher) {
        source(1, 2)
        val selected = channel(11, 1)
        val first = graph.recordingEngine.startChannel(selected)
        val second = graph.recordingEngine.startChannel(selected)
        assertThat(second).isEqualTo(first)
        assertThat(database.recordings().all()).hasSize(1)
        assertThat(connections.snapshotForSource(1)!!.locallyReserved).isEqualTo(0)
    }

    @Test fun independentAccountsKeepSeparateLiveOwnersAndRecordingReclaimsOnlyItsOwnAccount() = runTest(dispatcher) {
        source(1, 1)
        source(2, 1)
        val firstChannel = channel(11, 1)
        val secondChannel = channel(22, 2)
        val first = session()
        val second = session()
        first.openChannel(firstChannel.id)
        second.openChannel(secondChannel.id)
        scheduler.runCurrent()
        val firstPlayer = requireNotNull(first.controllerOrNull.value)
        val secondPlayer = requireNotNull(second.controllerOrNull.value)
        assertThat(firstPlayer.isReleased).isFalse()
        assertThat(secondPlayer.isReleased).isFalse()
        assertThat(firstPlayer.player.currentMediaItem).isNull()
        assertThat(secondPlayer.player.currentMediaItem).isNull()
        assertThat(connections.snapshotForSource(1)!!.locallyReserved).isEqualTo(1)
        assertThat(connections.snapshotForSource(2)!!.locallyReserved).isEqualTo(1)

        val result = connections.reserveForRecording(
            ConnectionRequest("scheduled-recording", 1, 1, ConnectionPurpose.RECORDING), allowInterruptLive = true)
        assertThat(result).isInstanceOf(ReservationResult.Reserved::class.java)
        externalReservations += (result as ReservationResult.Reserved).token

        assertThat(firstPlayer.isReleased).isTrue()
        assertThat(first.active.value).isFalse()
        assertThat(secondPlayer.isReleased).isFalse()
        assertThat(second.controllerOrNull.value).isSameInstanceAs(secondPlayer)
        assertThat(second.currentChannel.value?.id).isEqualTo(secondChannel.id)
        assertThat(connections.snapshotForSource(2)!!.locallyReserved).isEqualTo(1)
    }

    @Test fun unknownAccountApprovalAdoptsOnlyThePromptedRequestAndIsNotReused() = runTest(dispatcher) {
        source(1, null)
        val selected = channel(11, 1)
        val next = channel(12, 1)
        val capture = recording(1, "existing-recording")
        val live = session()
        live.openChannel(selected.id)
        scheduler.runCurrent()
        val prompt = requireNotNull(live.connectionPrompt.value)
        assertThat(live.controllerOrNull.value).isNull()
        assertThat(connections.snapshotForSource(1)!!.locallyReserved).isEqualTo(1)

        live.answerConnectionPrompt(true)
        scheduler.runCurrent()

        assertThat(live.connectionPrompt.value).isNull()
        assertThat(live.currentChannel.value?.id).isEqualTo(selected.id)
        assertThat(live.controllerOrNull.value).isNotNull()
        val approvedToken = connections.snapshotForSource(1)!!.reservations
            .single { it.token.purpose == ConnectionPurpose.LIVE }.token
        assertThat(approvedToken.sessionId).isEqualTo(prompt.challenge.request.sessionId)
        assertThat(approvedToken.generation).isEqualTo(prompt.challenge.request.generation)
        assertThat(connections.approve(prompt.challenge)).isNull()
        assertThat(connections.reservationState(capture)).isEqualTo(ReservationState.ACTIVE)

        live.stop()
        live.openChannel(next.id)
        scheduler.runCurrent()
        val nextPrompt = requireNotNull(live.connectionPrompt.value)
        assertThat(nextPrompt.challenge.request.generation).isGreaterThan(approvedToken.generation)
        assertThat(nextPrompt.challenge.challengeId).isNotEqualTo(prompt.challenge.challengeId)
        assertThat(live.controllerOrNull.value).isNull()
        assertThat(connections.snapshotForSource(1)!!.locallyReserved).isEqualTo(1)
        live.answerConnectionPrompt(false)
        scheduler.runCurrent()
    }

    @Test fun decliningUnknownAllowanceNeverAllocatesAnEngineOrAdditionalReservation() = runTest(dispatcher) {
        source(1, null)
        val selected = channel(11, 1)
        val capture = recording(1, "existing-recording")
        val live = session()
        live.openChannel(selected.id)
        scheduler.runCurrent()
        val prompt = requireNotNull(live.connectionPrompt.value)

        live.answerConnectionPrompt(false)
        scheduler.runCurrent()

        assertThat(live.connectionPrompt.value).isNull()
        assertThat(live.controllerOrNull.value).isNull()
        assertThat(live.engineReady.value).isFalse()
        assertThat(connections.snapshotForSource(1)!!.locallyReserved).isEqualTo(1)
        assertThat(connections.reservationState(capture)).isEqualTo(ReservationState.ACTIVE)
        assertThat(connections.approve(prompt.challenge)).isNull()
        assertThat((live.state.value as PlayerController.State.Error).message).contains("canceled")
    }

    @Test fun knownAccountLimitDenialRetainsThePreviousPlayerAndItsReservation() = runTest(dispatcher) {
        source(1, 1)
        source(2, 1)
        val current = channel(11, 1)
        val unavailable = channel(22, 2)
        val capture = recording(2, "other-account-recording")
        val live = session()
        live.openChannel(current.id)
        scheduler.runCurrent()
        val previousPlayer = requireNotNull(live.controllerOrNull.value)

        live.openChannel(unavailable.id)
        scheduler.runCurrent()

        assertThat(live.connectionPrompt.value).isNull()
        assertThat(live.controllerOrNull.value).isSameInstanceAs(previousPlayer)
        assertThat(previousPlayer.isReleased).isFalse()
        assertThat(live.currentChannel.value?.id).isEqualTo(current.id)
        assertThat(connections.snapshotForSource(1)!!.locallyReserved).isEqualTo(1)
        assertThat(connections.snapshotForSource(2)!!.locallyReserved).isEqualTo(1)
        assertThat(connections.reservationState(capture)).isEqualTo(ReservationState.ACTIVE)
        assertThat((live.state.value as PlayerController.State.Error).message).contains("configured connection limit")
    }
}
