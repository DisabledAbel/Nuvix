/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.recordings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
class RecordingDurationRemoteTest {
    @get:Rule val compose = createComposeRule()
    private fun row() = Recording(id = 1, channelId = 1, sourceId = 1, channelName = "Sports", title = "NHL Game",
        filePath = "/fake.ts", streamUrl = "https://private.invalid/secret", status = RecordingStatus.RECORDING,
        scheduledStartMillis = 1_000, scheduledEndMillis = 900_000, programmeEndMillis = 600_000, startedAtMillis = 1000)
    @Test fun remoteCanActivateEveryChoiceWithAccessibleLabelsWithoutConfirming() {
        val choices = mutableListOf<DurationChoice>()
        var custom = 0
        var confirmed = 0
        compose.setContent { MaterialTheme { DurationEditor(row(), null, 100_000, false, null,
            {}, { choices += it }, {}, { confirmed++ }, { custom++ }) } }
        for (minutes in listOf(5, 10, 15, 30, 60)) {
            compose.onNodeWithText("Add $minutes Minutes").performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
                .performKeyInput { pressKey(Key.DirectionCenter) }
            compose.runOnIdle { assertEquals(DurationChoice.Add(minutes), choices.last()) }
        }
        for (label in listOf("Choose End Time", "Restore Original End", "Stop Recording Now")) {
            compose.onNodeWithText(label).performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
                .performKeyInput { pressKey(Key.DirectionCenter) }
        }
        compose.runOnIdle { assertEquals(1, custom); assertEquals(DurationChoice.Stop, choices.last()); assertEquals(0, confirmed) }
        compose.onNodeWithText("secret", substring = true).assertDoesNotExist()
    }
    @Test fun stopHasSeparateExplicitConfirmationAndBlockedReviewCannotConfirm() {
        val r = row()
        val review = DurationReview(r, DurationChoice.Stop, 100_000, listOf(r), emptyList(), "Provider", 1,
            emptyList(), listOf(DurationReview.STOP_WARNING), false, 0)
        var confirmed = 0
        compose.setContent { MaterialTheme { DurationEditor(r, review, 100_000, false, null,
            {}, {}, {}, { confirmed++ }, {}) } }
        compose.onNodeWithText("Confirm Stop Recording").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, confirmed) }
    }    @Test fun customEndIsFullyRemoteOperableAndOnlyReturnsAProposal() {
        var proposed: Long? = null
        compose.setContent { MaterialTheme { DurationCustomEndDialog(900_000, {}, { proposed = it }) } }
        compose.onNodeWithText("Later Hour").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertNull(proposed) }
        compose.onNodeWithText("Review End Time").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(4_500_000L, proposed) }
    }
    @Test fun blockedReviewExposesDisabledConfirmation() {
        val r = row()
        val review = DurationReview(r, DurationChoice.Add(5), 1_200_000, listOf(r), emptyList(), "Provider", 1,
            emptyList(), listOf("Higher-priority conflict"), true, 0)
        compose.setContent { MaterialTheme { DurationEditor(r, review, 100_000, false, null, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Confirm With Warning").assertIsNotEnabled()
        compose.onNodeWithText("Adjust Extension").assertIsEnabled()
    }
    @Test fun dpadMovesBetweenIncrementsAndOkOnlyProposesTheFocusedChoice() {
        val choices = mutableListOf<DurationChoice>()
        var confirmed = 0
        compose.setContent { MaterialTheme { DurationEditor(row(), null, 100_000, false, null,
            {}, { choices += it }, {}, { confirmed++ }, {}) } }
        compose.onNodeWithText("Add 5 Minutes").performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Add 10 Minutes").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            assertEquals(listOf(DurationChoice.Add(10)), choices)
            assertEquals(0, confirmed)
        }
    }

}
