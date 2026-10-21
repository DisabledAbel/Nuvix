/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.opentv.data.db.OpenTvDatabase
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
class RecordingLateStartMigrationTest {
    @Test fun version15UpgradePreservesProtectedLockedExtendedMediaSeriesAndHistory() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "late-start-v15.db"
        context.deleteDatabase(name)
        val relative = "schemas/app.opentv.data.db.OpenTvDatabase/15.json"
        val schema = JSONObject(sequenceOf(File(relative), File("app/$relative")).first { it.isFile }.readText()).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(15) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.getJSONArray("indices")
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                    db.execSQL("INSERT INTO series_rules VALUES (3,8,'News','news','News',123,1)")
                    db.execSQL("""INSERT INTO recordings (id,channelId,sourceId,channelName,title,filePath,streamUrl,userAgent,
                        scheduledStartMillis,scheduledEndMillis,startedAtMillis,endedAtMillis,status,sizeBytes,seriesRuleId,
                        programmeStartMillis,programmeEndMillis,protectedRecording,lockPadding,originalEndMillis,durationRevision,
                        manualExtensionMillis,durationModifiedAtMillis)
                        VALUES (7,8,9,'News','Existing media','/keep.ts','https://example.invalid/private','Nuvix',
                        1000,900000,1000,800000,'PARTIAL',564,3,1000,600000,1,1,700000,4,200000,12345)""")
                    db.execSQL("INSERT INTO recording_events VALUES (1,7,123,'PARTIAL','Keep history')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase; helper.close()
        val migrated = Room.databaseBuilder(context, OpenTvDatabase::class.java, name)
            .addMigrations(OpenTvDatabase.MIGRATION_15_16).allowMainThreadQueries().build()
        try {
            val r = migrated.recordings().byId(7)!!
            assertEquals("/keep.ts", r.filePath); assertEquals(564, r.sizeBytes)
            assertTrue(r.protectedRecording && r.lockPadding)
            assertEquals(4, r.durationRevision); assertEquals(900000, r.scheduledEndMillis)
            assertEquals(700000, r.originalEndMillis); assertEquals(200000, r.manualExtensionMillis)
            assertEquals("INHERIT", r.lateStartPolicy); assertEquals(10, r.minimumRemainingMinutes)
            assertEquals("INHERIT", migrated.seriesRules().byId(3)!!.lateStartPolicy)
            assertEquals("Keep history", migrated.recordingEvents().forRecording(7).single().message)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
