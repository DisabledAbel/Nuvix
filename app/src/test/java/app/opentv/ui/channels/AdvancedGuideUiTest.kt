/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.channels

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import app.opentv.core.GuideLayout
import app.opentv.core.GuidePosition
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.ui.ChannelsViewModel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class AdvancedGuideUiTest {
    @get:Rule val compose = createComposeRule()
    private val start = 1_800_000_000_000L
    private fun row(id: Long, withProgrammes: Boolean = true): ChannelsViewModel.Row {
        val channel = Channel(id = id, sourceId = 1, streamId = "$id", name = "Channel $id", categoryId = null,
            logoUrl = null, epgChannelId = "channel$id", number = id.toInt(), streamUrl = "https://example.invalid/$id")
        val programmes = if (withProgrammes) (0 until 48).map { slot ->
            Programme(id = id * 100 + slot, feedId = 1, epgChannelId = "channel$id", title = "Programme $id-$slot",
                startUtcMillis = start + slot * 30 * 60_000L, endUtcMillis = start + (slot + 1) * 30 * 60_000L)
        } else emptyList()
        return ChannelsViewModel.Row(channel, listOf(channel), programmes.firstOrNull(), programmes.getOrNull(1), programmes)
    }
    private fun programmeTag(channel: Long, slot: Int = 0) = "guide-programme-1-channel$channel-${start + slot * 30 * 60_000L}"

    @Test fun remoteMovesAcrossTimelineAndChannelsAndSelectOpensDetailsWithoutTuning() {
        var selections = 0
        var details: Pair<Long, String>? = null
        var focusedChannel = 0L
        compose.setContent {
            MaterialTheme {
                GuideGrid(listOf(row(1), row(2)), start, null, onSelectRow = { selections++ },
                    onFocusRow = { focusedChannel = it.primary.id },
                    onProgramme = { channel, programme -> details = channel.primary.id to programme.title }, nowMillis = start + 5 * 60_000L)
            }
        }
        compose.onNodeWithTag(programmeTag(1)).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag(programmeTag(1)).performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag(programmeTag(1, 1)).assertIsFocused()
        compose.onNodeWithTag(programmeTag(1, 1)).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag(programmeTag(2, 1)).assertIsFocused()
        compose.onNodeWithTag(programmeTag(2, 1)).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            assertThat(selections).isEqualTo(0)
            assertThat(focusedChannel).isEqualTo(2L)
            assertThat(details).isEqualTo(2L to "Programme 2-1")
        }
    }

    @Test fun switchingLayoutsPreservesProgrammeFocusAndDetailsTarget() {
        var layout by mutableStateOf(GuideLayout.STANDARD)
        var selectedProgramme: String? = null
        compose.setContent {
            MaterialTheme { GuideGrid(listOf(row(1)), start, null, {}, {},
                onProgramme = { _, programme -> selectedProgramme = programme.title }, layout = layout, nowMillis = start) }
        }
        compose.onNodeWithTag(programmeTag(1)).performSemanticsAction(SemanticsActions.RequestFocus)
        listOf(GuideLayout.COMPACT, GuideLayout.EXPANDED, GuideLayout.STANDARD).forEach { next ->
            compose.runOnIdle { layout = next }
            compose.onNodeWithTag(programmeTag(1)).assertIsFocused()
        }
        compose.onNodeWithTag(programmeTag(1)).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertThat(selectedProgramme).isEqualTo("Programme 1-0") }
    }

    @Test fun hourAndNowControlsMoveSharedTimelineAndPersistPositionWithoutTuning() {
        var selections = 0
        var savedTime = start
        compose.setContent {
            MaterialTheme { GuideGrid(listOf(row(1)), start, null, { selections++ }, {}, nowMillis = start,
                onPositionChanged = { _, time -> savedTime = time }) }
        }
        compose.onNodeWithText("Next hour").performClick()
        compose.runOnIdle { assertThat(savedTime).isEqualTo(start + GUIDE_HOUR_MILLIS) }
        compose.onNodeWithText("Now").performClick()
        compose.runOnIdle { assertThat(savedTime).isEqualTo(start); assertThat(selections).isEqualTo(0) }
        compose.onNodeWithTag(programmeTag(1)).assertIsDisplayed()
    }

    @Test fun currentChannelRestoresFocusAcrossLazyRowsIncludingMissingGuideData() {
        var selections = 0
        val rows = (1L..100L).map { row(it, withProgrammes = false) }
        compose.setContent {
            MaterialTheme { GuideGrid(rows, start, null, { selections++ }, {}, currentChannelId = 80,
                nowMillis = start, rememberPosition = false) }
        }
        compose.onNodeWithText("Current channel").performClick()
        compose.onNodeWithTag("guide-channel-80").assertIsFocused()
        compose.runOnIdle { assertThat(selections).isEqualTo(0) }
    }

    @Test fun currentChannelClearsAnExcludingFilterBeforeRestoringFocusWithoutTuning() {
        var rows by mutableStateOf(listOf(row(1, withProgrammes = false)))
        var selections = 0
        var clearFilterCalls = 0
        compose.setContent {
            MaterialTheme { GuideGrid(rows, start, null, { selections++ }, {}, currentChannelId = 80,
                nowMillis = start, onReturnToCurrentChannel = {
                    clearFilterCalls++
                    rows = (1L..100L).map { row(it, withProgrammes = false) }
                }) }
        }
        compose.onNodeWithText("Current channel").performClick()
        compose.onNodeWithTag("guide-channel-80").assertIsFocused()
        compose.runOnIdle { assertThat(clearFilterCalls).isEqualTo(1); assertThat(selections).isEqualTo(0) }
    }

    @Test fun rapidRemoteInputAcrossOffscreenRowsKeepsTheLatestFocusTarget() {
        val rows = (1L..100L).map { row(it, withProgrammes = false) }
        var selections = 0
        compose.setContent {
            MaterialTheme { GuideGrid(rows, start, null, { selections++ }, {}, nowMillis = start) }
        }
        compose.onNodeWithTag("guide-channel-1").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("guide-channel-1").performKeyInput {
            repeat(25) { pressKey(Key.DirectionDown) }
        }
        compose.onNodeWithTag("guide-channel-26").assertIsFocused()
        compose.runOnIdle { assertThat(selections).isEqualTo(0) }
    }

    @Test fun savedPositionRestoresChannelAndVirtualizedTimelineAndReportsBoundedSlice() {
        var visibleIds = emptyList<Long>()
        val rows = (1L..1000L).map { row(it, withProgrammes = false) }
        compose.setContent {
            MaterialTheme { GuideGrid(rows, start, null, {}, {}, nowMillis = start,
                restorePosition = GuidePosition(600, start + 6 * GUIDE_HOUR_MILLIS),
                onVisibleRowsChanged = { visibleIds = it.map { row -> row.primary.id } }) }
        }
        compose.onNodeWithTag("guide-channel-600").assertIsFocused()
        compose.runOnIdle { assertThat(visibleIds).contains(600L); assertThat(visibleIds.size).isAtMost(60) }
    }

    @Test fun legacyListReportsNewGuideRowsAfterScrollingBeyondInitialWindow() {
        var visibleIds = emptyList<Long>()
        val rows = (1L..500L).map { row(it, withProgrammes = false) }
        compose.setContent {
            MaterialTheme { ChannelList(rows, null, {}, {}, nowMillis = start,
                onVisibleRowsChanged = { visibleIds = it.map { row -> row.primary.id } }, modifier = androidx.compose.ui.Modifier) }
        }
        compose.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performScrollToIndex(150)
        compose.runOnIdle { assertThat(visibleIds).contains(151L); assertThat(visibleIds.size).isAtMost(60) }
    }

    @Test fun changedAutomaticMatchReloadsVisibleEpgWithoutScrolling() {
        var channel by mutableStateOf(row(1, withProgrammes = false))
        var reportedCandidate: String? = null
        compose.setContent {
            MaterialTheme { GuideGrid(listOf(channel), start, null, {}, {}, nowMillis = start,
                onVisibleRowsChanged = { reportedCandidate = it.firstOrNull()?.primary?.matchedEpgId }) }
        }
        compose.runOnIdle {
            val matched = channel.primary.copy(matchedEpgId = "new-match")
            channel = channel.copy(primary = matched, variants = listOf(matched))
        }
        compose.runOnIdle { assertThat(reportedCandidate).isEqualTo("new-match") }
    }

    @Test fun emptyGuideProvidesClearFilterFeedback() {
        compose.setContent { MaterialTheme { GuideGrid(emptyList(), start, null, {}, {}, nowMillis = start) } }
        compose.onNodeWithText("No channels match this guide filter.").assertIsDisplayed()
    }
}
