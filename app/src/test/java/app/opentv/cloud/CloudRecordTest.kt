package app.opentv.cloud

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for cloud record. */
class CloudRecordTest {
    private val deviceA = "00000000-0000-0000-0000-000000000001"
    private val deviceB = "00000000-0000-0000-0000-000000000002"
    private val key = "a".repeat(64)
    private val profile = "b".repeat(64)
    /** Builds a synthetic playback-progress record with controlled time and device identity. */
    private fun progress(time: Long = 1000, position: Long = 900, device: String = deviceA) =
        CloudRecord(key, "movie_progress", profile, position, 1000, time, device)

    /** Checks: newer Intentional Rewind Wins. */
    @Test fun newerIntentionalRewindWins() {
        val old = progress(time = 1000, position = 900)
        val rewind = progress(time = 2000, position = 100)
        assertEquals(rewind, newestRecord(old, rewind))
        assertEquals(rewind, newestRecord(rewind, old))
    }

    /** Checks: ties Have One Stable Winner In Both Directions. */
    @Test fun tiesHaveOneStableWinnerInBothDirections() {
        val a = progress(device = deviceA)
        val b = progress(device = deviceB)
        assertEquals(b, newestRecord(a, b))
        assertEquals(b, newestRecord(b, a))
    }

    /** Checks: cannot Merge Different Profiles. */
    @Test(expected = IllegalArgumentException::class) fun cannotMergeDifferentProfiles() {
        newestRecord(progress(), progress().copy(profileKey = "c".repeat(64)))
    }

    /** Checks: validates Remote Allowlist Bounds And Future Clock. */
    @Test fun validatesRemoteAllowlistBoundsAndFutureClock() {
        assertTrue(progress().isSafe(2000))
        assertFalse(progress().copy(kind = "provider_settings").isSafe(2000))
        assertFalse(progress().copy(recordKey = "https://provider/user/password").isSafe(2000))
        assertFalse(progress().copy(positionMillis = -1).isSafe(2000))
        assertFalse(progress().copy(positionMillis = 2000).isSafe(2000))
        assertFalse(progress().copy(updatedAtMillis = 302001).isSafe(2000))
        assertFalse(progress().copy(deviceId = "user-token").isSafe(2000))
        assertFalse(progress().copy(profileKey = CloudRecord.EMPTY_PROFILE).isSafe(2000))
        assertFalse(CloudRecord(key, "movie_favourite", profileKey = profile,
            updatedAtMillis = 1000, deviceId = deviceA).isSafe(2000))
    }

    /** Checks: identity Does Not Depend On Local Id Or Url And Tmdb Matches Across Titles. */
    @Test fun identityDoesNotDependOnLocalIdOrUrlAndTmdbMatchesAcrossTitles() {
        assertEquals(CloudIdentity.media("movie", "Translated title", 2001, "123"),
            CloudIdentity.media("movie", "Original title", 2002, "123"))
        assertEquals(CloudIdentity.profile("  Alice  "), CloudIdentity.profile("alice"))
        assertEquals(CloudIdentity.channel("BBC One", null), CloudIdentity.channel("  BBC   ONE ", ""))
        assertEquals(CloudIdentity.channel("BBC One", null), CloudIdentity.channel("BBC One", "https://provider/user/password"))
        assertFalse(CloudIdentity.media("movie", "Example", 2025, null) == CloudIdentity.media("movie", "Example", 2026, null))
    }

    /** Checks: serialized Record Has Only Allowed Fields. */
    @Test fun serializedRecordHasOnlyAllowedFields() {
        val json = Json { encodeDefaults = true }.encodeToString(CloudRecord.serializer(), progress())
        val fields = Json.parseToJsonElement(json).let { it as kotlinx.serialization.json.JsonObject }.keys
        assertEquals(setOf("record_key", "kind", "profile_key", "position_ms", "duration_ms", "updated_at_ms", "device_id"), fields)
        assertFalse(json.contains("url", ignoreCase = true))
        assertFalse(json.contains("password", ignoreCase = true))
    }

    /** Checks: rejects Secret And Service Role Keys. */
    @Test fun rejectsSecretAndServiceRoleKeys() {
        assertTrue(isPublicClientKey("sb_publishable_example_publishable_key"))
        assertFalse(isPublicClientKey("sb_secret_example_secret_key"))
        assertFalse(isPublicClientKey("a.eyJyb2xlIjoic2VydmljZV9yb2xlIn0.signature"))
        assertTrue(isPublicClientKey("a.eyJyb2xlIjoiYW5vbiJ9.signature"))
        assertFalse(isPublicClientKey("unrecognised-key"))
    }
}
