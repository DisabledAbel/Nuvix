/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.stalker

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.StalkerIdentity
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
class StalkerMigrationTest {
    @Test fun version16ProviderRetainsMacCredentialsAndAutomaticDefaults() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "stalker-migration.db"
        context.deleteDatabase(name)
        val relative = "schemas/app.opentv.data.db.OpenTvDatabase/16.json"
        val schema = JSONObject(sequenceOf(File(relative), File("app/$relative")).first { it.isFile }.readText()).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(16) {
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
                    db.execSQL("""INSERT INTO sources (id,name,kind,url,username,password,macAddress,epgUrl,userAgent,liveFormat,enabled,lastCatalogSyncMillis)
                        VALUES (7,'Legacy','STALKER','https://portal.example.invalid/c','keep-user','keep-password',
                        '00:1a:79:aa:bb:cc','https://guide.example.invalid','Legacy Agent','HLS',1,123)""")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase; helper.close()
        val migrated = Room.databaseBuilder(context, OpenTvDatabase::class.java, name)
            .addMigrations(OpenTvDatabase.MIGRATION_16_17, OpenTvDatabase.MIGRATION_17_18).allowMainThreadQueries().build()
        try {
            val source = migrated.sources().byId(7)!!
            assertEquals("00:1a:79:aa:bb:cc", source.macAddress)
            assertEquals("keep-password", source.password)
            assertEquals("keep-user", source.username)
            assertEquals("Legacy Agent", source.userAgent)
            assertEquals(123L, source.lastCatalogSyncMillis)
            assertNull(source.stalkerSerialNumber); assertNull(source.stalkerDeviceId1)
            assertNull(source.stalkerDeviceId2); assertNull(source.stalkerSignature); assertNull(source.stalkerAccountKey)
            assertEquals(0L, source.stalkerRevision)
            assertEquals("64D08FE7B8030", StalkerIdentity.resolve(source).sn)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
