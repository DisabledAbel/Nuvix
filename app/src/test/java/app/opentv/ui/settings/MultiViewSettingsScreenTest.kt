/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import app.opentv.core.AppSettings
import app.opentv.multiview.MultiViewAudioMode
import app.opentv.multiview.MultiViewChannelReference
import app.opentv.multiview.MultiViewLayout
import app.opentv.multiview.MultiViewPreferences
import app.opentv.multiview.MultiViewPresetStore
import app.opentv.multiview.MultiViewPresentation
import app.opentv.multiview.MultiViewRecoveryPolicy
import app.opentv.multiview.MultiViewSavedPanel
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class MultiViewSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val settings get() = AppSettings.get(context)
    @Before fun reset() { settings.setMultiViewPreferences(MultiViewPreferences()) }

    @Test fun `MultiView preferences are reachable from Display and Playback using remote select`() {
        compose.setContent { MaterialTheme { AppSettingsScreen(onBack = {}) } }
        val entry = compose.onNodeWithText("Multi-View settings")
        entry.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        entry.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Audio switching").assertIsDisplayed()
        val done = compose.onNodeWithText("Done")
        done.performSemanticsAction(SemanticsActions.RequestFocus)
        done.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Multi-View settings").performScrollTo().assertIsDisplayed()
    }

    @Test fun `remote focus does not select audio preference until OK and selection persists`() {
        compose.setContent { MaterialTheme { MultiViewSettingsScreen(onBack = {}) } }
        val mode = compose.onNodeWithText("Press OK to Switch Audio")
        mode.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { assertThat(settings.multiViewPreferences.value.audioMode).isEqualTo(MultiViewAudioMode.FOLLOW_FOCUS) }
        mode.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertThat(settings.multiViewPreferences.value.audioMode).isEqualTo(MultiViewAudioMode.PRESS_OK) }
        val delay = compose.onNodeWithText("300 ms")
        delay.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        delay.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertThat(settings.multiViewPreferences.value.focusDelayMs).isEqualTo(300) }
    }

    @Test fun `disabling unexpected-exit recovery clears checkpoint while preserving presets`() {
        val store = MultiViewPresetStore(context)
        try {
            val presentation = MultiViewPresentation(listOf(
                MultiViewSavedPanel("first", MultiViewChannelReference(1, 1)),
                MultiViewSavedPanel("second", MultiViewChannelReference(2, 1)),
            ), "first", "first", MultiViewLayout.TWO)
            val saved = store.upsert("Saved game", presentation)!!
            store.saveInterrupted(presentation)
            compose.setContent { MaterialTheme { MultiViewSettingsScreen(onBack = {}) } }
            val disable = compose.onNodeWithText("Do not offer recovery")
            disable.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
            disable.performKeyInput { pressKey(Key.DirectionCenter) }
            compose.runOnIdle {
                assertThat(settings.multiViewPreferences.value.recovery).isEqualTo(MultiViewRecoveryPolicy.DISABLED)
                assertThat(store.peekRecovery()).isNull()
                assertThat(store.find(saved.id)).isEqualTo(saved)
            }
        } finally { store.close() }
    }
}
