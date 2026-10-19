/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.multiview

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.opentv.multiview.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class MultiViewPresetReviewRemoteTest {
    @get:Rule val compose = createComposeRule()
    private val proposal = MultiViewPresentation(listOf(MultiViewSavedPanel("p0", MultiViewChannelReference(1, 1))), "p0", "p0", MultiViewLayout.GRID)
    private fun state(blocked: Boolean = false): MultiViewPresetReviewState {
        val plan = PresetRecordingPlan(listOf(PresetAccountForecast("one", setOf(1), 0, 0, 1, 1, PresetAllowanceKind.CONFIGURED, blocked)),
            listOf(PresetRecordingWindow(1, "NHL Game", "Sports", 1, 120_000, 600_000, true, true, false, false)),
            listOf(PresetRecordingConflict(PresetConflictKind.UPCOMING, 120_000, 600_000, listOf(1), listOf("p0"), "one")), emptySet())
        return MultiViewPresetReviewState(MultiViewPresetInspection(proposal, listOf(MultiViewPreviewChannel(1, 1, "Live Sports", "Provider A")), plan))
    }
    private fun show(blocked: Boolean = false, actions: MutableList<String> = mutableListOf()) {
        compose.setContent { MaterialTheme { PresetReviewContent("Sunday Sports", proposal, state(blocked), canSave = true,
            onRemove = { actions += "exclude" }, onPrimary = {}, onReplace = { actions += "replace" },
            onAlternatives = { actions += "provider" }, onAlternative = { _, _ -> }, onReview = { actions += "review" },
            onSave = { actions += "save" }, onRefresh = { actions += "refresh" }, onLoad = { actions += "load" }, onDismiss = { actions += "cancel" }) } }
    }
    @Test fun everyActionIsReachableWithRemoteAndDoesNotAutomaticallyLoad() {
        val actions = mutableListOf<String>(); show(actions = actions)
        compose.onNodeWithText("Cancel").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        for ((label, action) in listOf("Replace Channel: Live Sports" to "replace", "Choose Another Provider: Live Sports" to "provider",
            "Exclude Panel: Live Sports" to "exclude", "Review Recording: NHL Game" to "review", "Save Changes to Preset" to "save",
            "Continue With Warning — Load Modified Layout Once" to "load")) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.RequestFocus)
                .performKeyInput { pressKey(Key.DirectionCenter) }
            compose.runOnIdle { assertEquals(action, actions.last()) }
        }
        assertEquals(1, actions.count { it == "load" })
    }
    @Test fun currentCapacityConflictDisablesLoadingButEditingRemainsAvailable() {
        show(blocked = true)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Continue With Warning — Load Modified Layout Once"))
        compose.onNodeWithText("Continue With Warning — Load Modified Layout Once").assertIsNotEnabled()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Exclude Panel: Live Sports"))
        compose.onNodeWithText("Exclude Panel: Live Sports").assertIsEnabled()
    }
    @Test fun warningsExposeRecordingTimeProtectionAndFallbackAsAccessibleText() {
        show()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Upcoming Recording Conflict"))
        compose.onNodeWithText("Upcoming Recording Conflict").assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("needs account capacity at", substring = true))
        compose.onNodeWithText("needs account capacity at", substring = true).assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Protect Recording: On", substring = true))
        compose.onNodeWithText("Protect Recording: On", substring = true).assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("acknowledging this preview does not dismiss it", substring = true))
        compose.onNodeWithText("acknowledging this preview does not dismiss it", substring = true).assertExists()
    }
}
