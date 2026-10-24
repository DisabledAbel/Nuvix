/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.settings

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.opentv.data.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w1280dp-h720dp-land")
@OptIn(ExperimentalTestApi::class)
class StalkerConfigurationScreenTest {
    @get:Rule val compose = createComposeRule()
    private val source = Source(id = 7, name = "Synthetic", kind = SourceKind.STALKER,
        url = "https://portal.example.invalid", macAddress = "00:1A:79:AA:BB:CC")
    private fun remote(text: String) {
        val node = compose.onNodeWithText(text)
        node.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        node.performKeyInput { pressKey(Key.DirectionCenter) }
    }
    private fun remoteLabel(label: String) {
        val node = compose.onNodeWithContentDescription(label)
        node.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        node.performKeyInput { pressKey(Key.DirectionCenter) }
    }

    @Test fun dpadMovesFocusWithoutChangingIdentityUntilOk() {
        compose.setContent { MaterialTheme { StalkerConfigurationScreen(source,
            onCancel = {}, onSave = { _, _ -> }, onSaved = {}) } }
        val edit = compose.onNodeWithText("Edit MAC Address")
        edit.assertIsFocused()
        compose.onNodeWithText("Serial Number").assertDoesNotExist()
        edit.performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Advanced Device Identity").assertIsFocused()
        compose.onNodeWithText("Serial Number").assertDoesNotExist()
        compose.onNodeWithText("Advanced Device Identity").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Serial Number").assertExists()
    }

    @Test fun macRequiresExplicitConfirmationAndCancelDiscardsDraft() {
        var saved: Source? = null
        var cancelled = false
        compose.setContent { MaterialTheme { StalkerConfigurationScreen(source,
            onCancel = { cancelled = true }, onSave = { draft, result -> saved = draft; result(null) }, onSaved = {}) } }
        remote("Edit MAC Address")
        compose.onNodeWithTag("portal-mac").performTextReplacement("00:1a:79:11:22:33")
        remote("Save")
        compose.onNodeWithText("Confirm portal MAC change").assertIsDisplayed()
        compose.runOnIdle { assertNull(saved) }
        compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertNull(saved); assertFalse(cancelled) }
        remote("Save")
        compose.onNodeWithText("Confirm and Save").performClick()
        compose.runOnIdle { assertEquals("00:1a:79:11:22:33", saved!!.macAddress) }
    }

    @Test fun customFieldsAndIndividualRestoreAreAccessibleWithRemote() {
        var saved: Source? = null
        compose.setContent { MaterialTheme { StalkerConfigurationScreen(source,
            onCancel = {}, onSave = { draft, result -> saved = draft; result(null) }, onSaved = {}) } }
        remote("Advanced Device Identity")
        for (label in listOf("Serial Number", "Device ID 1", "Device ID 2", "Signature")) {
            remoteLabel("$label: Custom — Provider Supplied")
            compose.onNodeWithTag("identity-$label").performScrollTo().performTextReplacement("Provider $label")
        }
        remoteLabel("Device ID 1: Restore Automatic")
        remote("Save")
        compose.runOnIdle {
            assertEquals("Provider Serial Number", saved!!.stalkerSerialNumber)
            assertNull(saved!!.stalkerDeviceId1)
            assertEquals("Provider Device ID 2", saved!!.stalkerDeviceId2)
            assertEquals("Provider Signature", saved!!.stalkerSignature)
        }
    }

    @Test fun backAndCancelNeverInvokeSaveAndInvalidMacIsRejected() {
        var saves = 0
        var cancellations = 0
        var dispatcher: OnBackPressedDispatcher? = null
        compose.setContent { MaterialTheme {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            StalkerConfigurationScreen(source, onCancel = { cancellations++ },
                onSave = { _, _ -> saves++ }, onSaved = {})
        } }
        remote("Edit MAC Address")
        compose.onNodeWithTag("portal-mac").performTextReplacement("00:1A:79")
        remote("Save")
        compose.onNodeWithText("Enter six colon-separated hexadecimal pairs for the portal MAC.").assertIsDisplayed()
        remote("Cancel")
        compose.runOnIdle { assertEquals(0, saves); assertEquals(1, cancellations); dispatcher!!.onBackPressed() }
        compose.runOnIdle { assertEquals(0, saves); assertEquals(2, cancellations) }
    }
}
