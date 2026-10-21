/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.recordings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
class LateStartRemoteTest {
    @get:Rule val compose = createComposeRule()
    @Test fun dpadSelectsPolicyCustomMinutesAndSaveRequiresExplicitOk() {
        var saved: LateStartOptions? = null
        compose.setContent { MaterialTheme { LateStartDialog("Recording Late Start", LateStartOptions(),
            onDismiss = {}, onSave = { saved = it }) } }
        compose.onNodeWithText("Record Remaining").performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Skip If Late").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Minimum Time Remaining").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("15 min").performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("+1 minute").performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Minimum remaining: 16 minutes").assertExists()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("Save Late Start").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(LateStartOptions(LateStartPolicy.MINIMUM_REMAINING, 16), saved) }
    }
    @Test fun cancelLeavesStoredPolicyAndThresholdUntouched() {
        var saved = 0; var dismissed = 0
        compose.setContent { MaterialTheme { LateStartDialog("Series Late Start", LateStartOptions(LateStartPolicy.SKIP_IF_LATE),
            onDismiss = { dismissed++ }, onSave = { saved++ }) } }
        compose.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(0, saved); assertEquals(1, dismissed) }
    }
}
