/* Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.opentv.pairing.CloudManagerClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class WebManagerScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun countdownUpdatesPreserveRemoteFocusAndExpiredOrPairedCodesDisappear() {
        val ready = CloudManagerClient.State.Ready("https://manager.example", "ABCDEFGH2345",
            remainingSeconds = 299)
        val state = mutableStateOf<CloudManagerClient.State>(ready)
        var starts = 0
        var stops = 0
        var backs = 0
        compose.setContent {
            MaterialTheme {
                WebManagerContent(state.value, ready.url, {},
                    { starts++; state.value = ready }, { stops++ }, { backs++ })
            }
        }
        compose.onNodeWithText("ABCD-EFGH-2345").assertIsDisplayed()
        compose.onNodeWithText("Code expires in 4:59").assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        val done = compose.onNodeWithText("Done")
        done.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { state.value = ready.copy(remainingSeconds = 298) }
        compose.onNodeWithText("Code expires in 4:58").assertIsDisplayed()
        done.assertIsFocused()
        compose.runOnIdle { state.value = ready.copy(paired = true, remainingSeconds = null) }
        compose.onNodeWithText("ABCD-EFGH-2345").assertDoesNotExist()
        compose.onAllNodesWithText("Code expires in", substring = true).assertCountEquals(0)
        compose.runOnIdle { state.value = CloudManagerClient.State.Expired }
        compose.onNodeWithText("Code expired. Start a new session to get a new code.").assertIsDisplayed()
        compose.onNodeWithText("ABCD-EFGH-2345").assertDoesNotExist()
        compose.onAllNodesWithText("Code expires in", substring = true).assertCountEquals(0)
        assertEquals(0, starts)
        val start = compose.onNodeWithText("Start browser session")
        start.performSemanticsAction(SemanticsActions.RequestFocus)
        start.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Code expires in 4:59").assertIsDisplayed()
        assertEquals(1, starts)
        done.performSemanticsAction(SemanticsActions.RequestFocus)
        done.performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, stops)
        assertEquals(1, backs)
    }
}
