/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.home

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land")
@OptIn(ExperimentalTestApi::class)
class HomeCustomizationUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var store: HomePreferencesStore
    private var plays = 0

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(HomePreferencesStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        store = HomePreferencesStore(context)
        compose.setContent {
            val preferences by store.preferences.collectAsState()
            MaterialTheme {
                CompositionLocalProvider(LocalHomeTileStyle provides HomeTileStyle(preferences.presentation)) {
                    Column {
                        HomeCustomizationAccess(preferences, store)
                        ContentTile("Current content", null, "Secondary metadata") { plays++ }
                    }
                }
            }
        }
    }
    @After fun close() { store.close() }

    private fun select(label: String) {
        val node = compose.onNodeWithText(label)
        if (label !in setOf("Customize home", "Move up", "Move down", "Hide section", "Restore section", "Back to customization", "Done")) node.performScrollTo()
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText(label).performKeyInput { pressKey(Key.DirectionCenter) }
    }
    private fun open() = select("Customize home")

    @Test fun remoteMovesShelfWithoutLosingControlFocusAndBackRestoresEntryFocus() {
        open()
        select("Rearrange Sections")
        select("Move down")
        compose.runOnIdle { assertThat(store.currentPreferences.shelfOrder.take(2)).containsExactly("recent", "continue").inOrder() }
        compose.onNodeWithText("Move down").assertIsFocused()
        select("Hide section")
        compose.runOnIdle { assertThat(store.currentPreferences.isVisible(HomeSection.CONTINUE)).isFalse() }
        select("Restore section")
        select("Move up")
        compose.onNodeWithText("Move up").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Move down").assertIsFocused()
        Espresso.pressBack()
        compose.onNodeWithText("Back to customization").assertDoesNotExist()
        Espresso.pressBack()
        compose.onNodeWithText("Customize home").assertIsFocused()
    }

    @Test fun directionalNavigationReachesLayoutOptionsAndBackWithoutTouch() {
        open()
        compose.onNodeWithText("Done").assertIsFocused()
        repeat(10) { compose.onNode(isFocused() and hasAnyAncestor(isDialog())).performKeyInput { pressKey(Key.DirectionUp) } }
        compose.onNodeWithText("Home Layout").assertIsFocused()
        compose.onNode(isFocused() and hasAnyAncestor(isDialog())).performKeyInput { pressKey(Key.DirectionCenter) }
        repeat(5) { compose.onNode(isFocused() and hasAnyAncestor(isDialog())).performKeyInput { pressKey(Key.DirectionUp) } }
        compose.onNodeWithText("Classic").assertIsFocused()
        compose.onNode(isFocused() and hasAnyAncestor(isDialog())).performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertThat(store.currentPreferences.presentation.layout).isEqualTo(HomeLayout.COMPACT) }
        Espresso.pressBack()
        Espresso.pressBack()
        compose.onNodeWithText("Customize home").assertIsFocused()
    }

    @Test fun layoutsAndMetadataApplyWhileContentRemainsWithoutInvokingPlayback() {
        open()
        select("Home Layout")
        listOf("Compact", "Cinema", "Classic").forEach { select(it) }
        compose.runOnIdle { assertThat(store.currentPreferences.presentation.layout).isEqualTo(HomeLayout.CLASSIC) }
        select("Back to customization")
        select("Poster and Card Sizes")
        select("Secondary metadata: Shown")
        compose.runOnIdle { assertThat(store.currentPreferences.presentation.metadata).isFalse() }
        select("Back to customization")
        select("Done")
        compose.onNodeWithText("Current content").assertExists()
        compose.onNodeWithText("Secondary metadata").assertDoesNotExist()
        compose.runOnIdle { assertThat(plays).isEqualTo(0) }
        compose.onNodeWithText("Customize home").assertIsFocused()
    }
}
