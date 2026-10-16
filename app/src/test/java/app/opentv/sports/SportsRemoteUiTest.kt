/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.sports

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.opentv.ui.home.SportsAction
import app.opentv.ui.home.SportsGameCard
import app.opentv.ui.home.SportsStreamCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class SportsRemoteUiTest {
    @get:Rule val compose = createComposeRule()
    private val event = SportsMatching.inferred(sportsProgramme(), SPORTS_NOW)!!
    @Test fun dpadFocusDoesNotTuneAndSelectOnlyOpensGameDetails() {
        var focused = 0; var opened = 0
        compose.setContent { MaterialTheme {
            Row {
                SportsGameCard(SportsEventCard(event, 3), SPORTS_NOW, false, onFocus = { focused++ }, onClick = { opened++ })
                SportsAction("Other control") {}
            }
        } }
        val card = compose.onNodeWithText(event.title)
        card.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { assertEquals(0, opened); assertTrue(focused > 0) }
        card.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, opened) }
        card.performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Other control").assertIsFocused()
    }
    @Test fun streamSwitchRequiresExplicitWatchSelectionAndFocusIsRestorable() {
        var tunes = 0
        compose.setContent { MaterialTheme {
            SportsStreamCard(sportsStream(), false, { tunes++ }, {}, {}, {}, {}, {})
        } }
        val watch = compose.onNodeWithText("Watch / Switch to Sports channel 1")
        watch.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { assertEquals(0, tunes) }
        watch.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, tunes) }
        compose.onNodeWithText("Favorite channel").performSemanticsAction(SemanticsActions.RequestFocus)
        watch.performSemanticsAction(SemanticsActions.RequestFocus)
        watch.assertIsFocused()
        compose.runOnIdle { assertEquals(1, tunes) }
    }
    @Test fun suggestedStreamsCannotBeOpenedUntilConfirmedAndQualityIsExplicitlyUnverified() {
        var confirms = 0; var tunes = 0
        val stream = sportsStream(sportsChannel().copy(qualityLabel = "4K"), MatchConfidence.POSSIBLE)
        compose.setContent { MaterialTheme { SportsStreamCard(stream, false, { tunes++ }, {}, { confirms++ }, {}, {}, {}) } }
        compose.onNodeWithText("Watch / Switch to Sports channel 1").assertDoesNotExist()
        compose.onNodeWithText("Provider quality label (unverified): 4K").assertIsDisplayed()
        val confirm = compose.onNodeWithText("Confirm match")
        confirm.performSemanticsAction(SemanticsActions.RequestFocus)
        confirm.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, confirms); assertEquals(0, tunes) }
    }
    @Test fun returningGameCardRequestsFocusWithoutOpeningPlayback() {
        var opens = 0
        compose.setContent { MaterialTheme { SportsGameCard(SportsEventCard(event, 2), SPORTS_NOW, false,
            restore = true, onClick = { opens++ }) } }
        compose.onNodeWithText(event.title).assertIsFocused()
        compose.runOnIdle { assertEquals(0, opens) }
    }
    @Test fun cachedEventCardClearlyLabelsInferenceAndDoesNotInventScore() {
        compose.setContent { MaterialTheme {
            Column { SportsGameCard(SportsEventCard(event, 2), SPORTS_NOW, true, onClick = {}) }
        } }
        compose.onNodeWithText("EPG-derived event · inferred").assertIsDisplayed()
        compose.onNodeWithText("2 available streams").assertIsDisplayed()
        compose.onNodeWithText("★ NHL · hockey").assertIsDisplayed()
    }
}
