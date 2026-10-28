/*
 * This file is part of Nuvix, an independent modified version of OpenTV.
 * Copyright (C) 2026 The Nuvix Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.security

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.opentv.data.db.OpenTvDatabase
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Checks Room's SQL migration chain; SQLCipher conversion itself requires an Android device. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class RoomMigrationPreservationTest {
    /** Checks: old database upgrades preserve local credentials favorites guide override and resume. */
    @Test
    fun `old database upgrades preserve local credentials favorites guide override and resume`() {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-preservation.db"
        context.deleteDatabase(name)
        val versionTwo = schemaVersionTwo()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val entities = versionTwo.getJSONArray("entities")
                        for (index in 0 until entities.length()) {
                            val entity = entities.getJSONObject(index)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = entity.getJSONArray("indices")
                            for (position in 0 until indices.length()) {
                                db.execSQL(indices.getJSONObject(position).getString("createSql").replace("\${TABLE_NAME}", table))
                            }
                        }
                        val setup = versionTwo.getJSONArray("setupQueries")
                        for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        helper.writableDatabase.apply {
            execSQL(
                """INSERT INTO sources
                    (id,name,kind,url,username,password,epgUrl,userAgent,enabled,lastCatalogSyncMillis)
                    VALUES (7,'Local source','XTREAM',?,'local-user','private-password',NULL,'Nuvix',1,100)""",
                arrayOf("https://provider.example.test"),
            )
            execSQL(
                """INSERT INTO channels
                    (id,sourceId,streamId,name,displayName,groupKey,qualityRank,qualityLabel,categoryId,
                    logoUrl,epgChannelId,matchedEpgId,epgOverrideId,number,streamUrl,favourite,hidden,sortIndex,lastSeenMillis)
                    VALUES (9,7,'100','News','News','news',0,'',NULL,NULL,NULL,NULL,'manual-guide-id',12,?,1,0,4,100)""",
                arrayOf("https://provider.example.test/live/local-user/private-password/100.m3u8"),
            )
            execSQL("INSERT INTO playback_positions VALUES ('movie:7:42',18000,90000,100)")
        }
        helper.close()

        // Use the framework factory here so this exercises real Room migrations and schema
        // validation independently of SQLCipher's Android-only native library.
        val upgraded = Room.databaseBuilder(context, OpenTvDatabase::class.java, name)
            .addMigrations(*existingMigrations())
            .allowMainThreadQueries()
            .build()
        try {
            val database = upgraded.openHelper.writableDatabase
            assertThat(database.version).isEqualTo(18)
            database.query("SELECT username,password,liveFormat,macAddress FROM sources WHERE id=7").use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo("local-user")
                assertThat(it.getString(1)).isEqualTo("private-password")
                assertThat(it.getString(2)).isEqualTo("HLS")
                assertThat(it.isNull(3)).isTrue()
            }
            database.query("SELECT favourite,epgOverrideId,streamUrl FROM channels WHERE id=9").use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getInt(0)).isEqualTo(1)
                assertThat(it.getString(1)).isEqualTo("manual-guide-id")
                assertThat(it.getString(2)).isEqualTo("https://provider.example.test/live/local-user/private-password/100.m3u8")
            }
            database.query("SELECT profileId,positionMillis,durationMillis FROM playback_positions WHERE mediaKey='movie:7:42'").use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getLong(0)).isEqualTo(1L)
                assertThat(it.getLong(1)).isEqualTo(18000L)
                assertThat(it.getLong(2)).isEqualTo(90000L)
            }
            database.query("SELECT name FROM profiles WHERE id=1").use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo("Me")
            }
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    /** Loads the exported version-two Room schema used to seed migration preservation tests. */
    private fun schemaVersionTwo(): JSONObject {
        val relative = "schemas/app.opentv.data.db.OpenTvDatabase/2.json"
        val file = listOf(File(relative), File("app", relative)).firstOrNull { it.isFile }
            ?: error("Exported Room v2 schema not found; run tests from the app or repository directory")
        return JSONObject(file.readText()).getJSONObject("database")
    }

    /** Collects the production migration chain for schema-preservation assertions. */
    private fun existingMigrations(): Array<Migration> = OpenTvDatabase::class.java.declaredFields
        .filter { Migration::class.java.isAssignableFrom(it.type) }
        .map { field -> field.isAccessible = true; field.get(null) as Migration }
        .sortedBy { it.startVersion }
        .toTypedArray()
}
