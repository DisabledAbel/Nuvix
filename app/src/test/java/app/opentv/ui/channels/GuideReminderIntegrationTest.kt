/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.channels

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.room.Room
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.Reminder
import app.opentv.data.repo.ReminderRepository
import app.opentv.reminders.ReminderAlarmReceiver
import app.opentv.reminders.ReminderScheduler
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
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class GuideReminderIntegrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: OpenTvDatabase
    private lateinit var repository: ReminderRepository
    private val now = 1_800_000_000_000L
    private val channel = Channel(id = 11, sourceId = 1, streamId = "11", name = "Original channel",
        customName = "My channel", categoryId = null, logoUrl = null, epgChannelId = "channel", number = 11,
        streamUrl = "https://example.invalid/live/11.ts")
    private fun programme(start: Long = now + 60_000, end: Long = now + 120_000) =
        Programme(feedId = 1, epgChannelId = "channel", title = "Upcoming programme",
            startUtcMillis = start, endUtcMillis = end)
    private val alarms get() = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).scheduledAlarms
    private fun alarmReminderIds() = alarms.map { ReminderScheduler.reminderIdFrom(shadowOf(it.operation).savedIntent) }

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).allowMainThreadQueries().build()
        repository = ReminderRepository(database.reminders())
    }

    @After fun close() { database.close() }

    @Test fun `guide reminder persists metadata and arms one immutable broadcast without retuning`() = runBlocking {
        val future = programme()
        assertTrue(setReminder(repository, context, channel, future, autoTune = true, nowMillis = now))
        val stored = repository.forProgramme(channel.id, future.startUtcMillis)!!
        assertEquals("My channel", stored.channelName)
        assertEquals(future.title, stored.title)
        assertEquals(future.endUtcMillis, stored.endUtcMillis)
        assertTrue(stored.autoTune)
        assertFalse(stored.fired)
        assertEquals(now, stored.createdAtMillis)
        assertEquals(listOf(stored.id), alarmReminderIds())
        val alarm = alarms.single()
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(future.startUtcMillis, alarm.triggerAtTime)
        val pending = shadowOf(alarm.operation)
        assertTrue(pending.isBroadcast)
        assertTrue(pending.isImmutable)
        assertEquals(ReminderScheduler.ACTION_REMIND, pending.savedIntent.action)
        assertEquals(ReminderAlarmReceiver::class.java.name, pending.savedIntent.component!!.className)
        assertNull(shadowOf(context as Application).nextStartedActivity)
        assertTrue(setReminder(repository, context, channel, future, autoTune = true, nowMillis = now))
        assertEquals(listOf(stored.id), alarmReminderIds())
        assertFalse(setReminder(repository, context, channel, future, autoTune = false, nowMillis = now))
        assertTrue(repository.forProgramme(channel.id, future.startUtcMillis)!!.autoTune)
    }

    @Test fun `canceling one channel slot preserves simultaneous reminder on another channel`() = runBlocking {
        val future = programme()
        setReminder(repository, context, channel, future, autoTune = false, nowMillis = now)
        setReminder(repository, context, channel.copy(id = 22), future, autoTune = false, nowMillis = now)
        val first = repository.forProgramme(channel.id, future.startUtcMillis)!!
        val second = repository.forProgramme(22, future.startUtcMillis)!!
        assertEquals(setOf(first.id, second.id), alarmReminderIds().toSet())
        ReminderScheduler.cancel(context, first.id)
        repository.delete(first.id)
        assertNull(repository.forProgramme(channel.id, future.startUtcMillis))
        assertEquals(listOf(second.id), alarmReminderIds())
        assertEquals(second, ReminderRepository(database.reminders()).forProgramme(22, future.startUtcMillis))
    }

    @Test fun `past current and malformed programmes cannot create a row or alarm`() = runBlocking {
        for (invalid in listOf(programme(now - 1, now + 1000), programme(now, now + 1000),
            programme(now + 1000, now + 1000), programme(now + 1000, now), programme(-1, now))) {
            assertFalse(setReminder(repository, context, channel, invalid, autoTune = false, nowMillis = now))
            assertNull(repository.forProgramme(channel.id, invalid.startUtcMillis))
        }
        assertTrue(alarms.isEmpty())
    }

    @Test fun `reboot rearm query retains only future unfired reminders`() = runBlocking {
        val base = Reminder(channelId = channel.id, channelName = channel.name, title = "Program",
            startUtcMillis = now + 60_000, endUtcMillis = now + 120_000)
        val eligible = repository.insert(base)
        repository.insert(base.copy(startUtcMillis = now, endUtcMillis = now + 60_000))
        val fired = repository.insert(base.copy(startUtcMillis = now + 120_000, endUtcMillis = now + 180_000))
        repository.markFired(fired)
        val reloaded = ReminderRepository(database.reminders())
        reloaded.upcoming(now).forEach { ReminderScheduler.set(context, it.id, it.startUtcMillis) }
        assertEquals(listOf(eligible), alarmReminderIds())
        assertTrue(reloaded.byId(fired)!!.fired)
    }
}
