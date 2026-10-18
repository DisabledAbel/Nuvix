/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.net.URI

/** Only supplied catalog artwork is loaded; no metadata lookup or URL is exposed to the UI. */
internal fun programmeArtworkModel(url: String?): String? = url?.trim()?.takeIf { value ->
    runCatching { URI(value).let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() } }.getOrDefault(false)
}

/** Displays validated program artwork with a local fallback for missing or failed images. */
@Composable
internal fun ProgrammeArtwork(artworkUrl: String?, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.LiveTv, contentDescription = "Program artwork", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        val imageWidth = with(density) { maxWidth.roundToPx() }.coerceIn(1, 640)
        val imageHeight = with(density) { maxHeight.roundToPx() }.coerceIn(1, 360)
        programmeArtworkModel(artworkUrl)?.let { model ->
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(model).size(imageWidth, imageHeight).crossfade(false).build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
