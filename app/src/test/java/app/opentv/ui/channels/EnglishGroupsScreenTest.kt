/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.channels

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.platform.LocalContext
import app.opentv.channels.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class EnglishGroupsScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var session: EnglishGroupSession
    private lateinit var activity: ComponentActivity
    private var backs = 0
    private var applies = 0
    private val key = ProviderGroupKey(1, "english")

    private fun show(groups: List<EnglishProviderGroup> = listOf(EnglishProviderGroup(key, "Offline provider", "English Movies", 20_000, 1))) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            session = remember { EnglishGroupSession(scope).also { it.updateCatalog(groups) } }
            activity = LocalContext.current as ComponentActivity
            val state by session.state.collectAsState()
            MaterialTheme {
                EnglishGroupsScreen(state, session::scan, session::toggle, session::selectLikely, session::clearSelection,
                    onApply = { if (session.apply()) applies++ }, onClearFilter = session::clearFilter,
                    onBack = { session.cancelReview(); backs++ })
            }
        }
    }
    private fun select(text: String) {
        val node = compose.onNodeWithText(text).performScrollTo()
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        node.performKeyInput { pressKey(Key.DirectionCenter) }
    }
    @Test fun remoteFocusScanReviewAndExplicitApplyAreSeparate() {
        show()
        compose.runOnIdle { assertFalse(session.state.value.scanned); assertNull(session.state.value.activeFilter) }
        compose.onNodeWithText("Scan provider groups").assertIsFocused()
        select("Scan provider groups")
        compose.waitUntil(10_000) { session.state.value.scanned }
        compose.onNodeWithText("Scan provider groups").assertIsFocused()
        compose.runOnIdle { assertNull(session.state.value.activeFilter) }
        val suggestion = compose.onNodeWithText("English Movies").performScrollTo()
        suggestion.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { assertTrue(session.state.value.selected.isEmpty()) }
        suggestion.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNode(isToggleable()).assertIsOn()
        select("Apply view filter")
        compose.runOnIdle { assertEquals(1, applies); assertEquals(setOf(key), session.state.value.activeFilter) }
        select("Show All Groups")
        compose.runOnIdle { assertNull(session.state.value.activeFilter) }
    }
    @Test fun emptyResultsExplainNextStepAndBackCancelsWithoutApplying() {
        show(emptyList())
        select("Scan provider groups")
        compose.waitUntil(10_000) { session.state.value.scanned }
        compose.onNodeWithText("No provider groups are available. Import or refresh an authorized provider, then scan again.").assertIsDisplayed()
        compose.runOnIdle {
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(1, backs)
            assertNull(session.state.value.activeFilter)
        }
    }
    @Test fun dpadCanMoveBetweenControlsAndCancelDraft() {
        show()
        select("Scan provider groups")
        compose.waitUntil(10_000) { session.state.value.scanned }
        compose.onNodeWithText("Scan provider groups").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Select all high-confidence suggestions").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(setOf(key), session.state.value.selected); assertNull(session.state.value.activeFilter) }
        select("Clear selection")
        compose.runOnIdle { assertTrue(session.state.value.selected.isEmpty()) }
        select("Select all high-confidence suggestions")
        select("Cancel without changes")
        compose.runOnIdle { assertEquals(1, backs); assertTrue(session.state.value.selected.isEmpty()); assertNull(session.state.value.activeFilter) }
    }
}
