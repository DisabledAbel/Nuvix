/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.recordings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.opentv.connections.*
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.recording.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class RecordingConflictRemoteTest {
    @get:Rule val compose = createComposeRule()
    private fun warning(): RecordingConflictWarning {
        val ledger = ConnectionAllocator()
        ledger.registerSource(1, "safe-opaque-account", AccountConnectionPolicy(1, false))
        ledger.reserve(ConnectionRequest("primary", 1, 1, ConnectionPurpose.MULTIVIEW))
        val rec = Recording(id = 1, channelId = 99, sourceId = 1, channelName = "Sports", title = "NHL Game",
            filePath = "/fake.ts", streamUrl = "https://private.invalid/secret", status = RecordingStatus.SCHEDULED,
            scheduledStartMillis = 120_000, scheduledEndMillis = 600_000)
        return RecordingConflictWarning(MultiViewRecordingConflictPolicy.detect(listOf(rec), ledger.snapshots.value, 0).single(), emptyList(), 0)
    }
    @Test fun dpadActivatesSwitchReleaseDetailsAndKeepWatchingWithAccessibleLabels() {
        val actions = mutableListOf<String>()
        compose.setContent { MaterialTheme { RecordingConflictDialog(warning(), "Live Sports", "My provider", true,
            onSwitch = { actions.add("switch") }, onRelease = { actions.add("release") },
            onDetails = { actions.add("details") }, onKeep = { actions.add("keep") }) } }
        for ((label, action) in listOf("Switch Primary Panel to Another Provider" to "switch",
            "Release Conflicting Viewing Panel" to "release", "View Recording Details" to "details",
            "Keep Watching Until Recording Starts" to "keep")) {
            compose.onNodeWithText(label).performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
                .performKeyInput { pressKey(Key.DirectionCenter) }
            compose.runOnIdle { assertEquals(action, actions.last()) }
        }
        compose.onNodeWithText("Recording Starting Soon").assertExists()
        compose.onNodeWithText("secret", substring = true).assertDoesNotExist()
    }
    @Test fun emptyAlternativesExplainRecoveryAndKeepDeadlineActionAvailable() {
        compose.setContent { MaterialTheme { RecordingConflictDialog(warning(), "Live Sports", "Provider", false,
            choose = true, onSwitch = {}, onRelease = {}, onDetails = {}, onKeep = {}) } }
        compose.onNodeWithText("No verified or exact-guide matching broadcast", substring = true).assertExists()
        compose.onNodeWithText("Keep Watching Until Recording Starts").assertExists()
    }
}
