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
import app.opentv.ui.home.SportsBroadcastChooser
import app.opentv.ui.home.SportsGameCard
import app.opentv.ui.home.SportsStreamCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression tests for sports remote ui. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class SportsRemoteUiTest {
    @get:Rule val compose = createComposeRule()
    private val event = SportsMatching.inferred(sportsProgramme(), SPORTS_NOW)!!
    /** Checks: dpad Focus Does Not Tune And Select Only Opens Game Details. */
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
    /** Checks: stream Switch Requires Explicit Watch Selection And Focus Is Restorable. */
    @Test fun streamSwitchRequiresExplicitWatchSelectionAndFocusIsRestorable() {
        var tunes = 0
        compose.setContent { MaterialTheme {
            SportsStreamCard(sportsStream(), false, { tunes++ }, {}, {}, {}, {}, {})
        } }
        val watch = compose.onNodeWithText("Watch Fullscreen")
        watch.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { assertEquals(0, tunes) }
        watch.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, tunes) }
        compose.onNodeWithText("Favorite channel").performSemanticsAction(SemanticsActions.RequestFocus)
        watch.performSemanticsAction(SemanticsActions.RequestFocus)
        watch.assertIsFocused()
        compose.runOnIdle { assertEquals(1, tunes) }
    }
    /** Checks: suggested Streams Cannot Be Opened Until Confirmed And Quality Is Explicitly Unverified. */
    @Test fun suggestedStreamsCannotBeOpenedUntilConfirmedAndQualityIsExplicitlyUnverified() {
        var confirms = 0; var tunes = 0
        val stream = sportsStream(sportsChannel().copy(qualityLabel = "4K"), MatchConfidence.POSSIBLE)
        compose.setContent { MaterialTheme { SportsStreamCard(stream, false, { tunes++ }, {}, { confirms++ }, {}, {}, {},
            onAddToMultiView = { tunes++ }, onReplaceMultiViewPanel = { tunes++ }) } }
        compose.onNodeWithText("Watch Fullscreen").assertDoesNotExist()
        compose.onNodeWithText("Add Broadcast to Multi-View").assertDoesNotExist()
        compose.onNodeWithText("Replace Multi-View Panel").assertDoesNotExist()
        compose.onNodeWithText("Provider quality label (unverified): 4K").assertIsDisplayed()
        val confirm = compose.onNodeWithText("Confirm match")
        confirm.performSemanticsAction(SemanticsActions.RequestFocus)
        confirm.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, confirms); assertEquals(0, tunes) }
    }
    @Test fun multiViewActionsRequireExplicitSelectionAndSuggestionsExposeNoPlaybackAction() {
        var adds = 0; var replacements = 0
        compose.setContent { MaterialTheme {
            SportsStreamCard(sportsStream(), false, {}, {}, {}, {}, {}, {},
                onAddToMultiView = { adds++ }, onReplaceMultiViewPanel = { replacements++ })
        } }
        val add = compose.onNodeWithText("Add Broadcast to Multi-View")
        add.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { assertEquals(0, adds); assertEquals(0, replacements) }
        add.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, adds); assertEquals(0, replacements) }
        val replace = compose.onNodeWithText("Replace Multi-View Panel")
        replace.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        replace.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, adds); assertEquals(1, replacements) }
    }
    @Test fun broadcastChooserShowsProvidersAndChoosesOnlyOneConfirmedBroadcast() {
        val first = sportsStream()
        val second = sportsStream(sportsChannel(2, 2))
        val suggestion = sportsStream(sportsChannel(3, 3), MatchConfidence.POSSIBLE)
        val choices = mutableListOf<SportsStream>()
        compose.setContent { MaterialTheme {
            SportsBroadcastChooser(listOf(first, second, suggestion), onChoose = { choices += it }, onCancel = {})
        } }
        compose.onNodeWithText("Provider 1 · Sports channel 1 · Confirmed").assertIsDisplayed()
        val providerTwo = compose.onNodeWithText("Provider 2 · Sports channel 2 · Confirmed")
        providerTwo.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { assertTrue(choices.isEmpty()) }
        compose.onNodeWithText("Provider 3 · Sports channel 3 · Confirmed").assertDoesNotExist()
        providerTwo.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(listOf(second), choices) }
    }
    /** Checks: returning Game Card Requests Focus Without Opening Playback. */
    @Test fun returningGameCardRequestsFocusWithoutOpeningPlayback() {
        var opens = 0
        compose.setContent { MaterialTheme { SportsGameCard(SportsEventCard(event, 2), SPORTS_NOW, false,
            restore = true, onClick = { opens++ }) } }
        compose.onNodeWithText(event.title).assertIsFocused()
        compose.runOnIdle { assertEquals(0, opens) }
    }
    /** Checks: cached Event Card Clearly Labels Inference And Does Not Invent Score. */
    @Test fun cachedEventCardClearlyLabelsInferenceAndDoesNotInventScore() {
        compose.setContent { MaterialTheme {
            Column { SportsGameCard(SportsEventCard(event, 2), SPORTS_NOW, true, onClick = {}) }
        } }
        compose.onNodeWithText("EPG-derived event · inferred").assertIsDisplayed()
        compose.onNodeWithText("2 available streams").assertIsDisplayed()
        compose.onNodeWithText("★ NHL · hockey").assertIsDisplayed()
    }
}
