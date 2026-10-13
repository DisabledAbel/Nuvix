/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.parser.displayTitle
import app.opentv.data.repo.PersonTitle
import app.opentv.ui.VodViewModel

/**
 * Everything in the library featuring one person — the Plex-style payoff of tapping a cast member or
 * the director on a detail page. Movies (they act in or directed) and series (they act in) come back
 * merged from [VodViewModel.titlesWithPerson] and are shown as one poster grid; a click opens that
 * title's own detail page. Reuses [PosterCard], so focus, scaling and rating badges match the rest
 * of the VOD surfaces.
 */
@Composable
fun PersonScreen(
    name: String,
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onBack: () -> Unit,
    viewModel: VodViewModel = viewModel(),
) {
    // null = still loading; empty = nothing matched. Keyed on name so a hop to another person reloads.
    var titles by remember(name) { mutableStateOf<List<PersonTitle>?>(null) }
    LaunchedEffect(name) { titles = viewModel.titlesWithPerson(name) }
    BackHandler { onBack() }

    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.vod_person_title, name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
        )

        // Weighted so the grid/empty state fills the space under the fixed header, exactly — the same
        // remaining-space pattern the Movies/Shows home uses.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val list = titles) {
                null -> Centered { CircularProgressIndicator() }
                else -> if (list.isEmpty()) {
                    Centered {
                        Text(
                            stringResource(R.string.vod_person_empty, name),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 140.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        gridItems(list, key = { it.gridKey() }) { item ->
                            when (item) {
                                is PersonTitle.MovieItem -> PosterCard(
                                    title = item.movie.displayTitle,
                                    posterUrl = item.movie.posterUrl,
                                    subtitle = item.movie.year?.toString(),
                                    rating = item.movie.rating,
                                    onClick = { onOpenMovie(item.movie) },
                                )
                                is PersonTitle.SeriesItem -> PosterCard(
                                    title = item.series.displayTitle,
                                    posterUrl = item.series.posterUrl,
                                    subtitle = item.series.year?.toString(),
                                    rating = item.series.rating,
                                    onClick = { onOpenSeries(item.series) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Grid key that stays unique across the two tables (a movie and a series can share a row id). */
private fun PersonTitle.gridKey(): String = when (this) {
    is PersonTitle.MovieItem -> "m${movie.id}"
    is PersonTitle.SeriesItem -> "s${series.id}"
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        content()
    }
}
