/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.multiview

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import app.opentv.connections.*
import app.opentv.core.ServiceLocator
import app.opentv.multiview.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import org.junit.After
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
class MultiViewRemoteUiTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: MultiViewSessionManager
    private var nextId = 0

    @Before fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(MultiViewPresetStore.STORAGE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        ServiceLocator.get(context).settings.setMultiViewPreferences(MultiViewPreferences())
        manager = MultiViewSessionManager(scope, resolve = { awaitCancellation() }, connections = object : MultiViewConnections {
            override fun snapshot(sourceId: Long): AccountConnectionSnapshot? = null
            override fun reserve(request: ConnectionRequest, approval: UnknownConnectionApproval?): ReservationResult = error("No streams during UI navigation")
            override fun approve(challenge: UnknownConnectionChallenge): UnknownConnectionApproval? = null
            override fun discard(challenge: UnknownConnectionChallenge) {}
            override fun markActive(token: ReservationToken) = false
            override fun beginRelease(token: ReservationToken) = false
            override fun release(token: ReservationToken) = false
            override fun fail(token: ReservationToken, knownConnectionLimit: Boolean) = false
            override fun registerOwner(token: ReservationToken, reclamationPriority: () -> Int, close: suspend () -> Unit) {}
        }, players = MultiViewPlayerFactory { error("UI must not allocate a player") }, hardware = object : MultiViewHardware {
            override val capability = MultiViewHardwareCapability(4, "Fake device")
        }, newId = { "panel-${nextId++}" })
        manager.add(1)
        manager.add(2)
        compose.setContent { MaterialTheme { MultiViewScreen(manager, onExit = {}, onOpenGuide = {}) } }
    }

    @After fun teardown() { manager.stop(); scope.cancel() }

    @Test fun remoteMenuOpensExplicitPanelControlsWithoutLongPress() {
        val id = manager.state.value.panels.first().id
        compose.onNodeWithTag("multiview-panel-$id").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.Menu) }
        compose.onNodeWithText("Listen to This Panel").assertExists()
        compose.onNodeWithText("Replace Channel").assertExists()
        compose.onNodeWithText("Watch Fullscreen").assertExists()
        compose.onNodeWithText("Remove Panel").assertExists()
    }

    @Test fun editingReordersStablePanelIdsAndDoneIsAnExplicitRemoteAction() {
        val original = manager.state.value.panels.map { it.id }
        compose.onNodeWithText("Edit Layout").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("multiview-done").assertExists()
        compose.onNodeWithTag("multiview-panel-${original.first()}").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertThat(manager.state.value.panels.map { it.id }).containsExactlyElementsIn(original.reversed()).inOrder() }
        compose.onNodeWithTag("multiview-done").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Panel Actions").assertExists()
        compose.runOnIdle { assertThat(manager.state.value.panels.map { it.channelId }).containsExactly(2L, 1L).inOrder() }
    }

    @Test fun explicitMuteIsPreservedWhileSelectingAnotherAudioPanel() {
        compose.onNodeWithText("Mute").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        val id = manager.state.value.panels.last().id
        compose.onNodeWithTag("multiview-panel-$id").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            assertThat(manager.state.value.muted).isTrue()
            assertThat(manager.state.value.audioPanelId).isEqualTo(id)
        }
        compose.onNodeWithText("Unmute").assertExists()
    }
}
