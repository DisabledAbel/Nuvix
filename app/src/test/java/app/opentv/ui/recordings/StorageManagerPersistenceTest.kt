/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.recordings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.recording.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class StorageManagerPersistenceTest {
    @Test fun cleanupToggleReadsCurrentStoredFieldsAndCommitsOffTheCallingThread() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("dvr_storage", Context.MODE_PRIVATE).edit().clear().commit()
        val store = DvrStorageStore(context)
        // The summary was rendered with defaults, then the user saved a different policy.
        val summaryPolicy = store.policy("opaque")
        val current = summaryPolicy.copy(warnings = false, warningBytes = 4 * DvrStoragePolicy.GIB,
            targetBytes = 8 * DvrStoragePolicy.GIB, retentionDays = 60)
        assertTrue(store.save("opaque", current))
        val caller = Thread.currentThread()
        assertTrue(updateStoredPolicy(store, "opaque") {
            assertNotSame(caller, Thread.currentThread())
            it.copy(cleanupEnabled = true)
        })
        assertEquals(current.copy(cleanupEnabled = true), store.policy("opaque"))
        assertTrue(updateStoredPolicy(store, "opaque") { it.copy(cleanupEnabled = false) })
        assertEquals(current, store.policy("opaque"))
    }
}
