package app.opentv.cloud

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SupabaseAdvancedApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SupabaseCloudApi
    private val owner = "11111111-1111-1111-1111-111111111111"
    private val device = "00000000-0000-0000-0000-000000000001"
    private val categories = setOf(AdvancedSyncCategory.APP_FAVOURITES)
    private fun apps() = AdvancedCloudRecord("apps", "app_order", 1000, device, entries = listOf("com.example.app"))
    private fun page(record: AdvancedCloudRecord = apps(), id: Int = 1): String {
        val objectText = advancedCloudJson.encodeToString(AdvancedCloudRecord.serializer(), record)
        return "[{\"id\":$id," + objectText.drop(1) + "]"
    }
    @Before fun setup() {
        server = MockWebServer(); server.start()
        api = SupabaseCloudApi(CloudConfig(server.url("/").toString().trimEnd('/'), "sb_publishable_test_only"))
    }
    @After fun cleanup() { server.shutdown() }

    @Test fun pullRequestsOnlySelectedKindsWithBoundedKeysetPagination() = runBlocking {
        server.enqueue(MockResponse().setBody(page()))
        server.enqueue(MockResponse().setBody("[]"))
        assertEquals(listOf(apps()), api.pullAdvanced("test-token", owner, categories))
        val first = server.takeRequest()
        assertEquals("test-token", first.getHeader("Authorization")?.removePrefix("Bearer "))
        assertTrue(first.path!!.contains("kind=in.(app_order)"))
        assertTrue(first.path!!.contains("owner_id=eq.$owner"))
        assertTrue(first.path!!.contains("limit=20"))
        assertTrue(server.takeRequest().path!!.contains("id=gt.1"))
    }

    @Test fun futureRecordDoesNotAbortOtherValidRecordsInThePull() = runBlocking {
        val future = apps().copy(updatedAtMillis = System.currentTimeMillis() + 600_000)
        server.enqueue(MockResponse().setBody(page(future)))
        server.enqueue(MockResponse().setBody(page(apps(), 2)))
        server.enqueue(MockResponse().setBody("[]"))
        val pulled = api.pullAdvanced("test-token", owner, categories)
        assertEquals(listOf(future, apps()), pulled)
        var stored: String? = null
        val journal = AdvancedCloudJournal({ stored }, { stored = it })
        journal.bindOwner(owner)
        assertEquals(listOf(apps()), journal.mergeRemote(owner, pulled, categories, System.currentTimeMillis()))
    }

    @Test fun unsupportedSchemaAndUnknownCredentialFieldsFailBeforeReturningAnyRecords() = runBlocking {
        server.enqueue(MockResponse().setBody(page(apps().copy(schemaVersion = 2))))
        assertTrue(runCatching { api.pullAdvanced("test-token", owner, categories) }.isFailure)
        server.enqueue(MockResponse().setBody(page().replace("\"kind\":", "\"provider_password\":\"canary\",\"kind\":")))
        val error = runCatching { api.pullAdvanced("test-token", owner, categories) }.exceptionOrNull()
        assertNotNull(error)
    }

    @Test fun malformedOwnerAndUnselectedCategoriesMakeNoRequests() = runBlocking {
        assertTrue(runCatching { api.pullAdvanced("test-token", "$owner&owner_id=neq.any", categories) }.isFailure)
        assertTrue(api.pullAdvanced("test-token", owner, emptySet()).isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun advancedUploadUsesOnlyAllowlistedVersionedFields() = runBlocking {
        server.enqueue(MockResponse().setBody(""))
        api.pushAdvanced("test-token", listOf(apps()))
        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/nuvix_merge_advanced_records", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"schema_version\":1"))
        assertTrue(body.contains("com.example.app"))
        assertFalse(body.contains("owner_id")); assertFalse(body.contains("position_ms"))
        assertTrue(runCatching { api.pushAdvanced("test-token", listOf(apps().copy(entries = listOf("https://private.invalid")))) }.isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun manyIndividuallyValidPagesCannotExceedTheDeviceMemoryBudget() = runBlocking {
        val record = AdvancedCloudRecord("33333333-3333-3333-3333-333333333333", "group_members", 1000,
            device, entries = (0 until 2000).map { it.toString(16).padStart(64, '0') })
        val encoded = advancedCloudJson.encodeToString(AdvancedCloudRecord.serializer(), record)
        for (pageIndex in 0 until 8) {
            val body = (1..20).joinToString(",", "[", "]") { index ->
                "{\"id\":${pageIndex * 20 + index}," + encoded.drop(1)
            }
            server.enqueue(MockResponse().setBody(body))
        }
        val result = runCatching { api.pullAdvanced("test-token", owner, setOf(AdvancedSyncCategory.CUSTOM_GROUPS)) }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals("Cloud library is too large for this device.", result.exceptionOrNull()?.message)
    }

    @Test fun authenticationErrorsDoNotExposeResponseSecrets() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("private-refresh-token-canary"))
        val error = runCatching { api.pullAdvanced("test-token", owner, categories) }.exceptionOrNull()
        assertEquals(401, (error as CloudHttpException).status)
        assertFalse(error.message.orEmpty().contains("private-refresh-token-canary"))
        assertFalse(error.message.orEmpty().contains("test-token"))
    }
}
