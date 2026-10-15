/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.player

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.opentv.player.MiniGuideState
import app.opentv.player.PlaybackQueue
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MiniTvGuideUiTest {
    @get:Rule val compose = createComposeRule()
    private val queue = (1L..200L).map { PlaybackQueue.Item(it, "Channel $it", null, it.toInt(), listOf("epg-$it")) }

    @Test fun `opening focuses playing row missing EPG is readable and invisible rows do no EPG work`() {
        val loaded = mutableSetOf<String>()
        val selections = mutableListOf<Long>()
        var state by mutableStateOf(MiniGuideState().open(queue, 1, 0))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    MiniTvGuide(queue, state, programmes = { ids, _ ->
                        loaded.addAll(ids)
                        flowOf(emptyList())
                    }, enabled = true, opacity = 0.82f, previousId = null,
                        onHighlight = { state = state.highlight(it, queue, 0) }, onSelect = selections::add,
                        onToggleFavourites = {}, onRowFocusChanged = { _, _ -> })
                }
            }
        }
        compose.onNodeWithText("Channel 1").assertIsFocused()
        compose.onAllNodesWithText("No program information.", substring = true).onFirst().assertExists()
        compose.runOnIdle {
            assertThat(selections).isEmpty()
            assertThat(loaded).contains("epg-1")
            assertThat(loaded).doesNotContain("epg-200")
            assertThat(loaded.size).isLessThan(20)
        }
        compose.onNodeWithText("Channel 1").performKeyInput { pressKey(Key.DirectionDown) }
        compose.runOnIdle {
            assertThat(selections).isEmpty()
            assertThat(state.highlightedId).isEqualTo(2)
            assertThat(state.playingId).isEqualTo(1)
        }
        compose.onNodeWithText("Channel 2").performClick()
        compose.runOnIdle { assertThat(selections).containsExactly(2L) }
    }

    @Test fun `metadata errors use fallback and legacy mode does not subscribe to EPG`() {
        val smallQueue = queue.take(1)
        var enabled by mutableStateOf(true)
        var loads = 0
        compose.setContent {
            MaterialTheme {
                MiniTvGuide(smallQueue, MiniGuideState().open(smallQueue, 1, 0), programmes = { _, _ ->
                    loads++
                    flow { throw IOException("Fixture metadata failure") }
                }, enabled = enabled, opacity = 0.82f, previousId = null,
                    onHighlight = {}, onSelect = {}, onToggleFavourites = {}, onRowFocusChanged = { _, _ -> })
            }
        }
        compose.onNodeWithText("No program information.", substring = true).assertExists()
        var beforeLegacy = 0
        compose.runOnIdle { beforeLegacy = loads; enabled = false }
        compose.onNodeWithText("Channel 1").assertExists()
        compose.onNodeWithText("No program information.", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertThat(loads).isEqualTo(beforeLegacy) }
    }
}
