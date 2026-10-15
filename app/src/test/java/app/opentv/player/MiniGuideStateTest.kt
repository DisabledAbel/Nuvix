/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MiniGuideStateTest {
    private val queue = listOf(
        PlaybackQueue.Item(4, "Four", null, favourite = true, variantIds = listOf(4, 40)),
        PlaybackQueue.Item(2, "Two", null),
        PlaybackQueue.Item(9, "Nine", null, favourite = true),
    )
    private fun open() = MiniGuideState().open(queue, 4, 1_000)

    @Test fun `existing left shortcut opens guide only from immersive playback`() {
        assertThat(action(PlayerRemoteKey.LEFT)).isEqualTo(PlayerRemoteAction.GUIDE)
        assertThat(action(PlayerRemoteKey.LEFT, controls = true)).isEqualTo(PlayerRemoteAction.CONTROLS)
        assertThat(action(PlayerRemoteKey.LEFT, picker = true)).isEqualTo(PlayerRemoteAction.PICKER_INPUT)
    }

    @Test fun `guide owns directions and select ahead of player controls and channel zapping`() {
        listOf(PlayerRemoteKey.LEFT, PlayerRemoteKey.RIGHT, PlayerRemoteKey.UP, PlayerRemoteKey.DOWN,
            PlayerRemoteKey.CHANNEL_UP, PlayerRemoteKey.CHANNEL_DOWN, PlayerRemoteKey.OTHER).forEach {
            assertThat(action(it, guide = true, controls = true)).isEqualTo(PlayerRemoteAction.GUIDE_INPUT)
        }
        assertThat(action(PlayerRemoteKey.RIGHT)).isEqualTo(PlayerRemoteAction.QUALITY)
        assertThat(action(PlayerRemoteKey.UP)).isEqualTo(PlayerRemoteAction.ZAP_UP)
        assertThat(action(PlayerRemoteKey.CHANNEL_DOWN)).isEqualTo(PlayerRemoteAction.ZAP_DOWN)
    }

    @Test fun `back and numeric entry remain available above every overlay`() {
        assertThat(action(PlayerRemoteKey.BACK, guide = true)).isEqualTo(PlayerRemoteAction.BACK)
        assertThat(action(PlayerRemoteKey.NUMBER, guide = true)).isEqualTo(PlayerRemoteAction.NUMBER)
        assertThat(open().close().visible).isFalse()
    }

    @Test fun `opening and closing playing channel never produces a tune request`() {
        val state = open()
        assertThat(state.highlightedId).isEqualTo(4)
        assertThat(state.selection(queue)).isNull()
        assertThat(state.close().selection(queue)).isNull()
        assertThat(state.close().playingId).isEqualTo(4)
    }

    @Test fun `browsing changes highlight while preserving the playing channel`() {
        val state = open().move(1, queue, 2_000)
        assertThat(state.highlightedId).isEqualTo(2)
        assertThat(state.playingId).isEqualTo(4)
        assertThat(state.visible).isTrue()
    }

    @Test fun `confirmed selection requests correct stable channel identity`() {
        assertThat(open().move(1, queue, 2_000).selection(queue)).isEqualTo(2)
    }

    @Test fun `a playing quality variant selects the primary row without restarting playback`() {
        val state = MiniGuideState().open(queue, 40, 1_000)
        assertThat(state.highlightedId).isEqualTo(4)
        assertThat(state.selection(queue)).isNull()
    }

    @Test fun `external channel switches update playing state without moving a valid highlight`() {
        val state = open().move(1, queue, 2_000).reconcile(queue, 9)
        assertThat(state.playingId).isEqualTo(9)
        assertThat(state.highlightedId).isEqualTo(2)
    }

    @Test fun `empty queue remains dismissible and never requests playback`() {
        val state = MiniGuideState().open(emptyList(), 4, 1_000).move(1, emptyList(), 2_000)
        assertThat(state.visible).isTrue()
        assertThat(state.highlightedId).isNull()
        assertThat(state.selection(emptyList())).isNull()
        assertThat(state.close().visible).isFalse()
    }

    @Test fun `removed highlighted channels are ignored and reconciled safely`() {
        val state = open().move(1, queue, 2_000)
        val remaining = queue.filterNot { it.id == 2L }
        assertThat(state.selection(remaining)).isNull()
        assertThat(state.reconcile(remaining, 9).highlightedId).isEqualTo(9)
    }

    @Test fun `unknown playing ids default to first channel without corrupting playing identity`() {
        val state = MiniGuideState().open(queue, 1234, 1_000)
        assertThat(state.highlightedId).isEqualTo(4)
        assertThat(state.playingId).isEqualTo(1234)
    }

    @Test fun `auto close uses idle time and resets after interaction`() {
        val state = open()
        assertThat(state.timedOut(10_999, 10)).isFalse()
        assertThat(state.timedOut(11_000, 10)).isTrue()
        assertThat(state.interact(10_000).timedOut(11_000, 10)).isFalse()
        assertThat(state.interact(10_000).timedOut(20_000, 10)).isTrue()
    }

    @Test fun `held remote keys suppress timeout until released`() {
        val state = open().interact(2_000, held = true)
        assertThat(state.timedOut(100_000, 5)).isFalse()
        assertThat(state.interact(100_000).timedOut(104_999, 5)).isFalse()
    }

    @Test fun `off disables timeout and closed overlays cannot time out`() {
        assertThat(open().timedOut(Long.MAX_VALUE, 0)).isFalse()
        assertThat(open().close().timedOut(100_000, 5)).isFalse()
    }

    @Test fun `favourites subset preserves browsing context and original ordering`() {
        val state = open().toggleFavourites(queue, 2_000)
        assertThat(state.channels(queue).map { it.id }).containsExactly(4L, 9L).inOrder()
        assertThat(state.toggleFavourites(queue, 3_000).channels(queue)).containsExactlyElementsIn(queue).inOrder()
    }

    @Test fun `empty favourites cannot retain an invalid selection`() {
        val state = open().toggleFavourites(queue.map { it.copy(favourite = false) }, 2_000)
        assertThat(state.highlightedId).isNull()
        assertThat(state.selection(queue.map { it.copy(favourite = false) })).isNull()
    }

    @Test fun `rapid navigation remains bounded without changing playback or queue ordering`() {
        var state = open()
        repeat(200) { state = state.move(1, queue, 2_000L + it) }
        assertThat(state.highlightedId).isEqualTo(9)
        assertThat(state.playingId).isEqualTo(4)
        repeat(200) { state = state.move(-1, queue, 3_000L + it) }
        assertThat(state.highlightedId).isEqualTo(4)
        assertThat(state.selection(queue)).isNull()
    }

    @Test fun `pip prevents opening and dismissal preserves playback identity`() {
        assertThat(MiniGuideState().open(queue, 4, 1_000, inPip = true).visible).isFalse()
        assertThat(open().close().playingId).isEqualTo(4)
    }

    private fun action(key: PlayerRemoteKey, guide: Boolean = false, picker: Boolean = false, controls: Boolean = false) =
        playerRemoteAction(key, guide, picker, controls)
}
