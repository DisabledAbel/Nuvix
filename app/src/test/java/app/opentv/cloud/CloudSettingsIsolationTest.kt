package app.opentv.cloud

import android.app.Application
import android.content.Context
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class CloudSettingsIsolationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var settings: CloudSettings
    @Before fun setup() {
        context.getSharedPreferences("nuvix_cloud_flags", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.noBackupFilesDir, "cloud_v1").deleteRecursively()
        settings = CloudSettings(context)
    }
    @Test fun existingEnabledUsersKeepOnlyOriginalCategoryAndNewInstallsRemainOff() {
        assertFalse(settings.enabled)
        assertEquals(AdvancedSyncCategory.DEFAULTS, settings.categories)
        settings.enabled = true
        val reopened = CloudSettings(context)
        assertTrue(reopened.enabled)
        assertEquals(setOf(AdvancedSyncCategory.FAVOURITES_PROGRESS), reopened.categories)
    }
    @Test fun categorySelectionsPersistAndCanAllBeDisabled() {
        settings.categories = setOf(AdvancedSyncCategory.APP_FAVOURITES, AdvancedSyncCategory.HOME_PREFERENCES)
        assertEquals(settings.categories, CloudSettings(context).categories)
        settings.categories = emptySet()
        assertTrue(CloudSettings(context).categories.isEmpty())
    }
    @Test fun signOutClearsEveryAccountBoundQueueWithoutDeletingLocalPreferences() {
        val directory = File(context.noBackupFilesDir, "cloud_v1")
        val accountFiles = listOf("session", "outbox", "acknowledged", "favourites", "advanced_journal")
        accountFiles.forEach { File(directory, it).writeText("synthetic-account-A-fixture") }
        File(directory, "advanced_journal.tmp").writeText("interrupted fixture")
        val launcher = context.getSharedPreferences("nuvix_launcher", Context.MODE_PRIVATE)
        launcher.edit().putString("favorite_packages", "[\"com.example.local\"]").commit()
        settings.enabled = true
        settings.categories = AdvancedSyncCategory.entries.toSet()
        settings.lastSynced = 1000
        settings.clearSession()
        assertFalse(settings.enabled)
        assertEquals(AdvancedSyncCategory.DEFAULTS, settings.categories)
        assertEquals(0L, settings.lastSynced)
        assertTrue(accountFiles.all { !File(directory, it).exists() })
        assertFalse(File(directory, "advanced_journal.tmp").exists())
        assertEquals("[\"com.example.local\"]", launcher.getString("favorite_packages", null))
    }
    @Test fun categoryNamesNotRecognizedByThisClientCannotEnableAnUnknownPayload() {
        context.getSharedPreferences("nuvix_cloud_flags", Context.MODE_PRIVATE).edit()
            .putStringSet("categories", setOf("PROVIDER_CREDENTIALS", "APP_FAVOURITES")).commit()
        assertEquals(setOf(AdvancedSyncCategory.APP_FAVOURITES), settings.categories)
    }
    @Test fun projectConfigurationRejectsInsecureOriginsAndPrivilegedKeysBeforeStorage() {
        for ((url, key) in listOf(
            "http://project.invalid" to "sb_publishable_this_is_test_only",
            "https://user:password@project.invalid" to "sb_publishable_this_is_test_only",
            "https://project.invalid/path" to "sb_publishable_this_is_test_only",
            "https://project.invalid" to "sb_secret_private_canary",
        )) assertTrue(runCatching { settings.configure(url, key) }.isFailure)
        assertNull(settings.config())
    }
    @Test fun malformedOwnerCannotBeSavedIntoTheEncryptedSession() {
        assertTrue(runCatching {
            settings.saveSession(CloudSession("not-an-owner", "test@example.invalid", "test", "test", 1000))
        }.isFailure)
        assertNull(settings.session())
        assertTrue(canonicalUuid(settings.deviceId))
    }
}
