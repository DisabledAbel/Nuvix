/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.channels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import app.opentv.channels.*
import com.disabledabel.nuvix.R

/** Read-only metadata review; each action requires OK and every control is reachable by D-pad. */
@Composable
fun EnglishGroupsScreen(
    state: EnglishGroupState,
    onScan: () -> Unit,
    onToggle: (ProviderGroupKey) -> Unit,
    onSelectLikely: () -> Unit,
    onClearSelection: () -> Unit,
    onApply: () -> Unit,
    onClearFilter: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val scanFocus = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }
    val sections = remember(state.suggestions) { state.suggestions.groupBy { it.classification.confidence } }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.english_groups_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.english_groups_explanation), style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LazyColumn(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    // Keep this control mounted and focusable while scanning; repeated OK is ignored.
                    ReviewAction(R.string.english_groups_scan, onScan, modifier = Modifier.focusRequester(scanFocus))
                    // Lazy children attach during measurement, after the parent composition.
                    LaunchedEffect(Unit) {
                        if (!initialFocusDone) {
                            scanFocus.requestFocus()
                            initialFocusDone = true
                        }
                    }
                }
                item { ReviewAction(R.string.english_groups_select_likely, onSelectLikely, state.scanned) }
                item { ReviewAction(R.string.english_groups_clear_selection, onClearSelection, state.selected.isNotEmpty()) }
                item { ReviewAction(R.string.english_groups_apply, onApply, state.scanned && state.selected.isNotEmpty() && !state.scanning) }
                item { ReviewAction(R.string.english_groups_clear_filter, onClearFilter, state.activeFilter != null) }
                item { ReviewAction(R.string.english_groups_cancel, onBack) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(stringResource(R.string.english_groups_selection_count, state.selected.size), style = MaterialTheme.typography.titleMedium)
                    if (state.activeFilter != null) Text(stringResource(R.string.english_groups_filter_active))
                    if (state.catalogChanged) Text(stringResource(R.string.english_groups_catalog_changed))
                    when {
                        state.scanning -> Text(stringResource(R.string.english_groups_scanning))
                        !state.scanned -> Text(stringResource(R.string.english_groups_not_scanned))
                        state.suggestions.isEmpty() -> Text(stringResource(R.string.english_groups_empty))
                        state.suggestions.none { it.classification.confidence == EnglishConfidence.LIKELY } -> Text(stringResource(R.string.english_groups_none_likely))
                    }
                }
                if (state.scanned) EnglishConfidence.entries.forEach { confidence ->
                    item(key = "section-$confidence") {
                        Text(stringResource(confidence.titleResource()), style = MaterialTheme.typography.titleLarge)
                    }
                    val suggestions = sections[confidence].orEmpty()
                    if (suggestions.isEmpty()) item(key = "empty-$confidence") { Text(stringResource(R.string.english_groups_empty_section)) }
                    items(suggestions, key = { "${it.group.key.sourceId}:${it.group.key.categoryId}" }) { suggestion ->
                        SuggestionRow(suggestion, suggestion.group.key in state.selected) { onToggle(suggestion.group.key) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewAction(label: Int, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth()
        .onFocusChanged { focused = it.isFocused }
        .border(if (focused) 3.dp else 0.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))) {
        Text(stringResource(label))
    }
}

@Composable
private fun SuggestionRow(suggestion: EnglishGroupSuggestion, selected: Boolean, onToggle: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val group = suggestion.group
    Row(Modifier.fillMaxWidth()
        .onFocusChanged { focused = it.isFocused }
        .border(if (focused) 3.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
        .background(if (selected || focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
        .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
        .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = selected, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(group.providerName.take(EnglishGroupDetector.MAX_NAME_LENGTH), style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(group.originalName.take(EnglishGroupDetector.MAX_NAME_LENGTH).ifBlank { stringResource(R.string.english_groups_unnamed) }, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.english_groups_channel_count, group.channelCount))
            Text(stringResource(suggestion.classification.confidence.titleResource()))
            Text(stringResource(when (suggestion.classification.evidence) {
                EnglishEvidence.EXPLICIT_LABEL -> R.string.english_groups_reason_explicit
                EnglishEvidence.MIXED_LANGUAGES -> R.string.english_groups_reason_mixed
                EnglishEvidence.AMBIGUOUS_CONTEXT -> R.string.english_groups_reason_ambiguous
                EnglishEvidence.NO_EVIDENCE -> R.string.english_groups_reason_unknown
                EnglishEvidence.UNUSABLE_NAME -> R.string.english_groups_reason_unusable
            }))
            Text(stringResource(if (selected) R.string.english_groups_selected else R.string.english_groups_unselected))
        }
    }
}

private fun EnglishConfidence.titleResource(): Int = when (this) {
    EnglishConfidence.LIKELY -> R.string.english_groups_likely
    EnglishConfidence.REVIEW -> R.string.english_groups_review
    EnglishConfidence.UNKNOWN -> R.string.english_groups_unknown
}
