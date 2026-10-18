/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.multiview

import android.app.Application
import android.content.Context
import app.opentv.connections.AccountConnectionPolicy
import app.opentv.connections.AccountConnectionSnapshot
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class MultiViewPresetStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val accountA = "acct_" + "a".repeat(64)
    private val accountB = "acct_" + "b".repeat(64)
    private val stores = mutableListOf<MultiViewPresetStore>()
    private fun store() = MultiViewPresetStore(context).also(stores::add)
    private fun presentation() = MultiViewPresentation(
        panels = listOf(
            MultiViewSavedPanel("panel-primary", MultiViewChannelReference(10, 1, accountA)),
            MultiViewSavedPanel("panel-secondary", MultiViewChannelReference(20, 2, accountB)),
        ),
        primaryPanelId = "panel-primary", audioPanelId = "panel-secondary", layout = MultiViewLayout.TWO,
    )

    @Before fun setup() {
        context.getSharedPreferences(MultiViewPresetStore.STORAGE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() { stores.forEach { it.close() } }

    @Test fun `save and reopen retain identity ordering provider account audio and presentation`() {
        val source = store()
        val original = presentation().copy(panels = presentation().panels.reversed(), layout = MultiViewLayout.FOCUSED, showLabels = false)
        val saved = source.upsert("Match night", original)!!
        val restored = store().find(saved.id)!!
        assertThat(restored.name).isEqualTo("Match night")
        assertThat(restored.presentation).isEqualTo(original)
        assertThat(restored.presentation.panels.map { it.panelId }).containsExactly("panel-secondary", "panel-primary").inOrder()
        assertThat(source.presets.value).containsExactly(saved)
    }

    @Test fun `rename replace and delete preserve the requested preset identity`() {
        val source = store()
        val first = source.upsert("First", presentation())!!
        val second = source.upsert("Second", presentation())!!
        assertThat(source.rename(first.id, "Renamed")).isTrue()
        val replacement = presentation().copy(audioPanelId = "panel-primary", layout = MultiViewLayout.GRID)
        val replaced = source.upsert("Replaced", replacement, first.id)!!
        assertThat(replaced.id).isEqualTo(first.id)
        assertThat(store().find(first.id)?.presentation).isEqualTo(replacement)
        assertThat(source.find(second.id)?.name).isEqualTo("Second")
        assertThat(source.delete(first.id)).isTrue()
        assertThat(store().presets.value.map { it.id }).containsExactly(second.id)
        assertThat(source.rename("missing", "No change")).isFalse()
        assertThat(source.delete("missing")).isFalse()
        assertThat(source.upsert("Missing", presentation(), "missing")).isNull()
    }

    @Test fun `independent store instances do not lose existing presets during updates`() {
        val first = store()
        val second = store()
        val presetA = first.upsert("A", presentation())!!
        val presetB = second.upsert("B", presentation())!!
        assertThat(store().presets.value.map { it.id }).containsExactly(presetA.id, presetB.id)
    }

    @Test fun `interrupted recovery is durable presentation only and requires an explicit consumer`() {
        val source = store()
        val snapshot = presentation()
        assertThat(source.peekRecovery()).isNull()
        assertThat(source.saveInterrupted(snapshot)).isTrue()
        assertThat(source.recovery.value).isEqualTo(snapshot)
        assertThat(store().peekRecovery()).isEqualTo(snapshot)
        // Reading and previewing cannot consume recovery, reservations, or start a player.
        source.preview(snapshot, emptyList(), emptyList())
        assertThat(store().peekRecovery()).isEqualTo(snapshot)
        assertThat(source.clearRecovery()).isTrue()
        assertThat(source.recovery.value).isNull()
        assertThat(store().peekRecovery()).isNull()
    }

    @Test fun `intentional recovery dismissal never deletes explicitly saved presets`() {
        val source = store()
        val preset = source.upsert("Keep", presentation())!!
        source.saveInterrupted(presentation())
        source.clearRecovery()
        assertThat(store().find(preset.id)).isEqualTo(preset)
    }

    @Test fun `preview reports missing channels shared account requirements and unknown limits without mutation`() {
        val source = store()
        val snapshot = presentation().copy(panels = listOf(
            MultiViewSavedPanel("panel-primary", MultiViewChannelReference(10, 1, accountA)),
            MultiViewSavedPanel("panel-secondary", MultiViewChannelReference(20, 2, accountA)),
            MultiViewSavedPanel("panel-third", MultiViewChannelReference(30, 3, accountB)),
        ))
        val accounts = listOf(
            AccountConnectionSnapshot(accountA, setOf(1, 2), AccountConnectionPolicy(maximumConnections = 1, useProviderMetadata = false), emptyList()),
            AccountConnectionSnapshot(accountB, setOf(3), AccountConnectionPolicy(), emptyList()),
        )
        val channels = listOf(MultiViewPreviewChannel(10, 1, "Game one", "Provider A"), MultiViewPreviewChannel(30, 3, "Game three", "Provider B"))
        val preview = source.preview(snapshot, channels, accounts)
        assertThat(preview.panels.map { it.available }).containsExactly(true, false, true).inOrder()
        val shared = preview.requirements.single { it.accountId == accountA }
        assertThat(shared.requestedConnections).isEqualTo(2)
        assertThat(shared.exceedsConfiguredAllowance).isTrue()
        assertThat(preview.requirements.single { it.accountId == accountB }.unknownAllowance).isTrue()
        assertThat(preview.deviceCapabilityUncertain).isTrue()
        assertThat(accounts.all { it.reservations.isEmpty() }).isTrue()
        assertThat(context.getSharedPreferences(MultiViewPresetStore.STORAGE_NAME, Context.MODE_PRIVATE).all).isEmpty()
    }

    @Test fun `preview uses current account links and catches stale references`() {
        val preview = store().preview(presentation(), listOf(MultiViewPreviewChannel(10, 1, "Channel", "Provider")),
            listOf(AccountConnectionSnapshot(accountB, setOf(1, 2), AccountConnectionPolicy(), emptyList())))
        assertThat(preview.panels.first().accountChanged).isTrue()
        assertThat(preview.requirements).hasSize(1)
        assertThat(preview.requirements.single().accountId).isEqualTo(accountB)
        // An id alone cannot validate a channel that now belongs to another source.
        val wrongSource = store().preview(presentation(), listOf(MultiViewPreviewChannel(10, 99, "Different", "Other")), emptyList())
        assertThat(wrongSource.panels.first().available).isFalse()
    }

    @Test fun `invalid panel identities duplicates and references cannot be persisted`() {
        val source = store()
        val original = presentation()
        assertThat(source.upsert("Empty", original.copy(panels = emptyList()))).isNull()
        assertThat(source.upsert("Missing primary", original.copy(primaryPanelId = "missing"))).isNull()
        assertThat(source.upsert("Missing audio", original.copy(audioPanelId = "missing"))).isNull()
        assertThat(source.upsert("Duplicate", original.copy(panels = listOf(original.panels.first(), original.panels.first())))).isNull()
        assertThat(source.upsert("Bad channel", original.copy(panels = listOf(original.panels.first().copy(channel = MultiViewChannelReference(-1, 1)))))).isNull()
        assertThat(source.presets.value).isEmpty()
        assertThat(source.peekRecovery()).isNull()
    }

    @Test fun `storage accepts only safe opaque references and rejects credential or URL shaped inputs`() {
        val source = store()
        val snapshot = presentation()
        val secretUrl = "https://provider.example/live/user/password.m3u8?token=private-token"
        val unsafeAccount = snapshot.copy(panels = listOf(snapshot.panels.first().copy(channel = MultiViewChannelReference(10, 1, secretUrl))))
        assertThat(source.upsert("Unsafe account", unsafeAccount)).isNull()
        assertThat(source.saveInterrupted(unsafeAccount)).isFalse()
        assertThat(source.upsert(secretUrl, snapshot)).isNull()
        assertThat(source.upsert("token=private-token", snapshot)).isNull()
        val unsafePanel = snapshot.copy(panels = listOf(snapshot.panels.first().copy(panelId = secretUrl)), primaryPanelId = secretUrl, audioPanelId = null)
        assertThat(source.saveInterrupted(unsafePanel)).isFalse()
        source.upsert("Safe", snapshot)
        source.saveInterrupted(snapshot)
        val encoded = context.getSharedPreferences(MultiViewPresetStore.STORAGE_NAME, Context.MODE_PRIVATE).all.values.joinToString()
        assertThat(encoded).doesNotContain("https://")
        assertThat(encoded).doesNotContain("password")
        assertThat(encoded).doesNotContain("private-token")
        assertThat(encoded).doesNotContain("reservation")
        assertThat(encoded).doesNotContain("surface")
        assertThat(encoded).contains(accountA)
    }

    @Test fun `unknown and corrupted schema are preserved without destructive migration`() {
        val prefs = context.getSharedPreferences(MultiViewPresetStore.STORAGE_NAME, Context.MODE_PRIVATE)
        listOf("{bad JSON", "{\"version\":2,\"presets\":[],\"futureData\":\"keep\"}").forEach { content ->
            prefs.edit().putString(MultiViewPresetStore.KEY_CONTENT, content).commit()
            val source = store()
            assertThat(source.storageAvailable).isFalse()
            assertThat(source.presets.value).isEmpty()
            assertThat(source.peekRecovery()).isNull()
            assertThat(source.upsert("Do not clobber", presentation())).isNull()
            assertThat(source.saveInterrupted(presentation())).isFalse()
            assertThat(source.clearRecovery()).isFalse()
            assertThat(prefs.getString(MultiViewPresetStore.KEY_CONTENT, null)).isEqualTo(content)
        }
    }

    @Test fun `preset capacity is bounded and replacing at capacity remains possible`() {
        val source = store()
        repeat(32) { assertThat(source.upsert("Preset $it", presentation())).isNotNull() }
        assertThat(source.upsert("Overflow", presentation())).isNull()
        val first = source.presets.value.first()
        assertThat(source.upsert("Replace at limit", presentation(), first.id)).isNotNull()
        assertThat(store().presets.value).hasSize(32)
    }
}
