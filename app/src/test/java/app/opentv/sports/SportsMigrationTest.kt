/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.sports

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.opentv.data.db.OpenTvDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SportsMigrationTest {
    @Test fun existingV12RemindersSurviveWithNullableGameKeysAndUniqueNewGameReminders(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "sports-migration.db"
        context.deleteDatabase(name)
        val relative = "schemas/app.opentv.data.db.OpenTvDatabase/12.json"
        val schema = JSONObject(listOf(File(relative), File("app", relative)).first { it.isFile }.readText()).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(12) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (index in 0 until entities.length()) {
                        val entity = entities.getJSONObject(index)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.getJSONArray("indices")
                        for (i in 0 until indices.length()) db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    val queries = schema.getJSONArray("setupQueries")
                    for (index in 0 until queries.length()) db.execSQL(queries.getString(index))
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        for (id in 1..2) helper.writableDatabase.execSQL("""INSERT INTO reminders
            (id,channelId,channelName,logoUrl,title,startUtcMillis,endUtcMillis,autoTune,createdAtMillis,fired)
            VALUES ($id,$id,'Existing channel',NULL,'Existing reminder',100000,200000,1,100,0)""")
        helper.close()
        val upgraded = Room.databaseBuilder(context, OpenTvDatabase::class.java, name)
            .addMigrations(OpenTvDatabase.MIGRATION_12_13, OpenTvDatabase.MIGRATION_13_14)
            .allowMainThreadQueries().build()
        try {
            val retained = upgraded.reminders().observeAll().first()
            assertEquals(2, retained.size)
            assertTrue(retained.all { it.sportsEventId == null && it.autoTune && !it.fired && it.title == "Existing reminder" })
            val sports = retained.first().copy(id = 0, sportsEventId = "fixture-game")
            upgraded.reminders().insert(sports)
            try { upgraded.reminders().insert(sports); fail("Duplicate game identity was inserted") }
            catch (expected: android.database.sqlite.SQLiteConstraintException) { assertTrue(expected.message.orEmpty().contains("sportsEventId")) }
            assertEquals(3, upgraded.reminders().observeAll().first().size)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
