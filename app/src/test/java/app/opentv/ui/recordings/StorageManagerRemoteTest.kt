/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.recordings

import android.app.Application
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import app.opentv.core.AppSettings
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
class StorageManagerRemoteTest {
    @get:Rule val compose = createComposeRule()
    private fun row(id: Long = 1) = Recording(id = id, channelId = 1, sourceId = 1, channelName = "Synthetic", title = "Program $id",
        filePath = "/private-folder/$id.ts", streamUrl = "https://private.invalid/secret", status = RecordingStatus.COMPLETED,
        endedAtMillis = 123_000, sizeBytes = 1024)
    private fun select(id: Long) = compose.onNodeWithText("Select: Program $id", substring = true).performScrollTo()
        .performSemanticsAction(SemanticsActions.RequestFocus).performKeyInput { pressKey(Key.DirectionCenter) }
    private fun review() = compose.onNodeWithText("Review deletion (1)").performScrollTo()
        .performSemanticsAction(SemanticsActions.RequestFocus).performKeyInput { pressKey(Key.DirectionCenter) }

    @Test fun remoteSelectionOnlyProposesAndCancelIsInitiallyFocusedBeforeExplicitConfirmation() {
        val deleted = mutableListOf<Recording>()
        var busy by mutableStateOf(false)
        compose.setContent { MaterialTheme { StorageManagerContent(emptyList(), listOf(row()), emptyList(), busy, null,
            {}, {}, { _, _ -> }, { deleted += it; busy = true }) } }
        select(1); review()
        compose.onNodeWithText("Cancel deletion").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(deleted.isEmpty()) }
        compose.onNodeWithText("Review deletion (1)").assertIsFocused()
        review()
        compose.onNodeWithText("Confirm delete recording files").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(listOf(row()), deleted) }
        compose.onNodeWithText("Refresh storage").assertIsFocused()
        compose.onNodeWithText("private-folder", substring = true).assertDoesNotExist()
        compose.onNodeWithText("secret", substring = true).assertDoesNotExist()
    }
    @Test fun stableKeysPreserveFocusedSelectionOnRefreshAndFallbackAfterRemoval() {
        var rows by mutableStateOf(listOf(row(1), row(2)))
        compose.setContent { MaterialTheme { StorageManagerContent(emptyList(), rows, emptyList(), false, null,
            {}, {}, { _, _ -> }, {}) } }
        select(1)
        compose.runOnIdle { rows = rows.map { if (it.id == 2L) it.copy(sizeBytes = 2048) else it } }
        compose.onNodeWithText("Selected: Program 1", substring = true).assertIsFocused()
        compose.runOnIdle { rows = listOf(row(2)) }
        compose.onNodeWithText("Refresh storage").assertIsFocused()
        compose.onNodeWithText("Review deletion (0)").assertIsNotEnabled()
    }
    @Test fun deletingOrProtectingFocusedRecordingDeepInListRestoresAnAttachedFocusableControl() {
        var rows by mutableStateOf((1L..100).map(::row))
        compose.setContent { MaterialTheme { StorageManagerContent(emptyList(), rows, emptyList(), false, null,
            {}, {}, { _, _ -> }, {}) } }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(76)
        select(75)
        compose.runOnIdle { rows = rows.filter { it.id != 75L } }
        // LazyColumn can retain focus on a surviving neighbor before the explicit fallback runs.
        compose.onNode(isFocused()).assertExists().assertIsEnabled()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(51)
        select(50)
        compose.runOnIdle { rows = rows.map { if (it.id == 50L) it.copy(protectedRecording = true) else it } }
        compose.onNode(isFocused()).assertExists().assertIsEnabled()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        compose.onNodeWithText("Review deletion (0)").assertIsNotEnabled()
    }
    @Test fun policyOptInIsSeparatePerDestinationAndLargeTextRemainsScrollable() {
        val dest = RecordingDestination("opaque", "This device", AppSettings.RecordingTarget.INTERNAL)
        val summary = StorageSummary(dest, StorageCapacity(StorageAvailability.AVAILABLE, System.currentTimeMillis()),
            1024, null, DvrStoragePolicy())
        val saved = mutableListOf<DvrStoragePolicy>()
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
            MaterialTheme { StorageManagerContent(listOf(summary), listOf(row().copy(protectedRecording = true)), emptyList(), false, null,
                {}, {}, { _, policy -> saved += policy }, {}) }
        } }
        compose.onNodeWithText("Estimated remaining recording hours: Unknown").performScrollTo().assertExists()
        compose.onNodeWithText("Enable automatic cleanup for This device").performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
        compose.onNodeWithText("Keep cleanup off").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
        compose.onNodeWithText("Enable automatic cleanup for This device").performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Enable cleanup for this destination").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, saved.size); assertTrue(saved.single().cleanupEnabled) }
    }
    @Test fun dpadMovesBetweenBackAndRefreshAndBackDispatchLeavesWithoutDeleting() {
        var backs = 0; var deletes = 0
        var owner: OnBackPressedDispatcherOwner? = null
        compose.setContent { owner = LocalOnBackPressedDispatcherOwner.current
            MaterialTheme { StorageManagerContent(emptyList(), listOf(row()), emptyList(), false, null,
                { backs++ }, {}, { _, _ -> }, { deletes++ }) } }
        compose.onNodeWithText("Back to Recordings / Settings").performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Refresh storage").assertIsFocused()
        compose.runOnIdle { owner!!.onBackPressedDispatcher.onBackPressed(); assertEquals(1, backs); assertEquals(0, deletes) }
    }
    @Test fun dpadCanReachAndReadCleanupHistoryEvenWithoutSelectableRecordings() {
        val history = (1L..2).map { app.opentv.data.model.RecordingEvent(id = it, recordingId = 7,
            atMillis = 1000, kind = "STORAGE_CLEANUP", message = "Automatic cleanup event $it") }
        compose.setContent { MaterialTheme { StorageManagerContent(emptyList(), emptyList(), history, false, null,
            {}, {}, { _, _ -> }, {}) } }
        compose.onNodeWithText("Sort by size (largest first)").performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Automatic cleanup event 1", substring = true).assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Automatic cleanup event 2", substring = true).assertIsFocused()
    }

}
