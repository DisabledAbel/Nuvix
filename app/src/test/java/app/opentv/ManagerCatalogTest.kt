/* Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv

import android.app.Application
import androidx.room.Room
import app.opentv.data.db.ManagerConflict
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real SQLite queries, transactions, sync setters and revision triggers; synthetic catalog only. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ManagerCatalogTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), OpenTvDatabase::class.java)
        .addCallback(OpenTvDatabase.MANAGER_CALLBACK).allowMainThreadQueries().build()
    private val dao = db.channels()
    private val source = Source(id = 1, name = "Provider <untrusted>", kind = SourceKind.M3U,
        url = "https://provider.invalid/secret-playlist", password = "secret-password")
    private fun channel(id: Long, sourceId: Long = 1) = Channel(id = id, sourceId = sourceId,
        streamId = "stream-$id", name = "Original $id", displayName = "Duplicate", categoryId = "category",
        logoUrl = null, epgChannelId = "guide-$id", number = null, streamUrl = "https://stream.invalid/secret/$id")
    private suspend fun seed(count: Int = 4) {
        db.sources().insert(source)
        (1..count).chunked(500).forEach { chunk -> dao.upsertAll(chunk.map { channel(it.toLong()) }) }
    }
    @After fun close() = db.close()

    @Test fun largeCatalogPagesAreBoundedStableAndCoverBoundariesWithDuplicateNames() = runBlocking {
        seed(40001)
        val first = dao.managerPage(1, "category", "", 100, 0, null)
        assertEquals(40001, first.total)
        assertEquals((1L..100L).toList(), first.rows.map { it.id })
        assertEquals((101L..200L).toList(), dao.managerPage(1, null, "", 100, 100, first.revision).rows.map { it.id })
        assertEquals(listOf(40001L), dao.managerPage(1, null, "", 100, 40000, first.revision).rows.map { it.id })
        assertTrue(dao.managerPage(1, null, "", 100, 40100, first.revision).rows.isEmpty())
        assertEquals(first.rows, dao.managerPage(1, null, "", 100, 0, first.revision).rows)
        assertTrue(runCatching { dao.managerPage(1, null, "", 101, 0, null) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun searchIncludesOriginalDisplayAndCustomNamesAndFiltersProviderCategory() = runBlocking {
        seed()
        dao.setCustomName(1, "My renamed channel")
        dao.upsertAll(listOf(channel(5).copy(categoryId = "other"), channel(6, 2)))
        assertEquals(listOf(1L), dao.managerPage(1, null, "RENAMED", 50, 0, null).rows.map { it.id })
        assertEquals(listOf(2L), dao.managerPage(1, null, "original 2", 50, 0, null).rows.map { it.id })
        assertEquals(4, dao.managerPage(1, "category", "duplicate", 50, 0, null).total)
        assertEquals(5, dao.managerPage(1, null, "duplicate", 50, 0, null).total)
        assertEquals(0, dao.managerPage(1, null, "%", 50, 0, null).total)
    }

    @Test fun refreshRenameMoveAndProviderChangesInvalidateOldPagesBeforeAnyEdit() = runBlocking {
        seed()
        val old = dao.managerPage(1, null, "", 2, 0, null).revision
        dao.replaceCatalogue(1, (1L..4L).map { channel(it) }, 123)
        assertTrue(runCatching { dao.managerBulk(1, old, listOf(1), "hide") }.exceptionOrNull() is ManagerConflict)
        assertFalse(dao.byId(1)!!.hidden)
        val current = dao.managerPage(1, null, "", 2, 0, null).revision
        dao.setCustomName(1, "changed")
        assertTrue(runCatching { dao.managerPage(1, null, "", 2, 2, current) }.exceptionOrNull() is ManagerConflict)
        val beforeMove = dao.managerRevision(1)!!.toString()
        dao.managerMove(1, beforeMove, 3, -1)
        assertEquals(listOf(1L, 3L, 2L, 4L), dao.managerPage(1, null, "", 100, 0, null).rows.map { it.id })
        assertTrue(runCatching { dao.managerBulk(1, beforeMove, listOf(1), "hide") }.exceptionOrNull() is ManagerConflict)
        val beforeConfig = dao.managerRevision(1)!!.toString()
        db.sources().update(db.sources().byId(1)!!.copy(password = "changed-secret"))
        assertTrue(runCatching { dao.managerBulk(1, beforeConfig, listOf(1), "hide") }.exceptionOrNull() is ManagerConflict)
        val beforeDelete = dao.managerRevision(1)!!.toString()
        db.sources().delete(1)
        assertTrue(runCatching { dao.managerPage(1, null, "", 50, 0, beforeDelete) }.exceptionOrNull() is ManagerConflict)
        assertTrue(runCatching { dao.managerBulk(1, beforeDelete, listOf(1), "hide") }.exceptionOrNull() is ManagerConflict)
    }

    @Test fun bulkPreservesOtherSettingsMarksSyncBitsAndKeepsRevisionAcrossBatches() = runBlocking {
        seed()
        dao.setCustomName(1, "My channel")
        dao.setSortIndex(1, 9)
        val before = dao.byId(1)!!
        val revision = dao.managerRevision(1)!!.toString()
        assertEquals("success", dao.managerBulk(1, revision, listOf(1), "favorite").single().status)
        assertEquals(before.copy(favourite = true, syncTouched = before.syncTouched or 1), dao.byId(1))
        assertEquals("skipped", dao.managerBulk(1, revision, listOf(1), "favorite").single().status)
        assertEquals("success", dao.managerBulk(1, revision, listOf(1), "hide").single().status)
        assertEquals(revision, dao.managerRevision(1)!!.toString())
        assertEquals(before.copy(favourite = true, hidden = true, syncTouched = before.syncTouched or 3), dao.byId(1))
        assertEquals("success", dao.managerBulk(1, revision, listOf(1), "unfavorite").single().status)
        assertEquals("success", dao.managerBulk(1, revision, listOf(1), "unhide").single().status)
        // An explicit Unhide on a currently visible baseline still records the user's intent.
        assertEquals("success", dao.managerBulk(1, revision, listOf(4), "unhide").single().status)
        assertTrue(dao.byId(4)!!.syncTouched and 2 != 0)
        dao.replaceCatalogue(1, (1L..4L).map { channel(it).copy(hidden = it == 4L) }, 222)
        assertFalse(dao.byId(4)!!.hidden)
        assertEquals(before.customName, dao.byId(1)!!.customName)
        assertFalse(dao.byId(1)!!.hidden)
        assertFalse(dao.byId(1)!!.favourite)
        assertEquals(before.epgChannelId, dao.byId(1)!!.epgChannelId)
    }

    @Test fun oversizedDuplicateAndCrossProviderBatchesCannotEditUnrelatedRows() = runBlocking {
        seed()
        dao.upsertAll(listOf(channel(5, 2)))
        val revision = dao.managerRevision(1)!!.toString()
        for (ids in listOf(emptyList(), List(101) { it.toLong() + 1 }, listOf(1L, 1L), listOf(-1L))) {
            assertTrue(runCatching { dao.managerBulk(1, revision, ids, "hide") }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(listOf("success", "skipped", "skipped"), dao.managerBulk(1, revision, listOf(1, 5, 99), "hide").map { it.status })
        assertFalse(dao.byId(5)!!.hidden)
        assertEquals(listOf(1L), dao.managerState(1, revision, listOf(1, 5, 99)).map { it.id })
    }

    @Test fun partialFailureReportsOnlyCommittedRowsAndReconcileDoesNotReplay() = runBlocking {
        seed()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_test BEFORE UPDATE OF hidden ON channels WHEN NEW.id = 2 BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        val revision = dao.managerRevision(1)!!.toString()
        assertEquals("success", dao.managerBulk(1, revision, listOf(1), "hide").single().status)
        // A failing batch rolls back all its writes, retaining previously committed batches.
        assertEquals(listOf("failed", "failed"), dao.managerBulk(1, revision, listOf(2, 3), "hide").map { it.status })
        val actual = dao.managerState(1, revision, listOf(1, 2, 3))
        assertEquals(listOf(true, false, false), actual.map { it.hidden })
        assertEquals(0, dao.byId(2)!!.syncTouched)
    }
}
