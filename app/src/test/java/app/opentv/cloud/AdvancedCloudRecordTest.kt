package app.opentv.cloud

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedCloudRecordTest {
    private val deviceA = "00000000-0000-0000-0000-000000000001"
    private val deviceB = "00000000-0000-0000-0000-000000000002"
    private val group = "11111111-1111-1111-1111-111111111111"
    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)
    private fun record(kind: String, key: String = hashA) = AdvancedCloudRecord(key, kind, 1000, deviceA)
    private fun name(value: String = "News") = record("group_name", group).copy(textValue = value)

    @Test fun groupSerializationIsVersionedAndOnlyContainsExplicitFields() {
        val encoded = advancedCloudJson.encodeToString(AdvancedCloudRecord.serializer(), name())
        assertEquals(name(), advancedCloudJson.decodeFromString(AdvancedCloudRecord.serializer(), encoded))
        val fields = (advancedCloudJson.parseToJsonElement(encoded) as JsonObject).keys
        assertEquals(setOf("record_key", "kind", "updated_at_ms", "device_id", "schema_version", "deleted",
            "text_value", "bool_value", "int_value", "entries"), fields)
        assertFalse(encoded.contains("provider"))
    }

    @Test(expected = SerializationException::class) fun credentialBearingUnknownFieldIsRejected() {
        val encoded = advancedCloudJson.encodeToString(AdvancedCloudRecord.serializer(), name())
        advancedCloudJson.decodeFromString(AdvancedCloudRecord.serializer(), encoded.dropLast(1) + ",\"provider_password\":\"secret\"}")
    }

    @Test fun groupNamesHaveStrictShapeAndBounds() {
        assertTrue(name().isSafe(1000))
        assertTrue(name("x".repeat(120)).isSafe(1000))
        assertFalse(name("").isSafe(1000))
        assertFalse(name("x".repeat(121)).isSafe(1000))
        assertFalse(name("line\nbreak").isSafe(1000))
        assertFalse(name("\u007f").isSafe(1000))
        assertFalse(name("\uD800").isSafe(1000))
        assertTrue(name("\uD83D\uDE00".repeat(120)).isSafe(1000))
        assertFalse(name().copy(boolValue = false).isSafe(1000))
        assertFalse(name().copy(recordKey = "local-room-id-1").isSafe(1000))
    }

    @Test fun groupDeletionIsExplicitAndOnlyAllowedForGroupIdentity() {
        assertTrue(name().copy(deleted = true, textValue = null).isSafe(1000))
        assertFalse(name().copy(deleted = true).isSafe(1000))
        assertFalse(record("channel_hidden").copy(boolValue = false, deleted = true).isSafe(1000))
    }

    @Test fun orderedMembershipIsUniqueAndContainsOnlyPublicFingerprints() {
        val members = record("group_members", group).copy(entries = listOf(hashB, hashA))
        assertTrue(members.isSafe(1000))
        assertEquals(listOf(hashB, hashA), advancedCloudJson.decodeFromString(AdvancedCloudRecord.serializer(),
            advancedCloudJson.encodeToString(AdvancedCloudRecord.serializer(), members)).entries)
        assertFalse(members.copy(entries = listOf(hashA, hashA)).isSafe(1000))
        assertFalse(members.copy(entries = listOf("https://provider/user/password")).isSafe(1000))
        assertTrue(members.copy(entries = (0 until 2000).map { CloudIdentity.hash("channel:$it") }).isSafe(1000))
        assertFalse(members.copy(entries = (0..2000).map { CloudIdentity.hash("channel:$it") }).isSafe(1000))
    }

    @Test fun ranksAreBoundedAndTyped() {
        for ((kind, key) in listOf("group_rank" to group, "channel_order" to hashA)) {
            val rank = record(kind, key).copy(intValue = 0)
            assertTrue(rank.isSafe(1000))
            assertTrue(rank.copy(intValue = 1_000_000).isSafe(1000))
            assertFalse(rank.copy(intValue = -1).isSafe(1000))
            assertFalse(rank.copy(intValue = 1_000_001).isSafe(1000))
            assertFalse(rank.copy(textValue = "1").isSafe(1000))
        }
    }

    @Test fun hideUnhideAndFavouriteRemovalHaveExplicitBooleanStates() {
        for (kind in listOf("channel_hidden", "channel_favourite_state", "movie_favourite_state", "series_favourite_state")) {
            val state = record(kind).copy(boolValue = true)
            assertTrue(state.isSafe(1000))
            assertTrue(state.copy(boolValue = false).isSafe(1000))
            assertFalse(state.copy(boolValue = null).isSafe(1000))
            assertFalse(state.copy(intValue = 1).isSafe(1000))
        }
    }

    @Test fun customChannelNamesCanBeResetWithEmptyString() {
        assertTrue(record("channel_name").copy(textValue = "My station").isSafe(1000))
        assertTrue(record("channel_name").copy(textValue = "").isSafe(1000))
        assertFalse(record("channel_name").copy(textValue = null).isSafe(1000))
    }

    @Test fun appOrderSupportsExplicitUnpinAndOnlyBoundedPackageNames() {
        val apps = record("app_order", "apps").copy(entries = listOf("com.example.player", "org.example.app_2"))
        assertTrue(apps.isSafe(1000))
        assertTrue(apps.copy(entries = emptyList()).isSafe(1000))
        assertFalse(apps.copy(entries = listOf("com.example.player", "com.example.player")).isSafe(1000))
        for (invalid in listOf("https://provider", "single", "com..app", "com.1app", "com.example/app", "x." + "a".repeat(254))) {
            assertFalse(invalid, apps.copy(entries = listOf(invalid)).isSafe(1000))
        }
        assertTrue(apps.copy(entries = (0 until 200).map { "com.example.app$it" }).isSafe(1000))
        assertFalse(apps.copy(entries = (0..200).map { "com.example.app$it" }).isSafe(1000))
    }

    @Test fun homePreferencesUseTheImplementedShelfAllowlist() {
        for (key in AdvancedCloudRecord.HOME_SHELVES) assertTrue(record("home_shelf", key).copy(boolValue = false).isSafe(1000))
        assertFalse(record("home_shelf", "arbitrary_ui_state").copy(boolValue = true).isSafe(1000))
    }

    @Test fun compatibleSettingsHaveExactTypedEnums() {
        for ((key, values) in mapOf("theme_mode" to listOf("SYSTEM", "DARK", "LIGHT"),
            "channel_layout" to listOf("GRID", "LIST"),
            "startup_destination" to listOf("HOME", "LIVE_TV", "GUIDE", "LAST_WATCHED", "APPS"))) {
            for (value in values) assertTrue(record("setting", key).copy(textValue = value).isSafe(1000))
            assertFalse(record("setting", key).copy(textValue = "unsupported").isSafe(1000))
            assertFalse(record("setting", key).copy(boolValue = true).isSafe(1000))
        }
        assertTrue(record("setting", "subtitles_enabled").copy(boolValue = false).isSafe(1000))
        assertFalse(record("setting", "subtitles_enabled").copy(textValue = "true").isSafe(1000))
        for (key in listOf("provider_url", "smb_password", "parental_pin", "buffer_size", "theme_mode_extra")) {
            assertFalse(record("setting", key).copy(textValue = "secret").isSafe(1000))
        }
    }

    @Test fun validationRejectsUnknownSchemaKindsIdsAndClockSkew() {
        assertFalse(name().copy(schemaVersion = 2).isSafe(1000))
        assertFalse(name().copy(kind = "provider_settings").isSafe(1000))
        assertFalse(name().copy(deviceId = "user-token").isSafe(1000))
        assertFalse(name().copy(recordKey = group.uppercase().replace('1', 'A')).isSafe(1000))
        assertFalse(name().copy(updatedAtMillis = 0).isSafe(1000))
        assertTrue(name().copy(updatedAtMillis = 301_000).isSafe(1000))
        assertFalse(name().copy(updatedAtMillis = 301_001).isSafe(1000))
        assertTrue(name().copy(updatedAtMillis = Long.MAX_VALUE).isSafe(Long.MAX_VALUE))
    }

    @Test fun eachCategoryIsExplicitAndOnlyExistingBehaviourDefaultsOn() {
        assertEquals(setOf(AdvancedSyncCategory.FAVOURITES_PROGRESS), AdvancedSyncCategory.DEFAULTS)
        assertEquals(AdvancedSyncCategory.CUSTOM_GROUPS, name().category)
        assertEquals(AdvancedSyncCategory.CHANNEL_ORGANIZATION, record("channel_hidden").category)
        assertEquals(AdvancedSyncCategory.FAVOURITE_REMOVALS, record("channel_favourite_state").category)
        assertEquals(AdvancedSyncCategory.HOME_PREFERENCES, record("home_shelf", "continue").category)
        assertEquals(AdvancedSyncCategory.APP_FAVOURITES, record("app_order", "apps").category)
        assertEquals(AdvancedSyncCategory.COMPATIBLE_SETTINGS, record("setting", "theme_mode").category)
    }

    @Test fun timestampThenDeviceThenPayloadProduceSameWinnerInBothDirections() {
        val first = name("A")
        val later = name("A").copy(updatedAtMillis = 1001)
        val device = name("A").copy(deviceId = deviceB)
        val payload = name("B")
        for ((a, b) in listOf(first to later, first to device, first to payload)) {
            assertEquals(b, newestAdvancedRecord(a, b))
            assertEquals(b, newestAdvancedRecord(b, a))
        }
        val bmp = name("\uE000")
        val supplementary = name("\uD800\uDC00")
        assertEquals(supplementary, newestAdvancedRecord(bmp, supplementary))
        val ranks = record("channel_order").copy(intValue = 9) to record("channel_order").copy(intValue = 10)
        assertEquals(ranks.second, newestAdvancedRecord(ranks.first, ranks.second))
        val membersA = record("group_members", group).copy(entries = listOf(hashA, hashB))
        val membersB = membersA.copy(entries = listOf(hashB, hashA))
        assertEquals(membersB, newestAdvancedRecord(membersA, membersB))
    }

    @Test fun deletedGroupCannotBeResurrectedByEvenANewerRename() {
        val deleted = name().copy(updatedAtMillis = 1, deleted = true, textValue = null)
        val staleLive = name().copy(updatedAtMillis = 100_000, deviceId = deviceB)
        assertEquals(deleted, newestAdvancedRecord(deleted, staleLive))
        assertEquals(deleted, newestAdvancedRecord(staleLive, deleted))
    }

    @Test(expected = IllegalArgumentException::class) fun unrelatedPreferenceKeysCannotOverwriteEachOther() {
        newestAdvancedRecord(record("home_shelf", "continue").copy(boolValue = true),
            record("home_shelf", "recent").copy(boolValue = false))
    }
}
