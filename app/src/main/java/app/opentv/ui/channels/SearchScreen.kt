/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.disabledabel.nuvix.R
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Programme
import app.opentv.data.db.ProgrammeSearchResult
import app.opentv.data.repo.EpgSearchFilter
import app.opentv.data.model.shownName
import app.opentv.data.parser.displayTitle
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.VodViewModel
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Channel search with an on-screen keyboard.
 *
 * Android TV's default text field punts to "type on your phone", which is useless if your phone
 * isn't to hand. This screen draws its own d-pad keyboard so search works with the remote alone,
 * and shows matching channels live as you type.
 */
@Composable
fun SearchScreen(
    onPlayChannel: (Channel) -> Unit,
    onPlayMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onBack: () -> Unit,
    viewModel: ChannelsViewModel = viewModel(),
    vodViewModel: VodViewModel = viewModel(),
    onOpenProgramme: ((Channel, Programme) -> Unit)? = null,
    epgViewModel: EpgSearchViewModel = viewModel(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    var programsMode by rememberSaveable { mutableStateOf(false) }
    var programFilter by rememberSaveable { mutableStateOf(EpgSearchFilter.ALL) }
    val channelResults by viewModel.searchResults.collectAsState()
    val movieResults by vodViewModel.movieResults.collectAsState()
    val seriesResults by vodViewModel.seriesResults.collectAsState()
    val epgState by epgViewModel.state.collectAsState()
    val anyResults = channelResults.isNotEmpty() || movieResults.isNotEmpty() || seriesResults.isNotEmpty()

    LaunchedEffect(query, programsMode, programFilter) {
        viewModel.setSearchQuery(if (programsMode) "" else query)
        vodViewModel.setVodSearchQuery(if (programsMode) "" else query)
        epgViewModel.search(if (programsMode) query else "", programFilter)
    }
    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.nav_search), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(20.dp))
            Text(
                query.ifEmpty { stringResource(R.string.search_type_name) },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                color = if (query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
            )
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SearchChoiceChip(
                selected = !programsMode, onClick = { programsMode = false },
                label = stringResource(R.string.epg_search_catalogue),
            )
            SearchChoiceChip(
                selected = programsMode, onClick = { programsMode = true },
                label = stringResource(R.string.epg_search_programmes),
            )
        }
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxSize()) {
            OnScreenKeyboard(
                onKey = { query = (query + it).take(120) },
                onSpace = { if (query.length < 120) query += " " },
                onBackspace = { query = query.dropLast(1) },
                onClear = { query = "" },
            )

            Spacer(Modifier.width(28.dp))

            Column(Modifier.weight(1f).fillMaxSize()) {
                if (programsMode) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(EpgSearchFilter.entries, key = { it.name }) { filter ->
                            SearchChoiceChip(
                                selected = programFilter == filter,
                                onClick = { programFilter = filter },
                                label = stringResource(epgFilterLabel(filter)),
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.epg_search_limit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        query.isBlank() -> Hint(stringResource(R.string.epg_search_hint))
                        query.trim().length < 2 -> Hint(stringResource(R.string.common_keep_typing))
                        epgState.loading -> Hint(stringResource(R.string.epg_search_loading))
                        epgState.results.isEmpty() -> Hint(stringResource(R.string.epg_search_empty, query))
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(epgState.results, key = { "p${it.programme.id}c${it.channelId}" }) { result ->
                                ProgrammeResultRow(result) {
                                    if (onOpenProgramme != null) onOpenProgramme(result.channel, result.programme)
                                    else onPlayChannel(result.channel)
                                }
                            }
                        }
                    }
                } else {
                    when {
                        query.isBlank() -> Hint(stringResource(R.string.search_start_hint))
                        query.trim().length < 2 -> Hint(stringResource(R.string.common_keep_typing))
                        !anyResults -> Hint(stringResource(R.string.search_no_results, query))
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (channelResults.isNotEmpty()) {
                                item { SectionHeader(stringResource(R.string.common_channels)) }
                                items(channelResults, key = { "c${it.key}" }) { row ->
                                    SearchResultRow(row = row, onClick = { onPlayChannel(row.primary) })
                                }
                            }
                            if (movieResults.isNotEmpty()) {
                                item { SectionHeader(stringResource(R.string.nav_movies)) }
                                items(movieResults, key = { "m${it.id}" }) { movie ->
                                    VodResultRow(movie.displayTitle, movie.posterUrl, movie.year?.toString()) {
                                        onPlayMovie(movie)
                                    }
                                }
                            }
                            if (seriesResults.isNotEmpty()) {
                                item { SectionHeader(stringResource(R.string.nav_shows)) }
                                items(seriesResults, key = { "s${it.id}" }) { show ->
                                    VodResultRow(show.displayTitle, show.posterUrl, show.year?.toString()) {
                                        onOpenSeries(show)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SearchChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        modifier = Modifier.onFocusChanged { focused = it.isFocused }.then(
            if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
            else Modifier,
        ),
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

private fun epgFilterLabel(filter: EpgSearchFilter): Int = when (filter) {
    EpgSearchFilter.ALL -> R.string.epg_search_all
    EpgSearchFilter.CURRENTLY_AIRING -> R.string.epg_search_airing
    EpgSearchFilter.UPCOMING -> R.string.epg_search_upcoming
    EpgSearchFilter.MOVIES -> R.string.epg_search_movies
    EpgSearchFilter.SPORTS -> R.string.epg_search_sports
    EpgSearchFilter.SERIES -> R.string.epg_search_series
}

@Composable
private fun ProgrammeResultRow(result: ProgrammeSearchResult, onClick: () -> Unit) {
    val programme = result.programme
    val status = if (programme.isLiveAt(System.currentTimeMillis())) stringResource(R.string.epg_search_now) else null
    val subtitle = listOfNotNull(
        result.channel.shownName,
        "${programmeSearchDate.format(Date(programme.startUtcMillis))}–${searchTime(programme.endUtcMillis)}",
        status,
        programme.category?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    VodResultRow(programme.title, result.channel.logoUrl, subtitle, onClick)
}

@Composable
private fun VodResultRow(name: String, posterUrl: String?, subtitle: String?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = posterUrl,
            contentDescription = null,
            modifier = Modifier.size(width = 34.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SearchResultRow(row: ChannelsViewModel.Row, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = row.primary.logoUrl,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.primary.shownName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.now?.let { "${searchTime(it.startUtcMillis)}  ${it.title}" } ?: stringResource(R.string.guide_no_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val searchTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val programmeSearchDate = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
private fun searchTime(utcMillis: Long): String = searchTimeFormat.format(Date(utcMillis))
