/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.room.Room
import app.opentv.connections.AccountConnectionManager
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class RecordingWarningPlanTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: OpenTvDatabase
    private lateinit var graph: ServiceLocator.Graph
    private lateinit var connections: AccountConnectionManager
    private var previousGraph: Any? = null
    private var previousConnections: Any? = null

    @Before fun setup() = runBlocking {
        context.getSharedPreferences("nuvix_connection_settings", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).allowMainThreadQueries().build()
        graph = ServiceLocator.Graph(context)
        ServiceLocator.Graph::class.java.getDeclaredField("database\$delegate").apply { isAccessible = true }.set(graph, lazyOf(db))
        previousGraph = replace(ServiceLocator::class.java, graph)
        connections = AccountConnectionManager.createForTest(context)
        previousConnections = replace(AccountConnectionManager::class.java, connections)
        db.sources().insert(Source(id = 1, name = "Fake provider", kind = SourceKind.M3U, url = "https://example.invalid/playlist"))
        connections.registerSource(db.sources().byId(1)!!)
        connections.setMaximumConnections(1, 1)
        graph.settings.setRecordConflictLeadMinutes(2)
    }

    @After fun close() {
        replace(ServiceLocator::class.java, previousGraph)
        replace(AccountConnectionManager::class.java, previousConnections)
        db.close()
    }

    @Test fun warningsUseEffectivePlanAndCancellationMovesPeerWarningWithoutChangingRequestedWindow() = runBlocking {
        val now = System.currentTimeMillis()
        val first = booking(1, now, 600_000, 1_200_000)
        val second = booking(2, now, 1_200_000, 1_800_000)
        db.recordings().insert(first); db.recordings().insert(second)
        RecordingScheduler.set(context, second.id, second.scheduledStartMillis)
        RecordingScheduler.rearmWarnings(context)
        assertEquals(now + 1_080_000, warnings()[second.id]) // Effective start 20 min, warning 18 min.
        assertEquals(second.scheduledStartMillis, db.recordings().byId(second.id)!!.scheduledStartMillis)
        db.recordings().cancelScheduled(first.id)
        RecordingScheduler.rearmWarnings(context)
        assertFalse(warnings().containsKey(first.id))
        assertEquals(now + 960_000, warnings()[second.id]) // Requested start 18 min, warning 16 min.
        val capture = alarms().single { shadowOf(it.operation).savedIntent.action == RecordingScheduler.ACTION_START }
        assertEquals(second.scheduledStartMillis, capture.triggerAtTime)
    }

    @Test fun changedAccountLimitReplansWarningBeforeAnyDeadlineAlarm() = runBlocking {
        val now = System.currentTimeMillis()
        db.recordings().insert(booking(1, now, 600_000, 1_200_000))
        db.recordings().insert(booking(2, now, 1_200_000, 1_800_000))
        RecordingScheduler.rearmWarnings(context)
        assertEquals(now + 1_080_000, warnings()[2L])
        connections.setMaximumConnections(1, 2)
        RecordingScheduler.rearmWarnings(context)
        assertEquals(now + 960_000, warnings()[2L])
    }

    private fun alarms() = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).scheduledAlarms
    private fun warnings() = alarms().filter { shadowOf(it.operation).savedIntent.action == RecordingScheduler.ACTION_WARN }
        .associate { shadowOf(it.operation).savedIntent.getLongExtra("recordingId", -1) to it.triggerAtTime }
    private fun booking(id: Long, now: Long, start: Long, end: Long) = Recording(id = id, channelId = id, sourceId = 1,
        channelName = "Channel $id", title = "Programme $id", filePath = "/fake-$id.ts", streamUrl = "https://example.invalid/live/$id",
        scheduledStartMillis = now + start - 120_000, scheduledEndMillis = now + end,
        programmeStartMillis = now + start, programmeEndMillis = now + end, status = RecordingStatus.SCHEDULED)
    private fun replace(type: Class<*>, value: Any?): Any? = type.getDeclaredField("instance").let {
        it.isAccessible = true; val old = it.get(null); it.set(null, value); old
    }
}
