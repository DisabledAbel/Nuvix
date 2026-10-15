package app.opentv.cloud

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AdvancedSyncEngineTest {
    private val owner = "11111111-1111-1111-1111-111111111111"
    private val deviceA = "00000000-0000-0000-0000-000000000001"
    private val deviceB = "00000000-0000-0000-0000-000000000002"
    private val group = "33333333-3333-3333-3333-333333333333"
    private val enabled = setOf(AdvancedSyncCategory.CUSTOM_GROUPS, AdvancedSyncCategory.APP_FAVOURITES,
        AdvancedSyncCategory.HOME_PREFERENCES, AdvancedSyncCategory.COMPATIBLE_SETTINGS)
    private class Server {
        val rows = linkedMapOf<String, AdvancedCloudRecord>()
        var pulls = 0
        var pushes = 0
        fun push(records: List<AdvancedCloudRecord>) {
            pushes++
            for (record in records) rows[record.identity()] = rows[record.identity()]?.let {
                newestAdvancedRecord(it, record)
            } ?: record
        }
    }
    private inner class Peer(val device: String, val server: Server) {
        var persisted: String? = null
        val journal = AdvancedCloudJournal({ persisted }, { persisted = it })
        val local = linkedMapOf<String, AdvancedCloudRecord>()
        var clock = 1000L
        var failPush = false
        var failApply = false
        var afterPull: (() -> Unit)? = null
        val engine = AdvancedSyncEngine(journal, { local.values.toList() }, { records, _ ->
            if (failApply) throw IOException("Interrupted durable apply")
            records.forEach { local[it.identity()] = it }; records.size
        }, { categories ->
            server.pulls++
            server.rows.values.filter { it.category in categories }.also { afterPull?.invoke(); afterPull = null }
        }, { records ->
            server.push(records)
            if (failPush) throw IOException("Accepted request, disconnected before response")
        }, { clock })
        fun put(record: AdvancedCloudRecord) { local[record.identity()] = record }
        fun capture() {
            journal.bindOwner(owner)
            journal.capture(owner, local.values, enabled, device, clock)
        }
        suspend fun sync(categories: Set<AdvancedSyncCategory> = enabled) = engine.sync(owner, device, categories)
    }
    private fun name(text: String) = AdvancedCloudRecord(group, "group_name", 1000, deviceA, textValue = text)
    private fun apps(vararg values: String) = AdvancedCloudRecord("apps", "app_order", 1000, deviceA, entries = values.toList())

    @Test fun initialDefaultsReceiveExistingCloudChoicesAndDoNotEchoThem() = runBlocking {
        val server = Server()
        val a = Peer(deviceA, server); a.put(apps("com.example.first", "com.example.absent")); a.sync()
        val b = Peer(deviceB, server); b.put(apps()); b.sync()
        assertEquals(listOf("com.example.first", "com.example.absent"), b.local["app_order:apps"]!!.entries)
        assertEquals(0, b.journal.pendingCount(owner))
        val pushes = server.pushes
        a.sync(); b.sync()
        assertEquals(pushes, server.pushes)
    }

    @Test fun twoOfflinePeersRetainIndependentGroupEditsThenConvergeOnConflicts() = runBlocking {
        val server = Server()
        val a = Peer(deviceA, server); a.put(name("News")); a.sync()
        val b = Peer(deviceB, server); b.sync()
        a.clock = 2000; b.clock = 2000
        a.put(name("Renamed on A")); a.capture()
        b.put(AdvancedCloudRecord(group, "group_members", 2000, deviceB, entries = listOf("a".repeat(64))))
        b.capture()
        a.sync(); b.sync(); a.sync()
        assertEquals("Renamed on A", b.local["group_name:$group"]!!.textValue)
        assertEquals(listOf("a".repeat(64)), a.local["group_members:$group"]!!.entries)
        a.clock = 3000; b.clock = 3000
        a.put(name("Rename A")); a.capture()
        b.put(name("Rename B")); b.capture()
        a.sync(); b.sync(); a.sync()
        assertEquals("Rename B", a.local["group_name:$group"]!!.textValue)
        assertEquals(a.local["group_name:$group"], b.local["group_name:$group"])
    }

    @Test fun deletionWinsEvenWhenOfflinePeerHasLaterRename() = runBlocking {
        val server = Server()
        val a = Peer(deviceA, server); a.put(name("News")); a.sync()
        val b = Peer(deviceB, server); b.sync()
        a.clock = 2000; a.put(name("ignored").copy(deleted = true, textValue = null)); a.capture()
        b.clock = 9000; b.put(name("Offline rename")); b.capture()
        a.sync(); b.sync(); a.sync()
        assertTrue(a.local["group_name:$group"]!!.deleted)
        assertTrue(b.local["group_name:$group"]!!.deleted)
        assertTrue(server.rows["group_name:$group"]!!.deleted)
    }

    @Test fun failedResponseReplaysSafelyAndAcknowledgesOnlyAfterLocalCompletion() = runBlocking {
        val server = Server(); val a = Peer(deviceA, server)
        a.put(apps("com.example.app")); a.failPush = true
        assertTrue(runCatching { a.sync() }.exceptionOrNull() is IOException)
        assertEquals(1, a.journal.pendingCount(owner))
        assertEquals(listOf("com.example.app"), server.rows["app_order:apps"]!!.entries)
        a.failPush = false; a.failApply = true
        assertTrue(runCatching { a.sync() }.exceptionOrNull() is IOException)
        // The server version is durably retained even if the seed was superseded during pull.
        assertNotNull(a.persisted)
        a.failApply = false; a.sync()
        assertEquals(0, a.journal.pendingCount(owner))
        assertEquals(listOf("com.example.app"), a.local["app_order:apps"]!!.entries)
    }

    @Test fun localEditDuringPullIsCapturedBeforeRemoteApply() = runBlocking {
        val server = Server(); val a = Peer(deviceA, server)
        a.put(apps("com.example.original")); a.sync()
        a.clock = 2000
        a.afterPull = { a.put(apps("com.example.final")) }
        a.sync()
        assertEquals(listOf("com.example.final"), server.rows["app_order:apps"]!!.entries)
        assertEquals(0, a.journal.pendingCount(owner))
    }

    @Test fun disabledModeAndEmptyCategoriesDoNotReadOrWriteCloudOrJournal() = runBlocking {
        val server = Server(); val a = Peer(deviceA, server)
        a.put(apps("com.example.privateapp"))
        a.engine.sync(owner, deviceA, enabled, enabled = false)
        a.sync(emptySet()); a.sync(setOf(AdvancedSyncCategory.FAVOURITES_PROGRESS))
        assertNull(a.persisted)
        assertEquals(0, server.pulls); assertEquals(0, server.pushes)
    }

    @Test fun disabledCategoryDoesNotUploadOrApplyWhileIndependentKeysConverge() = runBlocking {
        val server = Server(); val a = Peer(deviceA, server)
        a.put(apps("com.example.privateapp")); a.put(name("Allowed group"))
        a.sync(setOf(AdvancedSyncCategory.CUSTOM_GROUPS))
        assertFalse(server.rows.containsKey("app_order:apps"))
        val b = Peer(deviceB, server); b.put(apps("com.example.local"))
        b.sync(setOf(AdvancedSyncCategory.CUSTOM_GROUPS))
        assertEquals(listOf("com.example.local"), b.local["app_order:apps"]!!.entries)
        assertEquals("Allowed group", b.local["group_name:$group"]!!.textValue)
    }
}
