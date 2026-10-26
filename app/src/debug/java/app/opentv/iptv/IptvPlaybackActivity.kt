/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.iptv

import android.app.Activity
import android.os.Bundle
import androidx.media3.ui.PlayerView

/** Debug-only surface host. The instrumentation owns the actual production live session. */
class IptvPlaybackActivity : Activity() {
    lateinit var video: PlayerView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        video = PlayerView(this).apply { useController = false }
        setContentView(video)
    }
}
