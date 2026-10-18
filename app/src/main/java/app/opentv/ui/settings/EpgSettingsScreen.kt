/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.disabledabel.nuvix.R
import app.opentv.core.AppSettings
import app.opentv.core.GuideChannelFilter
import app.opentv.core.GuideChannelSort
import app.opentv.core.GuideLayout
import app.opentv.core.GuideTimelineDensity
import app.opentv.data.model.EpgFeed
import app.opentv.ui.EpgViewModel

/**
 * Guide settings: where guide data comes from.
 *
 * Three kinds of feed share the list — the provider's own guide, the curated free sources
 * shipped with the app, and any XMLTV URL the user adds. Automatic configuration also
 * discovers relevant public guides for channels that still have no programme data. Every enabled feed merges into a single guide, and each row shows its last
 * sync outcome, because "the guide silently didn't update" is the failure this whole app
 * exists to avoid.
 */
@Composable
fun EpgSettingsScreen(
    onBack: () -> Unit,
    viewModel: EpgViewModel = viewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    val automatic by viewModel.automaticEpg.collectAsState()
    val context = LocalContext.current
    val settings = remember(context) { AppSettings.get(context) }
    var showAdd by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_guide_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { viewModel.refresh() }, enabled = !ui.syncing) {
                Text(if (ui.syncing) stringResource(R.string.epg_updating) else stringResource(R.string.epg_update_now))
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }

        ui.statusLine?.let { line ->
            Spacer(Modifier.height(8.dp))
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.epg_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 720.dp),
        )
        Spacer(Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "guide_preferences") { GuidePreferencesPanel(settings) }
            item {
                Card {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.epg_automatic_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.epg_automatic_desc), style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(checked = automatic, onCheckedChange = viewModel::setAutomaticEpg)
                    }
                }
            }
            items(ui.feeds, key = { it.id }) { feed ->
                FeedRow(
                    feed = feed,
                    onToggle = { enabled -> viewModel.setEnabled(feed, enabled) },
                    onRemove = { viewModel.remove(feed) },
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                if (!showAdd) {
                    OutlinedButton(onClick = { showAdd = true }) { Text(stringResource(R.string.epg_add_own)) }
                } else {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            OutlinedTextField(
                                value = newName,
                                onValueChange = { newName = it },
                                label = { Text(stringResource(R.string.epg_name_optional)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = newUrl,
                                onValueChange = { newUrl = it },
                                label = { Text(stringResource(R.string.epg_xmltv_url)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        viewModel.addCustom(newName, newUrl)
                                        newName = ""; newUrl = ""; showAdd = false
                                    },
                                    enabled = newUrl.isNotBlank(),
                                ) { Text(stringResource(R.string.epg_add_guide)) }
                                TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.common_cancel)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** All changes use the same local store as the guide and take effect without restarting. */
@Composable
internal fun GuidePreferencesPanel(settings: AppSettings) {
    val layout by settings.guideLayout.collectAsState()
    val density by settings.guideDensity.collectAsState()
    val filter by settings.guideDefaultFilter.collectAsState()
    val sort by settings.guideSort.collectAsState()
    val artwork by settings.showGuideArtwork.collectAsState()
    val progress by settings.showGuideProgress.collectAsState()
    val rememberPosition by settings.rememberGuidePosition.collectAsState()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.advanced_guide_customization), style = MaterialTheme.typography.titleLarge)
            GuidePreferenceChoice(
                stringResource(R.string.advanced_guide_layout), layout, GuideLayout.entries,
                { value -> stringResource(when (value) {
                    GuideLayout.STANDARD -> R.string.advanced_guide_standard
                    GuideLayout.COMPACT -> R.string.advanced_guide_compact
                    GuideLayout.EXPANDED -> R.string.advanced_guide_expanded
                }) }, settings::setGuideLayout,
            )
            GuidePreferenceChoice(
                stringResource(R.string.advanced_guide_filter), filter, GuideChannelFilter.entries,
                { value -> stringResource(when (value) {
                    GuideChannelFilter.ALL -> R.string.advanced_guide_filter_all
                    GuideChannelFilter.FAVOURITES -> R.string.advanced_guide_filter_favourites
                    GuideChannelFilter.RECENT -> R.string.advanced_guide_filter_recent
                    GuideChannelFilter.GROUP -> R.string.advanced_guide_filter_group
                    GuideChannelFilter.PROVIDER -> R.string.advanced_guide_filter_provider
                    GuideChannelFilter.WITH_EPG -> R.string.advanced_guide_filter_epg
                    GuideChannelFilter.AIRING -> R.string.advanced_guide_filter_airing
                }) }, settings::setGuideDefaultFilter,
            )
            if (filter == GuideChannelFilter.GROUP || filter == GuideChannelFilter.PROVIDER) {
                Text(
                    stringResource(R.string.advanced_guide_filter_target_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GuidePreferenceChoice(
                stringResource(R.string.advanced_guide_sort), sort, GuideChannelSort.entries,
                { value -> stringResource(when (value) {
                    GuideChannelSort.PROVIDER_ORDER -> R.string.advanced_guide_sort_provider
                    GuideChannelSort.CHANNEL_NUMBER -> R.string.advanced_guide_sort_number
                    GuideChannelSort.ALPHABETICAL -> R.string.advanced_guide_sort_alpha
                    GuideChannelSort.FAVOURITES_FIRST -> R.string.advanced_guide_sort_favourites
                    GuideChannelSort.CUSTOM -> R.string.advanced_guide_sort_custom
                }) }, settings::setGuideSort,
            )
            GuidePreferenceChoice(
                stringResource(R.string.advanced_guide_density), density, GuideTimelineDensity.entries,
                { value -> stringResource(when (value) {
                    GuideTimelineDensity.COMFORTABLE -> R.string.advanced_guide_density_comfortable
                    GuideTimelineDensity.DENSE -> R.string.advanced_guide_density_dense
                    GuideTimelineDensity.WIDE -> R.string.advanced_guide_density_wide
                }) }, settings::setGuideDensity,
            )
            GuidePreferenceToggle(stringResource(R.string.advanced_guide_artwork), artwork, settings::setShowGuideArtwork)
            GuidePreferenceToggle(stringResource(R.string.advanced_guide_progress), progress, settings::setShowGuideProgress)
            GuidePreferenceToggle(stringResource(R.string.advanced_guide_remember), rememberPosition, settings::setRememberGuidePosition)
        }
    }
}

/** Displays a remote-focusable guide preference with selectable supported values. */
@Composable
private fun <T> GuidePreferenceChoice(
    title: String,
    selected: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    val entryFocus = remember { FocusRequester() }
    val close: () -> Unit = { open = false; restoreFocus = true }
    androidx.compose.runtime.LaunchedEffect(open, restoreFocus) {
        if (!open && restoreFocus) {
            entryFocus.requestFocus()
            restoreFocus = false
        }
    }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().focusRequester(entryFocus).guideFocusIndicator()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            Text(label(selected))
        }
    }
    if (open) {
        // Some TV hosts dispatch Back through the activity, while remote key events can reach
        // the focused Compose child directly. Both routes close this choice and restore entry focus.
        BackHandler(onBack = close)
        val selectedFocus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = close,
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyUp) close()
                    true
                } else false
            },
            title = { Text(title) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    options.forEach { value ->
                        Row(
                            Modifier.fillMaxWidth()
                                .then(if (value == selected) Modifier.focusRequester(selectedFocus) else Modifier)
                                .guideFocusIndicator()
                                .selectable(
                                    selected = value == selected,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(value); close() },
                                ).padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == selected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(label(value), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                androidx.compose.runtime.LaunchedEffect(Unit) { selectedFocus.requestFocus() }
            },
            confirmButton = {
                TextButton(onClick = close) { Text(stringResource(R.string.common_done)) }
            },
        )
    }
}

/** Displays a remote-focusable boolean guide preference. */
@Composable
private fun GuidePreferenceToggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().guideFocusIndicator().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Adds a visible focus indicator to guide preference controls. */
@Composable
private fun Modifier.guideFocusIndicator(): Modifier {
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.isFocused }
        .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
}

/** Displays a configured guide feed with refresh status and management actions. */
@Composable
private fun FeedRow(
    feed: EpgFeed,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Card {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(feed.name, style = MaterialTheme.typography.titleMedium)
                // The last sync outcome, verbatim — success counts or the actual error.
                // A guide that fails must say so where the user can see it.
                if (feed.lastResult.isNotEmpty()) {
                    Text(
                        feed.lastResult,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // User-added feeds can be removed; provider and built-in ones only toggled.
            if (!feed.builtIn && feed.providerSourceId == null) {
                TextButton(onClick = onRemove) { Text(stringResource(R.string.common_remove)) }
                Spacer(Modifier.width(4.dp))
            }
            Switch(checked = feed.enabled, onCheckedChange = onToggle)
        }
    }
}
