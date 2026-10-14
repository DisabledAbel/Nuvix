/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import app.opentv.pairing.QrCodes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.disabledabel.nuvix.BuildConfig
import com.disabledabel.nuvix.R
import app.opentv.core.ServiceLocator
import kotlinx.coroutines.launch

/**
 * About: what this is, what version it is, and how to get involved. The update check here is the
 * manual counterpart to the automatic one — someone who just heard a fix landed can pull it now
 * instead of waiting for the periodic check.
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }

        Spacer(Modifier.height(20.dp))

        Section("Nuvix version") {
            Text("Nuvix ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.titleMedium)
            Text("Independent IPTV player and optional television home launcher.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(16.dp))

        Section(stringResource(R.string.about_what_is_title)) {
            Text(
                stringResource(R.string.about_what_is_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        Section("OpenTV attribution") {
            Text("Nuvix is an independent modified version of OpenTV, based on opentvproject/opentv commit 43b95c05d8f6a042f51c0cff662ef80789ee7528. Copyright (C) 2026 The OpenTV Contributors. Nuvix modifications Copyright (C) 2026 Nuvix Contributors.")
            Text("Significant changes include Nuvix branding and navigation, Android TV home launcher and installed apps, optional Supabase synchronization, buffering controls, and verified releases.")
            Text("Licensed under GPL-3.0-or-later. No official affiliation with OpenTV or DIRECTV.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinkLine("Original project", "github.com/opentvproject/opentv")
        }
        Spacer(Modifier.height(16.dp))
        Section("TV metadata") {
            Text("TV metadata by TVmaze, licensed under CC BY-SA 4.0. Summaries are formatted and combined with provider metadata; adapted TVmaze data remains under CC BY-SA 4.0.")
            LinkLine("TVmaze", "www.tvmaze.com")
            LinkLine("Data license", "creativecommons.org/licenses/by-sa/4.0/")
            Text("Secondary TV metadata and IMDb ratings via IMDbAPI.dev. TMDb remains an optional fallback using your own key.")
        }
        Spacer(Modifier.height(16.dp))
        Section("Updates") { app.opentv.update.UpdateSettingsControls() }
        Spacer(Modifier.height(16.dp))
        Section(stringResource(R.string.about_licence_links_title)) {
            LinkLine(
                stringResource(R.string.about_licence_label),
                "GNU General Public License v3.0 or later",
                url = "https://www.gnu.org/licenses/gpl-3.0.html",
            )
            LinkLine(stringResource(R.string.about_source_code), "github.com/DisabledAbel/Nuvix")
            LinkLine(stringResource(R.string.about_report_bug), "github.com/DisabledAbel/Nuvix/issues")
            LinkLine("Nuvix releases", "github.com/DisabledAbel/Nuvix/releases")
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
    Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

/**
 * A tappable link row. Focusable on purpose: on a TV this is what lets the d-pad travel down the
 * About screen (and so scroll it) — a screen of plain Text has nothing to carry the scroll. Clicking
 * opens the URL in a browser, or shows it as a toast where there is no browser (some TV boxes).
 */
@Composable
private fun LinkLine(label: String, value: String, url: String = "https://$value") {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (focused) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                else Modifier,
            )
            .clickable {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { Toast.makeText(context, url, Toast.LENGTH_LONG).show() }
            }
            .padding(vertical = 6.dp, horizontal = 6.dp),
    ) {
        Text(
            "$label:  ",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}
