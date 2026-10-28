/* Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.*
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
class ManagerMigrationTest {
    @Test fun revisionMigrationRetainsCatalogAndDetectsChangesWithoutLeakingCredentials() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "manager-migration.db"
        context.deleteDatabase(name)
        val relative = "schemas/app.opentv.data.db.OpenTvDatabase/17.json"
        val schema = JSONObject(sequenceOf(File(relative), File("app/$relative")).first { it.isFile }.readText()).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(17) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                    db.execSQL("""INSERT INTO sources (id,name,kind,url,username,password,macAddress,epgUrl,userAgent,liveFormat,enabled,lastCatalogSyncMillis)
                        VALUES (7,'Legacy','M3U','https://provider.invalid/private','keep-user','keep-password',NULL,NULL,'Agent','HLS',1,123)""")
                    db.execSQL("""INSERT INTO channels (id,sourceId,streamId,name,displayName,groupKey,qualityRank,qualityLabel,categoryId,
                        logoUrl,epgChannelId,matchedEpgId,epgOverrideId,number,streamUrl,favourite,hidden,sortIndex,customName,lastSeenMillis)
                        VALUES (9,7,'stream','Original','Display','group',0,'','cat',NULL,NULL,NULL,'guide',1,'https://stream.invalid/private',1,1,4,'Custom',123)""")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase; helper.close()
        val migrated = Room.databaseBuilder(context, OpenTvDatabase::class.java, name)
            .addMigrations(OpenTvDatabase.MIGRATION_17_18).allowMainThreadQueries().build()
        try {
            val source = migrated.sources().byId(7)!!
            assertEquals("keep-password", source.password); assertEquals(0L, source.managerRevision)
            val row = migrated.channels().byId(9)!!
            assertTrue(row.favourite); assertTrue(row.hidden); assertEquals("Custom", row.customName)
            assertEquals("guide", row.epgOverrideId); assertEquals(4, row.sortIndex)
            migrated.channels().setHidden(9, false)
            assertEquals(0L, migrated.channels().managerRevision(7))
            migrated.channels().setCustomName(9, "Edited")
            assertTrue(migrated.channels().managerRevision(7)!! > 0)
            assertEquals("https://stream.invalid/private", migrated.channels().byId(9)!!.streamUrl)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
