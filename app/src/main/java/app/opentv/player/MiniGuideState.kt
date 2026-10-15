/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.player

/** Overlay state only. Playback is requested exclusively by [selection]; focus never tunes. */
data class MiniGuideState(
    val visible: Boolean = false,
    val highlightedId: Long? = null,
    val playingId: Long? = null,
    val favouritesOnly: Boolean = false,
    val lastInteractionMillis: Long = 0,
    val keyHeld: Boolean = false,
) {
    fun channels(queue: List<PlaybackQueue.Item>): List<PlaybackQueue.Item> =
        if (favouritesOnly) queue.filter { it.favourite } else queue

    fun open(queue: List<PlaybackQueue.Item>, playing: Long?, now: Long, inPip: Boolean = false): MiniGuideState {
        val rows = channels(queue)
        return copy(
            visible = !inPip,
            highlightedId = rows.firstOrNull { it.isPlaying(playing) }?.id ?: rows.firstOrNull()?.id,
            playingId = playing,
            lastInteractionMillis = now,
            keyHeld = false,
        )
    }

    fun close(): MiniGuideState = copy(visible = false, keyHeld = false)

    fun interact(now: Long, held: Boolean = false): MiniGuideState =
        copy(lastInteractionMillis = now, keyHeld = held)

    fun highlight(id: Long, queue: List<PlaybackQueue.Item>, now: Long): MiniGuideState =
        if (channels(queue).any { it.id == id }) copy(highlightedId = id, lastInteractionMillis = now) else this

    fun move(delta: Int, queue: List<PlaybackQueue.Item>, now: Long): MiniGuideState {
        val rows = channels(queue)
        val index = rows.indexOfFirst { it.id == highlightedId }.coerceAtLeast(0)
        return copy(
            highlightedId = rows.getOrNull((index + delta).coerceIn(0, (rows.size - 1).coerceAtLeast(0)))?.id,
            lastInteractionMillis = now,
        )
    }

    fun toggleFavourites(queue: List<PlaybackQueue.Item>, now: Long): MiniGuideState =
        copy(favouritesOnly = !favouritesOnly).reconcile(queue, playingId).interact(now)

    /** Recover stale highlights when a filter or queue changes; playing can be a quality variant. */
    fun reconcile(queue: List<PlaybackQueue.Item>, playing: Long?): MiniGuideState {
        val rows = channels(queue)
        return copy(
            playingId = playing,
            highlightedId = rows.firstOrNull { it.id == highlightedId }?.id
                ?: rows.firstOrNull { it.isPlaying(playing) }?.id ?: rows.firstOrNull()?.id,
        )
    }

    /** A null result closes the guide without a playback request (including the playing row). */
    fun selection(queue: List<PlaybackQueue.Item>): Long? =
        channels(queue).firstOrNull { visible && it.id == highlightedId && !it.isPlaying(playingId) }?.id

    fun timedOut(now: Long, timeoutSeconds: Int): Boolean =
        visible && !keyHeld && timeoutSeconds > 0 && now - lastInteractionMillis >= timeoutSeconds * 1_000L
}

enum class PlayerRemoteAction { GUIDE, GUIDE_INPUT, PICKER_INPUT, ZAP_UP, ZAP_DOWN, CONTROLS, QUALITY, BACK, NUMBER }
enum class PlayerRemoteKey { LEFT, RIGHT, UP, DOWN, CHANNEL_UP, CHANNEL_DOWN, BACK, NUMBER, OTHER }

/** Shared by the real root key handler and JVM tests, so overlays keep ownership of the d-pad. */
fun playerRemoteAction(
    key: PlayerRemoteKey,
    guideVisible: Boolean,
    pickerVisible: Boolean,
    controlsVisible: Boolean,
): PlayerRemoteAction = when {
    key == PlayerRemoteKey.BACK -> PlayerRemoteAction.BACK
    key == PlayerRemoteKey.NUMBER -> PlayerRemoteAction.NUMBER
    guideVisible -> PlayerRemoteAction.GUIDE_INPUT
    pickerVisible -> PlayerRemoteAction.PICKER_INPUT
    key == PlayerRemoteKey.UP || key == PlayerRemoteKey.CHANNEL_UP -> PlayerRemoteAction.ZAP_UP
    key == PlayerRemoteKey.DOWN || key == PlayerRemoteKey.CHANNEL_DOWN -> PlayerRemoteAction.ZAP_DOWN
    controlsVisible -> PlayerRemoteAction.CONTROLS
    key == PlayerRemoteKey.LEFT -> PlayerRemoteAction.GUIDE
    key == PlayerRemoteKey.RIGHT -> PlayerRemoteAction.QUALITY
    else -> PlayerRemoteAction.CONTROLS
}
