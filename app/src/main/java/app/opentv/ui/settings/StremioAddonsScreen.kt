/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.model.StremioAddon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StremioAddonsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)

    val addons: StateFlow<List<StremioAddon>> = graph.settings.stremioAddons

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data class Added(val name: String) : Status
        data object Invalid : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Validate a pasted manifest URL against the live add-on, and store it if it checks out. */
    fun add(rawUrl: String) {
        val url = rawUrl.trim()
        if (url.isBlank()) return
        viewModelScope.launch {
            _status.value = Status.Checking
            val name = withContext(Dispatchers.IO) { graph.stremioClient.fetchManifest(url) }
            _status.value = if (name != null) {
                graph.settings.addStremioAddon(StremioAddon(manifestUrl = url, name = name))
                Status.Added(name)
            } else {
                Status.Invalid
            }
        }
    }

    fun remove(addon: StremioAddon) = graph.settings.removeStremioAddon(addon.manifestUrl)

    fun clearStatus() { _status.value = Status.Idle }
}

/**
 * Manage Stremio add-ons: paste a manifest URL, it's validated against the live add-on and stored
 * on-device. OpenTV bundles none and points at none — the user brings their own. A short note makes
 * the debrid model explicit: the key lives in the add-on's own personalised URL, not in OpenTV.
 */
@Composable
fun StremioAddonsScreen(onBack: () -> Unit) {
    val viewModel: StremioAddonsViewModel = viewModel()
    val addons by viewModel.addons.collectAsState()
    val status by viewModel.status.collectAsState()
    var input by remember { mutableStateOf("") }

    // Clear the field once an add-on validates and is stored — done as an effect, not during
    // composition, so it's a single clean side effect rather than a write mid-layout.
    LaunchedEffect(status) {
        if (status is StremioAddonsViewModel.Status.Added) input = ""
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_addons_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.addons_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 760.dp),
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                if (status !is StremioAddonsViewModel.Status.Idle) viewModel.clearStatus()
            },
            label = { Text(stringResource(R.string.addons_url_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
        )

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    viewModel.add(input)
                },
                enabled = input.isNotBlank() && status !is StremioAddonsViewModel.Status.Checking,
            ) { Text(stringResource(R.string.addons_add)) }
            Spacer(Modifier.width(14.dp))
            when (val s = status) {
                is StremioAddonsViewModel.Status.Checking -> {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.addons_checking), style = MaterialTheme.typography.bodyMedium)
                }
                is StremioAddonsViewModel.Status.Added ->
                    Text(
                        stringResource(R.string.addons_added, s.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                is StremioAddonsViewModel.Status.Invalid ->
                    Text(
                        stringResource(R.string.addons_invalid),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                StremioAddonsViewModel.Status.Idle -> {}
            }
        }

        Spacer(Modifier.height(20.dp))

        if (addons.isEmpty()) {
            Text(
                stringResource(R.string.addons_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.widthIn(max = 760.dp),
            ) {
                items(addons, key = { it.manifestUrl }) { addon ->
                    AddonRow(addon = addon, onRemove = { viewModel.remove(addon) })
                }
            }
        }
    }
}

@Composable
private fun AddonRow(addon: StremioAddon, onRemove: () -> Unit) {
    Card {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(addon.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    hostOf(addon.manifestUrl),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRemove) { Text(stringResource(R.string.common_remove)) }
        }
    }
}

/** Host only — a personalised manifest URL can embed a key, which has no place on a settings list. */
private fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
