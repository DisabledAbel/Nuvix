/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.settings

import android.app.Application
import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.espresso.Espresso
import app.opentv.core.AppSettings
import app.opentv.core.GuideChannelSort
import app.opentv.core.GuideLayout
import app.opentv.core.GuideTimelineDensity
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Regression tests for guide preferences ui. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land")
@OptIn(ExperimentalTestApi::class)
class GuidePreferencesUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var settings: AppSettings

    /** Creates isolated fixtures before each test. */
    @Before fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = AppSettings::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { GuidePreferencesPanel(settings) }
            }
        }
    }

    /** Opens the settings or customization UI required by the test. */
    private fun open(title: String) {
        val entry = compose.onNode(hasText(title) and hasClickAction())
        entry.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        entry.performKeyInput { pressKey(Key.DirectionCenter) }
    }

    /** Selects the labeled guide preference option using remote-control input. */
    private fun choose(label: String) {
        val option = compose.onNode(hasText(label) and hasAnyAncestor(isDialog()))
        option.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        option.performKeyInput { pressKey(Key.DirectionCenter) }
    }

    /** Checks: remote Switches All Layouts Immediately And Restores Entry Focus. */
    @Test fun remoteSwitchesAllLayoutsImmediatelyAndRestoresEntryFocus() {
        open("Guide layout")
        compose.onNode(hasText("Standard") and hasAnyAncestor(isDialog())).assertIsFocused()
        compose.onNode(isFocused() and hasAnyAncestor(isDialog())).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionCenter)
        }
        compose.runOnIdle { assertThat(settings.guideLayout.value).isEqualTo(GuideLayout.COMPACT) }
        compose.onNode(hasText("Guide layout") and hasClickAction()).assertIsFocused()
        open("Guide layout")
        choose("Expanded")
        compose.runOnIdle { assertThat(settings.guideLayout.value).isEqualTo(GuideLayout.EXPANDED) }
        open("Guide layout")
        choose("Standard")
        compose.runOnIdle { assertThat(settings.guideLayout.value).isEqualTo(GuideLayout.STANDARD) }
        compose.onNode(hasText("Guide layout") and hasClickAction()).assertIsFocused()
    }

    /** Checks: back Closes Choices And Remote Reaches Density Sort And Visibility. */
    @Test fun backClosesChoicesAndRemoteReachesDensitySortAndVisibility() {
        open("Channel sorting")
        choose("Alphabetical")
        compose.runOnIdle { assertThat(settings.guideSort.value).isEqualTo(GuideChannelSort.ALPHABETICAL) }
        open("Timeline density")
        choose("More time visible")
        compose.runOnIdle { assertThat(settings.guideDensity.value).isEqualTo(GuideTimelineDensity.DENSE) }
        open("Timeline density")
        compose.onNode(hasText("More time visible") and hasAnyAncestor(isDialog())).assertIsFocused()
        Espresso.pressBack()
        // Wait for native window disposal as well as Compose idleness before checking absence.
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()
        }
        compose.onNode(hasText("Timeline density") and hasClickAction()).assertIsFocused()
        compose.onNode(isDialog()).assertDoesNotExist()

        listOf("Program artwork", "Program progress indicators", "Remember last guide position").forEach { label ->
            val toggle = compose.onNodeWithText(label)
            toggle.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
            toggle.performKeyInput { pressKey(Key.DirectionCenter) }
        }
        compose.runOnIdle {
            assertThat(settings.showGuideArtwork.value).isFalse()
            assertThat(settings.showGuideProgress.value).isFalse()
            assertThat(settings.rememberGuidePosition.value).isFalse()
        }
    }

    /** Checks: remote Back Closes The Focused Choice And Restores Its Settings Entry. */
    @Test fun remoteBackClosesTheFocusedChoiceAndRestoresItsSettingsEntry() {
        open("Guide layout")
        val selected = compose.onNode(hasText("Standard") and hasAnyAncestor(isDialog()))
        selected.assertIsFocused()
        selected.performKeyInput { pressKey(Key.Back) }
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNode(hasText("Guide layout") and hasClickAction()).assertIsFocused()
        compose.runOnIdle { assertThat(settings.guideLayout.value).isEqualTo(GuideLayout.STANDARD) }
    }
}
