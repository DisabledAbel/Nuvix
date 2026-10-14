/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.vod

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import app.opentv.data.remote.*
import coil.compose.AsyncImage
import com.disabledabel.nuvix.R

internal fun plainSummary(value: String?) = value?.let {
    HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
}.orEmpty()

@Composable
internal fun MetadataLink(label: String, url: String) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(context, url, Toast.LENGTH_LONG).show() }
    }) { Text(label) }
}

@Composable
internal fun TvmazeAttribution(url: String) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MetadataLink(stringResource(R.string.tvmaze_source), url)
        MetadataLink(stringResource(R.string.tvmaze_license), "https://creativecommons.org/licenses/by-sa/4.0/")
        Text(stringResource(R.string.tvmaze_changes), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun TvPeople(details: TvmazeDetails, onPerson: (String) -> Unit) {
    if (details.cast.isNotEmpty()) {
        Text(stringResource(R.string.vod_cast), modifier = Modifier.padding(horizontal = 24.dp))
        LazyRow(contentPadding = PaddingValues(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(details.cast, key = { "${it.person.id}:${it.character}" }) { cast ->
                PersonTile(cast.person, cast.character, onPerson)
            }
        }
    }
    if (details.crew.isNotEmpty()) {
        Text(stringResource(R.string.tv_crew), modifier = Modifier.padding(horizontal = 24.dp))
        LazyRow(contentPadding = PaddingValues(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(details.crew, key = { "${it.person.id}:${it.job}" }) { crew ->
                PersonTile(crew.person, crew.job, onPerson)
            }
        }
    }
}

@Composable
private fun PersonTile(person: TvPerson, role: String?, onPerson: (String) -> Unit) {
    OutlinedButton(onClick = { onPerson(person.route) }) {
        Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AsyncImage(person.image, person.name, Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Crop)
            Text(person.name)
            role?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun TvProfileContent(profile: TvProfile) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(profile.person.name, style = MaterialTheme.typography.headlineMedium)
                AsyncImage(profile.person.image, profile.person.name, Modifier.width(180.dp).height(240.dp), contentScale = ContentScale.Crop)
                profile.person.birthday?.let { Text(stringResource(R.string.tv_born, it)) }
                profile.person.deathday?.let { Text(stringResource(R.string.tv_died, it)) }
                profile.person.country?.let { Text(it) }
                Text(stringResource(R.string.tv_credits), style = MaterialTheme.typography.titleLarge)
            }
        }
        items(profile.credits, key = { "${it.show.id}:${it.role}" }) { credit ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AsyncImage(credit.show.poster, null, Modifier.width(80.dp).height(110.dp), contentScale = ContentScale.Crop)
                Column {
                    MetadataLink(listOfNotNull(credit.show.name, credit.show.year?.toString()).joinToString(" · "), credit.show.url)
                    credit.role?.let { Text(it) }
                }
            }
        }
        item { TvmazeAttribution(profile.person.url) }
    }
}
