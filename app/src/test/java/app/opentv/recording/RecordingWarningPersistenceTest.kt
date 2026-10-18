/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import android.app.Application
import android.app.AlarmManager
import android.content.Context
import app.opentv.core.AppSettings
import app.opentv.connections.*
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
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
class RecordingWarningPersistenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() { context.getSharedPreferences("recording_conflict_warnings", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun keepWatchingSurvivesRestartButNewScheduleDoesNotInheritDismissal() {
        RecordingWarningStore(context).dismiss("4294967297:2:100:200:2:5")
        assertTrue(RecordingWarningStore(context).dismissed("4294967297:2:100:200:2:5"))
        assertFalse(RecordingWarningStore(context).dismissed("4294967297:2:150:200:2:5"))
        assertFalse(RecordingWarningStore(context).dismissed("1:2:100:200:2:5"))
    }
    @Test fun duplicateAlarmNotificationClaimPersistsAndCanceledRowsArePruned() {
        val store = RecordingWarningStore(context)
        store.markNotified("1:2:100:200:2:5")
        assertTrue(RecordingWarningStore(context).notified("1:2:100:200:2:5"))
        store.prune(emptySet())
        assertFalse(store.notified("1:2:100:200:2:5"))
    }
    @Test fun warningSettingDefaultsToTwoAndPersistsWithinBounds() {
        val prefs = context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE)
        prefs.edit().remove("record_conflict_lead_minutes").commit()
        fun reopen() = AppSettings::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        val settings = reopen()
        assertEquals(2, settings.recordConflictLeadMinutes.value)
        settings.setRecordConflictLeadMinutes(7)
        assertEquals(7, reopen().recordConflictLeadMinutes.value)
        settings.setRecordConflictLeadMinutes(0); assertEquals(1, reopen().recordConflictLeadMinutes.value)
        settings.setRecordConflictLeadMinutes(100); assertEquals(10, reopen().recordConflictLeadMinutes.value)
    }
    @Test fun paddedWarningAlarmsUseFullLongIdsRearmAndCancelIndependently() {
        AppSettings.get(context).setRecordConflictLeadMinutes(2)
        val alarm = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        val start = System.currentTimeMillis() + 600_000
        RecordingScheduler.setWarning(context, 1, start)
        RecordingScheduler.setWarning(context, 4294967297, start)
        assertEquals(2, alarm.scheduledAlarms.size)
        assertTrue(alarm.scheduledAlarms.all { it.triggerAtTime == start - 120_000 })
        RecordingScheduler.setWarning(context, 1, start + 300_000)
        assertEquals(2, alarm.scheduledAlarms.size)
        RecordingScheduler.cancel(context, 1)
        assertEquals(1, alarm.scheduledAlarms.size)
        RecordingScheduler.cancel(context, 4294967297)
        assertTrue(alarm.scheduledAlarms.isEmpty())
    }
    @Test fun backgroundNotificationIsIdempotentAndNeverStartsAnActivity() {
        val ledger = ConnectionAllocator()
        ledger.registerSource(1, "opaque", AccountConnectionPolicy(1, false))
        ledger.reserve(ConnectionRequest("primary", 1, 1, ConnectionPurpose.MULTIVIEW))
        val now = System.currentTimeMillis()
        val rec = Recording(id = 1, channelId = 9, sourceId = 1, channelName = "Sports", title = "Game",
            streamUrl = "https://example.invalid/private-token", filePath = "/fake.ts", status = RecordingStatus.SCHEDULED,
            scheduledStartMillis = now + 60_000, scheduledEndMillis = now + 600_000)
        val conflict = MultiViewRecordingConflictPolicy.detect(listOf(rec), ledger.snapshots.value, now).single()
        assertTrue(RecordingConflictNotifications.show(context, conflict))
        assertFalse(RecordingConflictNotifications.show(context, conflict))
        assertTrue(RecordingWarningStore(context).notified(conflict.key))
        assertNull(shadowOf(context as Application).nextStartedActivity)
        RecordingConflictNotifications.cancel(context, rec.id)
    }
}
