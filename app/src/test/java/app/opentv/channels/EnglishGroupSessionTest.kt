/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.channels

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EnglishGroupSessionTest {
    private fun group(source: Long = 1, id: String = "1", name: String = "English") =
        EnglishProviderGroup(ProviderGroupKey(source, id), "Provider $source", name, 20_000, 1)

    @Test fun noAutomaticScanAndScanDoesNotApply() = runTest {
        val session = EnglishGroupSession(this, StandardTestDispatcher(testScheduler))
        session.updateCatalog(listOf(group()))
        advanceUntilIdle()
        assertEquals(EnglishGroupState(), session.state.value)
        session.scan()
        advanceUntilIdle()
        assertTrue(session.state.value.scanned)
        assertNull(session.state.value.activeFilter)
        assertTrue(session.state.value.selected.isEmpty())
        assertFalse(session.apply())
    }
    @Test fun explicitSelectionApplyClearAndCancelPreserveOriginalInput() = runTest {
        val session = EnglishGroupSession(this, StandardTestDispatcher(testScheduler))
        val catalog = listOf(group(), group(2), group(3, name = "UK"))
        val original = catalog.toList()
        session.updateCatalog(catalog)
        session.scan(); advanceUntilIdle()
        session.selectLikely()
        assertEquals(setOf(group().key, group(2).key), session.state.value.selected)
        session.clearSelection(); session.toggle(group(3).key)
        assertTrue(session.apply()) // Unknown groups can be selected manually.
        session.beginReview(); session.toggle(group().key); session.cancelReview()
        assertEquals(setOf(group(3).key), session.state.value.activeFilter)
        assertEquals(setOf(group(3).key), session.state.value.selected)
        session.clearFilter()
        assertNull(session.state.value.activeFilter)
        assertEquals(original, catalog)
    }
    @Test fun providerIdentityAndRefreshCannotReuseStaleSelections() = runTest {
        val session = EnglishGroupSession(this, StandardTestDispatcher(testScheduler))
        session.updateCatalog(listOf(group(), group(2)))
        session.scan(); advanceUntilIdle(); session.toggle(group().key); session.apply()
        assertEquals(setOf(ProviderGroupKey(1, "1")), session.state.value.activeFilter)
        session.updateCatalog(listOf(group(2)))
        assertNull(session.state.value.activeFilter)
        assertTrue(session.state.value.selected.isEmpty())
        assertTrue(session.state.value.catalogChanged)
        assertFalse(session.apply())
        session.scan(); advanceUntilIdle(); session.selectLikely(); session.apply()
        session.updateCatalog(listOf(group(2, name = "French")))
        assertFalse(session.apply())
    }
    @Test fun repeatedScanCancelAndProviderChangesDiscardInFlightWork() = runTest {
        val queued = java.util.ArrayDeque<Runnable>()
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued.add(block) }
        }
        fun finishWorker() { while (queued.isNotEmpty()) queued.removeFirst().run() }
        val session = EnglishGroupSession(this, worker)
        session.updateCatalog(List(10_000) { group(id = "$it") })
        repeat(20) { session.scan() }
        runCurrent() // Owner has queued classification; worker has not started it.
        assertTrue(session.state.value.scanning)
        assertEquals(1, queued.size)
        session.updateCatalog(listOf(group(2, name = "French")))
        finishWorker(); advanceUntilIdle()
        assertFalse(session.state.value.scanned)
        assertTrue(session.state.value.suggestions.isEmpty())
        assertFalse(session.apply())
        session.scan(); runCurrent(); session.cancelReview()
        finishWorker(); advanceUntilIdle()
        assertFalse(session.state.value.scanning)
        assertTrue(session.state.value.suggestions.isEmpty())
        session.scan(); runCurrent(); finishWorker(); advanceUntilIdle()
        assertEquals(EnglishConfidence.UNKNOWN, session.state.value.suggestions.single().classification.confidence)
        session.selectLikely()
        assertTrue(session.state.value.selected.isEmpty())
        session.close()
        assertEquals(EnglishGroupState(), session.state.value)
    }
    @Test fun emptyCatalogAndUnrelatedSelectionAreSafe() = runTest {
        val session = EnglishGroupSession(this, StandardTestDispatcher(testScheduler))
        session.scan(); advanceUntilIdle()
        assertTrue(session.state.value.scanned)
        assertTrue(session.state.value.suggestions.isEmpty())
        session.toggle(group().key); session.selectLikely()
        assertTrue(session.state.value.selected.isEmpty())
        assertFalse(session.apply())
    }
}
