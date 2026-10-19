/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.opentv.data.db.OpenTvDatabase
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Regression tests for recording migration. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class RecordingMigrationTest {
    /** Checks: v13 migrates with Room validation and preserves old padded booking and profile. */
    @Test fun `v13 migrates with Room validation and preserves old padded booking and profile`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "recording-v13-migration.db"
        context.deleteDatabase(name)
        val relative = "schemas/app.opentv.data.db.OpenTvDatabase/13.json"
        val schemaFile = sequenceOf(File(relative), File("app/$relative")).first { it.isFile }
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(13) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (index in 0 until entities.length()) {
                        val entity = entities.getJSONObject(index)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.getJSONArray("indices")
                        for (i in 0 until indices.length()) db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
                    db.execSQL("INSERT INTO profiles (id,name,createdAtMillis) VALUES (4,'My profile',123)")
                    db.execSQL("""INSERT INTO sources (id,name,kind,url,username,password,userAgent,liveFormat,enabled,lastCatalogSyncMillis)
                        VALUES (9,'Existing provider','XTREAM','https://example.invalid','private-user','private-password','Nuvix','HLS',1,123)""")
                    db.execSQL("""INSERT INTO recordings (id,channelId,sourceId,channelName,title,filePath,streamUrl,userAgent,
                        scheduledStartMillis,scheduledEndMillis,startedAtMillis,endedAtMillis,status,sizeBytes,profileId)
                        VALUES (7,8,9,'News','Existing booking','/recordings/keep.ts','https://example.invalid/live.ts','Nuvix',
                        1000,2000,0,0,'SCHEDULED',99,4)""")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase
        helper.close()
        val migrated = Room.databaseBuilder(context, OpenTvDatabase::class.java, name)
            .addMigrations(OpenTvDatabase.MIGRATION_13_14, OpenTvDatabase.MIGRATION_14_15).allowMainThreadQueries().build()
        try {
            val recording = migrated.recordings().byId(7)!!
            assertThat(recording.filePath).isEqualTo("/recordings/keep.ts")
            assertThat(recording.scheduledStartMillis).isEqualTo(1000)
            assertThat(recording.scheduledEndMillis).isEqualTo(2000)
            assertThat(recording.programmeStartMillis).isEqualTo(0)
            assertThat(recording.sizeBytes).isEqualTo(99)
            assertThat(recording.profileId).isEqualTo(4)
            assertThat(migrated.profiles().all().single().name).isEqualTo("My profile")
            val source = migrated.sources().byId(9)!!
            assertThat(source.username).isEqualTo("private-user")
            assertThat(source.password).isEqualTo("private-password")
            assertThat(source.url).isEqualTo("https://example.invalid")
            assertThat(migrated.recordingEvents().forRecording(7)).isEmpty()
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
