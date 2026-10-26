/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.opentv.player.PlayerSurfaceOwner
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercise the actual Media3 view adapter without opening a media item or network connection. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PlayerSurfaceOwnerTest {
    @Test fun `same fullscreen view attaches replacement player after constrained stream release`() {
        val context = RuntimeEnvironment.getApplication()
        val first = ExoPlayer.Builder(context).build()
        val replacement = ExoPlayer.Builder(context).build()
        var active = first
        val owner = PlayerSurfaceOwner { active }
        val view = PlayerView(context)
        try {
            assertThat(owner.attach(view)).isTrue()
            owner.clear()
            first.release()
            active = replacement
            owner.reset()
            // AndroidView.update must run this for the retained View after engine replacement.
            assertThat(owner.attach(view)).isTrue()
            assertThat(view.player).isSameInstanceAs(replacement)
            assertThat(owner.attach(view)).isTrue()
            assertThat(replacement.mediaItemCount).isEqualTo(0)
        } finally {
            owner.clear()
            first.release()
            replacement.release()
        }
    }
    /**
     * Checks: fullscreen preview and recreated Activity views transfer one player without changing
     * intent.
     */
    @Test
    fun `fullscreen preview and recreated Activity views transfer one player without changing intent`() {
        val context = RuntimeEnvironment.getApplication()
        val player = ExoPlayer.Builder(context).build()
        try {
            val tracks = player.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage("de")
                .setPreferredTextLanguage("es")
                .build()
            player.trackSelectionParameters = tracks
            player.playWhenReady = false
            player.volume = 0.35f
            val owner = PlayerSurfaceOwner { player }
            val fullscreen = PlayerView(context)
            val preview = PlayerView(context)
            val recreatedFullscreen = PlayerView(context)

            assertThat(owner.attach(fullscreen)).isTrue()
            assertThat(fullscreen.player).isSameInstanceAs(player)
            assertThat(owner.attach(preview)).isTrue()
            assertThat(fullscreen.player).isNull()
            assertThat(preview.player).isSameInstanceAs(player)

            // Navigation disposal can arrive after the replacement already owns the surface.
            owner.detach(fullscreen)
            assertThat(preview.player).isSameInstanceAs(player)
            assertThat(owner.attach(recreatedFullscreen)).isTrue()
            owner.detach(preview)
            assertThat(preview.player).isNull()
            assertThat(recreatedFullscreen.player).isSameInstanceAs(player)
            assertThat(player.trackSelectionParameters).isEqualTo(tracks)
            assertThat(player.playWhenReady).isFalse()
            assertThat(player.volume).isEqualTo(0.35f)
            assertThat(player.mediaItemCount).isEqualTo(0)

            owner.clear()
            assertThat(recreatedFullscreen.player).isNull()
            owner.clear()
        } finally {
            player.release()
        }
    }

    /** Checks: missing session surface falls back without creating or retaining a player. */
    @Test
    fun `missing session surface falls back without creating or retaining a player`() {
        val context = RuntimeEnvironment.getApplication()
        val view = PlayerView(context)
        val owner = PlayerSurfaceOwner { null }

        assertThat(owner.attach(view)).isFalse()
        assertThat(view.player).isNull()
        owner.detach(view)
        owner.clear()
        assertThat(view.player).isNull()
    }
}
