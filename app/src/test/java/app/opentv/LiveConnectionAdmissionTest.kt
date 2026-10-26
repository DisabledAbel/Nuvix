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

    /** Creates isolated fixtures before each test. */
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

    /** Releases test resources and restores shared state after each test. */
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

    /** Replaces a lazy application-graph dependency with an isolated test fixture. */
    private fun setGraphDelegate(name: String, value: Any) {
        ServiceLocator.Graph::class.java.getDeclaredField("${name}\$delegate")
            .apply { isAccessible = true }.set(graph, lazyOf(value))
    }

    /** Replaces a process singleton for the test and returns its previous value for restoration. */
    private fun replaceSingleton(type: Class<*>, value: Any?): Any? =
        type.getDeclaredField("instance").run {
            isAccessible = true
            get(null).also { set(null, value) }
        }

    /** Provides a synthetic provider configuration for the test's requested source identity. */
    private suspend fun source(id: Long, maximum: Int?, kind: SourceKind = SourceKind.M3U): Source {
        val source = Source(id = id, name = "Offline account $id", kind = kind,
            url = "https://account-$id.example.invalid/fixture.m3u",
            username = if (kind == SourceKind.XTREAM) "synthetic" else null,
            password = if (kind == SourceKind.XTREAM) "synthetic" else null)
        database.sources().insert(source)
        connections.registerSource(source)
        connections.setMaximumConnections(id, maximum)
        return source
    }

    /** Provides a synthetic channel with the identity and state required by this test. */
    private suspend fun channel(id: Long, sourceId: Long): Channel {
        val channel = Channel(id = id, sourceId = sourceId, streamId = "$id", name = "Channel $id",
            categoryId = null, logoUrl = null, epgChannelId = null, number = id.toInt(),
            streamUrl = "https://account-$sourceId.example.invalid/live/$id.ts")
        database.channels().upsertAll(listOf(channel))
        return channel
    }

    /** Creates a retained live session using the test application. */
    private fun session(): LivePlaybackSession = LivePlaybackSession(app).also {
        store.put("live-admission-${sessionCount++}", it)
        // Admission remains real. Suspension applies before play(), avoiding prepare() and I/O.
        it.setForeground(false)
    }

    /** Provides the recording fixture used by the current scheduling or capture scenario. */
    private fun recording(sourceId: Long, id: String): ReservationToken {
        val token = (connections.reserve(ConnectionRequest(id, 1, sourceId, ConnectionPurpose.RECORDING))
            as ReservationResult.Reserved).token
        connections.markActive(token)
        externalReservations += token
        return token
    }

    @Test fun sameProviderReplacementClosesOldPlayerAndKeepsExactlyOneOwnedReservation() = runTest(dispatcher) {
        for ((index, maximum) in listOf(1, 3, null).withIndex()) {
            val sourceId = index.toLong() + 1
            source(sourceId, maximum, SourceKind.XTREAM)
            val current = channel(sourceId * 10 + 1, sourceId)
            val next = channel(sourceId * 10 + 2, sourceId)
            val live = session()
            live.openChannel(current.id); scheduler.runCurrent()
            val oldPlayer = requireNotNull(live.controllerOrNull.value)
            val oldToken = connections.snapshotForSource(sourceId)!!.reservations.single().token
            live.openChannel(next.id); scheduler.runCurrent()
            assertThat(oldPlayer.isReleased).isTrue()
            assertThat(live.requestedChannelId.value).isEqualTo(next.id)
            assertThat(live.currentChannel.value?.id).isEqualTo(next.id)
            assertThat(live.connectionPrompt.value).isNull()
            val newest = connections.snapshotForSource(sourceId)!!.reservations.single().token
            assertThat(newest.generation).isGreaterThan(oldToken.generation)
            assertThat(connections.release(oldToken)).isFalse()
            assertThat(connections.reservationState(newest)).isEqualTo(ReservationState.PENDING)
            live.stop()
            assertThat(connections.snapshotForSource(sourceId)!!.locallyReserved).isEqualTo(0)
        }
    }

    @Test fun sameProviderReplacementPreservesDvrReservationAndUnknownApprovalRequirement() = runTest(dispatcher) {
        source(1, 2)
        val current = channel(11, 1)
        val next = channel(12, 1)
        val capture = recording(1, "protected-capture")
        val live = session()
        live.openChannel(current.id); scheduler.runCurrent()
        live.openChannel(next.id); scheduler.runCurrent()
        assertThat(connections.reservationState(capture)).isEqualTo(ReservationState.ACTIVE)
        assertThat(connections.snapshotForSource(1)!!.locallyReserved).isEqualTo(2)
        connections.setMaximumConnections(1, null)
        live.openChannel(current.id); scheduler.runCurrent()
        val prompt = requireNotNull(live.connectionPrompt.value)
        assertThat(live.controllerOrNull.value).isNull()
        assertThat(connections.snapshotForSource(1)!!.reservations.single().token).isEqualTo(capture)
        // A newer selection cancels the obsolete approval; the older challenge cannot admit it.
        live.openChannel(next.id); scheduler.runCurrent()
        assertThat(connections.approve(prompt.challenge)).isNull()
        live.answerConnectionPrompt(false); scheduler.runCurrent()
        assertThat(connections.reservationState(capture)).isEqualTo(ReservationState.ACTIVE)
    }

    /** Checks: repeated Manual Recording Requests Before Service Delivery Reuse One Durable Booking. */
    @Test fun repeatedManualRecordingRequestsBeforeServiceDeliveryReuseOneDurableBooking() = runTest(dispatcher) {
        source(1, 2)
        val selected = channel(11, 1)
        val first = graph.recordingEngine.startChannel(selected)
        val second = graph.recordingEngine.startChannel(selected)
        assertThat(second).isEqualTo(first)
        assertThat(database.recordings().all()).hasSize(1)
        assertThat(connections.snapshotForSource(1)!!.locallyReserved).isEqualTo(0)
    }

    /** Checks: independent Accounts Keep Separate Live Owners And Recording Reclaims Only Its Own Account. */
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

    /** Checks: unknown Account Approval Adopts Only The Prompted Request And Is Not Reused. */
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

    /** Checks: declining Unknown Allowance Never Allocates An Engine Or Additional Reservation. */
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

    /** Checks: known Account Limit Denial Retains The Previous Player And Its Reservation. */
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
