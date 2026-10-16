/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.channels

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.shownName
import com.disabledabel.nuvix.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Metadata rendering also works for sparse or malformed imported guide rows. */
@Composable
internal fun ProgrammeDetailsMetadata(programme: Programme, channel: Channel, status: GuideProgrammeStatus, showArtwork: Boolean) {
    Text(programme.title.ifBlank { stringResource(R.string.guide_details_title) },
        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(4.dp))
    Text(if (status == GuideProgrammeStatus.INVALID) channel.shownName else
        "${formatProgrammeTime(programme.startUtcMillis)}–${formatProgrammeTime(programme.endUtcMillis)}   ${channel.shownName}",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(when (status) {
        GuideProgrammeStatus.INVALID -> stringResource(R.string.guide_details_timing_unavailable)
        else -> "${programme.durationMillis / 60_000} min · " + stringResource(when (status) {
            GuideProgrammeStatus.AIRING -> R.string.guide_details_airing
            GuideProgrammeStatus.UPCOMING -> R.string.guide_details_upcoming
            else -> R.string.guide_details_ended
        })
    }, style = MaterialTheme.typography.labelLarge)
    programme.category?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    val episode = listOfNotNull(programme.season?.takeIf { it >= 0 }?.let { "Season $it" },
        programme.episode?.takeIf { it >= 0 }?.let { "Episode $it" }).joinToString(" · ")
    if (episode.isNotEmpty()) Text(episode, style = MaterialTheme.typography.bodyMedium)
    if (showArtwork) ProgrammeArtwork(programme.iconUrl, Modifier.fillMaxWidth().height(120.dp))
    Text(programme.description?.takeIf { it.isNotBlank() } ?: stringResource(R.string.guide_details_no_description),
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
}

private fun formatProgrammeTime(millis: Long) = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date(millis))
