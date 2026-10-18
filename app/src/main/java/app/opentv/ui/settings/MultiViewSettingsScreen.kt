/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.opentv.core.AppSettings
import app.opentv.multiview.MultiViewAudioMode
import app.opentv.multiview.MultiViewPresetStore
import app.opentv.multiview.MultiViewRecoveryPolicy
import app.opentv.multiview.MultiViewRestorePolicy

/** Nested under Display & Playback; every action is reachable with a TV remote. */
@Composable
fun MultiViewSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember(context) { AppSettings.get(context) }
    val preferences by settings.multiViewPreferences.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Multi-View", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text("Done") }
        }
        Text("Settings → Display & Playback → Multi-View", style = MaterialTheme.typography.bodyMedium)
        SettingCard {
            PreferenceToggle("Multi-View enabled", "Additional streams respect provider allowances and device capacity.", preferences.enabled) {
                settings.setMultiViewPreferences(preferences.copy(enabled = it))
            }
        }
        SettingCard {
            Text("Audio switching", style = MaterialTheme.typography.titleMedium)
            PreferenceChoice("Follow Focus", preferences.audioMode == MultiViewAudioMode.FOLLOW_FOCUS) {
                settings.setMultiViewPreferences(preferences.copy(audioMode = MultiViewAudioMode.FOLLOW_FOCUS))
            }
            PreferenceChoice("Press OK to Switch Audio", preferences.audioMode == MultiViewAudioMode.PRESS_OK) {
                settings.setMultiViewPreferences(preferences.copy(audioMode = MultiViewAudioMode.PRESS_OK))
            }
            Text("Focus delay: ${preferences.focusDelayMs} ms", style = MaterialTheme.typography.titleMedium)
            listOf(0, 150, 300, 500).forEach { delay ->
                PreferenceChoice("$delay ms", preferences.focusDelayMs == delay) {
                    settings.setMultiViewPreferences(preferences.copy(focusDelayMs = delay))
                }
            }
        }
        SettingCard {
            Text("Returning from fullscreen", style = MaterialTheme.typography.titleMedium)
            listOf(
                MultiViewRestorePolicy.ASK_FIRST to "Ask First",
                MultiViewRestorePolicy.RESTORE to "Restore after checking resources",
                MultiViewRestorePolicy.KEEP_FULLSCREEN to "Keep only the fullscreen channel",
            ).forEach { (policy, label) ->
                PreferenceChoice(label, preferences.fullscreenRestoration == policy) {
                    settings.setMultiViewPreferences(preferences.copy(fullscreenRestoration = policy))
                }
            }
            Text("Unknown connection allowances still require approval for each additional stream.", style = MaterialTheme.typography.bodyMedium)
        }
        SettingCard {
            PreferenceToggle("Buffering alternative suggestions", "Offer available broadcasts after repeated playback stalls.", preferences.bufferingSuggestions) {
                settings.setMultiViewPreferences(preferences.copy(bufferingSuggestions = it))
            }
            PreferenceToggle("Compact Guide Multi-View", "Keep the existing panels above the Guide while browsing.", preferences.compactGuide) {
                settings.setMultiViewPreferences(preferences.copy(compactGuide = it))
            }
            PreferenceToggle("Channel and provider labels", "Show readable identities on video panels.", preferences.showLabels) {
                settings.setMultiViewPreferences(preferences.copy(showLabels = it))
            }
            PreferenceToggle("Save presentation preferences", "Remember layout appearance with saved presentation state.", preferences.savePresentationPreferences) {
                settings.setMultiViewPreferences(preferences.copy(savePresentationPreferences = it))
            }
        }
        SettingCard {
            Text("Recovery after unexpected exit", style = MaterialTheme.typography.titleMedium)
            PreferenceChoice("Ask Before Restore", preferences.recovery == MultiViewRecoveryPolicy.ASK_BEFORE_RESTORE) {
                settings.setMultiViewPreferences(preferences.copy(recovery = MultiViewRecoveryPolicy.ASK_BEFORE_RESTORE))
            }
            PreferenceChoice("Do not offer recovery", preferences.recovery == MultiViewRecoveryPolicy.DISABLED) {
                settings.setMultiViewPreferences(preferences.copy(recovery = MultiViewRecoveryPolicy.DISABLED))
                MultiViewPresetStore(context).let { store -> store.clearRecovery(); store.close() }
            }
            Text("A recovery review never starts streams until you confirm. Saved presets remain available.", style = MaterialTheme.typography.bodyMedium)
        }
        Text("Provider connection limits are configured in each provider's Connection Management settings.", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun PreferenceChoice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PreferenceToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
