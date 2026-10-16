/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.home

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.delay

internal data class HomeTileStyle(val presentation: HomePresentation = HomePresentation(), val onArtwork: (String?) -> Unit = {})
internal val LocalHomeTileStyle = staticCompositionLocalOf { HomeTileStyle() }

internal fun homeCardWidth(layout: HomeLayout, size: HomeCardSize): Int {
    val base = when (size) { HomeCardSize.SMALL -> 150; HomeCardSize.MEDIUM -> 190; HomeCardSize.LARGE -> 230 }
    return when (layout) { HomeLayout.COMPACT -> base - 30; HomeLayout.CINEMA -> base + 30; HomeLayout.CLASSIC -> base }
}

internal enum class HomeFeatureSource { NONE, CONTINUE, RECENT, FAVOURITE, CHANNEL }
internal fun selectHomeFeature(preference: HomeFeatured, resume: Boolean, recent: Boolean, favourite: Boolean, channels: Boolean): HomeFeatureSource = when (preference) {
    HomeFeatured.DISABLED -> HomeFeatureSource.NONE
    HomeFeatured.CONTINUE -> if (resume) HomeFeatureSource.CONTINUE else HomeFeatureSource.NONE
    HomeFeatured.RECENT -> if (recent) HomeFeatureSource.RECENT else HomeFeatureSource.NONE
    HomeFeatured.FAVOURITE -> if (favourite) HomeFeatureSource.FAVOURITE else HomeFeatureSource.NONE
    HomeFeatured.AUTOMATIC -> when {
        recent -> HomeFeatureSource.RECENT
        favourite -> HomeFeatureSource.FAVOURITE
        channels -> HomeFeatureSource.CHANNEL
        else -> HomeFeatureSource.NONE
    }
}
internal fun backgroundArtwork(background: HomeBackground, artwork: String?): String? =
    artwork?.takeIf { background == HomeBackground.DYNAMIC_ARTWORK && it.isNotBlank() }

/** One bounded request, debounced focus, and the last successful painter retained during loading. */
@Composable
internal fun HomeBackdrop(background: HomeBackground, artwork: State<String?>, active: Boolean) {
    val context = LocalContext.current
    val candidate = backgroundArtwork(background, artwork.value)
    var requested by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(candidate, active) {
        if (candidate == null) requested = null
        else if (active) { delay(180); requested = candidate }
    }
    val request = remember(requested) { ImageRequest.Builder(context).data(requested)
        .size(640, 360).scale(coil.size.Scale.FIT).precision(coil.size.Precision.EXACT)
        .memoryCacheKey("home-background:$requested").bitmapConfig(Bitmap.Config.RGB_565).allowHardware(false).build() }
    val painter = rememberAsyncImagePainter(request)
    var displayed by remember { mutableStateOf<Painter?>(null) }
    val imageState = painter.state
    LaunchedEffect(requested, imageState) {
        displayed = when {
            requested == null || imageState is AsyncImagePainter.State.Error -> null
            imageState is AsyncImagePainter.State.Success -> imageState.painter
            else -> displayed
        }
    }
    val base = MaterialTheme.colorScheme.background
    val colors = when (background) {
        HomeBackground.MIDNIGHT -> listOf(Color(0xFF06101F), Color(0xFF06101F))
        HomeBackground.CHARCOAL -> listOf(Color(0xFF16181D), Color(0xFF16181D))
        HomeBackground.FOREST -> listOf(Color(0xFF071C16), Color(0xFF071C16))
        HomeBackground.OCEAN -> listOf(Color(0xFF102E48), Color(0xFF06101F))
        HomeBackground.DUSK -> listOf(Color(0xFF2B193D), Color(0xFF0C1427))
        else -> listOf(base, base)
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(colors))) {
        if (background == HomeBackground.DYNAMIC_ARTWORK) {
            val reducedMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            Crossfade(displayed, animationSpec = tween(if (reducedMotion) 0 else 250), label = "Home artwork") { image ->
                if (image != null) Box(Modifier.fillMaxSize()) {
                    Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Color(0xFF030A14).copy(alpha = .84f)))
                }
            }
        }
    }
}
