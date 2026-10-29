/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv.ui.player

import android.app.Application
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit
import androidx.annotation.OptIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.opentv.core.AppSettings
import app.opentv.player.*
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Regression tests for stream information. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land")
@kotlin.OptIn(ExperimentalTestApi::class)
class StreamInformationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var settings: AppSettings
    /** Creates isolated fixtures before each test. */
    @Before fun reset() {
        settings = AppSettings.get(RuntimeEnvironment.getApplication())
        settings.setDiagnosticsEnabled(true); settings.setDiagnosticsDetailed(false); settings.setDiagnosticsTechnical(true)
    }
    /** Sends a remote Select key to the focused Compose control matching the label. */
    private fun select(label: String) {
        compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText(label).performKeyInput { pressKey(Key.DirectionCenter) }
    }
    /** Checks: compact and detailed honor technical preference and mark unsupported values. */
    @Test fun `compact and detailed honor technical preference and mark unsupported values`() {
        val rows = diagnosticRows(DiagnosticSnapshot(), false, true).toMap()
        assertThat(rows["Resolution"]).isEqualTo("Unavailable")
        assertThat(rows).doesNotContainKey("Audio codec")
        val detailed = diagnosticRows(DiagnosticSnapshot(), true, true).toMap()
        assertThat(detailed["Video bitrate (measured)"]).isEqualTo("Unavailable")
        assertThat(detailed["Available bandwidth (estimate)"]).isEqualTo("Unavailable")
        assertThat(diagnosticRows(DiagnosticSnapshot(), true, false).toMap()).doesNotContainKey("Audio codec")
    }
    @Test fun `recovery-only diagnostics have unique row keys and work without a controller`() {
        val budget = RecoveryBudget()
        repeat(3) { budget.record(RecoveryEvent.STARTED, it.toLong()) }
        budget.publish(RecoveryState.FAILED, 3, RecoveryFailure.NETWORK)
        settings.setDiagnosticsDetailed(true)
        val snapshot = DiagnosticSnapshot(state = "Failed", recovery = budget.snapshot.value)
        val rows = diagnosticRows(snapshot, true, true)
        assertThat(rows.map { it.first }.distinct()).hasSize(rows.size)
        assertThat(rows.filter { it.first.startsWith("Recovery event") }.map { it.first })
            .containsExactly("Recovery event 1", "Recovery event 2", "Recovery event 3").inOrder()
        var cleared = false
        var closed = false
        compose.setContent { MaterialTheme {
            StreamInformation(snapshot, onClear = { budget.clearHistory(); cleared = true }, onClose = { closed = true })
        } }
        compose.onNodeWithText("Stream Information").assertExists()
        compose.onNodeWithText("Close").assertIsFocused()
        select("Clear")
        assertThat(cleared).isTrue()
        assertThat(budget.snapshot.value.history).isEmpty()
        select("Close")
        assertThat(closed).isTrue()
    }

    /** Checks: hidden until opened focus close and back never mutate playback or add listeners. */
    @Test fun `hidden until opened focus close and back never mutate playback or add listeners`() {
        val fake = DiagnosticPlayer().apply { state = Player.STATE_READY; playing = true }
        val observer = PlaybackDiagnostics(fake.player)
        compose.setContent {
            var visible by remember { mutableStateOf(false) }
            MaterialTheme {
                Button(onClick = { visible = true }) { Text("Open diagnostics") }
                if (visible) StreamInformation(observer) { visible = false }
            }
        }
        compose.onNodeWithText("Stream Information").assertDoesNotExist()
        select("Open diagnostics")
        compose.onNodeWithText("Close").assertIsFocused()
        compose.onNodeWithText("Close").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("Clear").assertIsFocused()
        select("View: Compact")
        compose.onNodeWithText("View: Detailed").assertExists()
        select("Close")
        compose.onNodeWithText("Stream Information").assertDoesNotExist()
        compose.runOnIdle {
            assertThat(fake.samples).isGreaterThan(0)
            val samples = fake.samples
            shadowOf(Looper.getMainLooper()).idleFor(2100, TimeUnit.MILLISECONDS)
            assertThat(fake.samples).isEqualTo(samples)
        }
        select("Open diagnostics")
        compose.onNodeWithText("Close").performKeyInput { pressKey(Key.Back) }
        compose.onNodeWithText("Stream Information").assertDoesNotExist()
        assertThat(fake.playing).isTrue()
        assertThat(fake.listeners).hasSize(1)
        assertThat(fake.analytics).hasSize(1)
        compose.runOnIdle { observer.close() }
        assertThat(fake.listeners).isEmpty()
    }
}
