/* Nuvix modifications, Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.security

import android.app.Application
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.opentv.data.db.EncryptedDatabaseFactory
import com.google.common.truth.Truth.assertThat
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Verifies the actual 4.12 Java helper binding; does not open SQLCipher's native database. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SqlCipherRetentionBindingTest {
    /** Checks: native corruption handler retains database and surfaces original failure. */
    @Test
    fun `native corruption handler retains database and surfaces original failure`() {
        val context = RuntimeEnvironment.getApplication()
        val name = "corruption-handler-retention.db"
        val original = context.getDatabasePath(name).apply {
            parentFile!!.mkdirs()
            writeText("existing database recovery bytes")
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(11) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        val helper = EncryptedDatabaseFactory.retainingHelper(configuration, ByteArray(32) { 7 }, true)
        try {
            // Reflection checks the pinned library's real native-open error-handler field. Testing
            // only Room's callback would miss SQLCipher's factory silently passing a null handler.
            val field = SQLiteOpenHelper::class.java.getDeclaredField("mErrorHandler").apply { isAccessible = true }
            val handler = field.get(helper) as DatabaseErrorHandler
            assertThat(handler.javaClass.name).doesNotContain("DefaultDatabaseErrorHandler")
            val corruption = SQLiteDatabaseCorruptException("simulated corruption")
            val failure = assertThrows(IllegalStateException::class.java) {
                handler.onCorruption(null, corruption)
            }
            assertThat(failure).hasMessageThat().contains("Existing app data has been retained")
            assertThat(failure.cause).isSameInstanceAs(corruption)
            assertThat(original.readText()).isEqualTo("existing database recovery bytes")
        } finally {
            helper.close()
            original.delete()
        }
    }
}
