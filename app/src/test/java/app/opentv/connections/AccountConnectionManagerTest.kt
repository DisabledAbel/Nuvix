/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.connections

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AccountConnectionManagerTest {
    private lateinit var context: Context
    private fun source(id: Long = 1, username: String = "private-user") = Source(
        id = id, name = "Provider $id", kind = SourceKind.XTREAM, url = "https://provider.example:8443",
        username = username, password = "private-password",
    )
    private fun request(id: String, sourceId: Long = 1, purpose: ConnectionPurpose = ConnectionPurpose.LIVE) =
        ConnectionRequest(id, 1, sourceId, purpose)
    private fun reserve(manager: AccountConnectionManager, request: ConnectionRequest) =
        (manager.reserve(request) as ReservationResult.Reserved).token

    @Before fun reset() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nuvix_connection_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun exactAccountsShareIdentityAcrossSourcesAndPasswordRotation() {
        val account = AccountConnectionManager.accountIdentity(source())
        assertEquals(account, AccountConnectionManager.accountIdentity(source(2)))
        assertEquals(account, AccountConnectionManager.accountIdentity(source().copy(password = "rotated")))
        assertEquals(account, AccountConnectionManager.accountIdentity(source().copy(url = "https://PROVIDER.example:8443/")))
        assertNotEquals(account, AccountConnectionManager.accountIdentity(source(username = "independent")))
        assertNotEquals(account, AccountConnectionManager.accountIdentity(source().copy(url = "https://other.example:8443")))
        assertTrue(account.matches(Regex("acct_[0-9a-f]{64}")))
    }

    @Test fun differentPlaylistUrlsRemainIndependentUntilExplicitlyLinked() {
        val a = source().copy(kind = SourceKind.M3U, url = "https://playlist.example/a?token=secret-a")
        val b = a.copy(id = 2, url = "https://playlist.example/b?token=secret-b")
        val manager = AccountConnectionManager.createForTest(context)
        assertNotEquals(manager.registerSource(a), manager.registerSource(b))
        manager.setMaximumConnections(1, 2)
        assertTrue(manager.associateSources(2, 1))
        assertEquals(manager.accountKeyForSource(1), manager.accountKeyForSource(2))
        assertEquals(2, manager.snapshotForSource(2)!!.effectiveMaximum)
    }

    @Test fun settingsAndAssociationPersistButLiveReservationsAndApprovalsDoNot() {
        val manager = AccountConnectionManager.createForTest(context)
        manager.registerSource(source())
        manager.registerSource(source(2, "other-private-user"))
        manager.setMaximumConnections(1, 3)
        assertTrue(manager.associateSources(2, 1))
        reserve(manager, request("live"))

        val reloaded = AccountConnectionManager.createForTest(context)
        reloaded.registerSource(source())
        reloaded.registerSource(source(2, "other-private-user"))
        assertEquals(reloaded.accountKeyForSource(1), reloaded.accountKeyForSource(2))
        assertEquals(3, reloaded.snapshotForSource(2)!!.effectiveMaximum)
        assertEquals(0, reloaded.snapshotForSource(1)!!.locallyReserved)
        val raw = context.getSharedPreferences("nuvix_connection_settings", Context.MODE_PRIVATE).all.toString()
        listOf("private-user", "private-password", "provider.example", "live", "reservation", "approval").forEach {
            assertFalse("Private/transient value must not be persisted: $it", raw.contains(it))
        }
    }

    @Test fun associationMergesExactIdentityCopiesTransitivelyIncludingFutureImports() {
        val manager = AccountConnectionManager.createForTest(context)
        listOf(source(), source(2), source(3, "second"), source(4, "third")).forEach(manager::registerSource)
        manager.setMaximumConnections(4, 1)
        assertTrue(manager.associateSources(1, 3))
        assertEquals(manager.accountKeyForSource(2), manager.accountKeyForSource(3))
        assertTrue(manager.associateSources(3, 4))
        assertEquals(1, manager.snapshots.value.size)
        manager.registerSource(source(5))
        assertEquals(manager.accountKeyForSource(4), manager.accountKeyForSource(5))
        reserve(manager, request("live", sourceId = 5))
        assertTrue(manager.reserve(request("other", sourceId = 2)) is ReservationResult.Denied)

        val reloaded = AccountConnectionManager.createForTest(context)
        listOf(source(), source(2), source(3, "second"), source(4, "third"), source(6)).forEach(reloaded::registerSource)
        assertEquals(1, reloaded.snapshots.value.size)
        assertEquals(1, reloaded.snapshotForSource(6)!!.effectiveMaximum)
    }

    @Test fun clearingAssociationKeepsAllExactIdentityCopiesTogether() {
        val manager = AccountConnectionManager.createForTest(context)
        listOf(source(), source(2), source(3, "second")).forEach(manager::registerSource)
        assertTrue(manager.associateSources(1, 3))
        assertTrue(manager.clearAssociation(2))
        assertEquals(manager.accountKeyForSource(1), manager.accountKeyForSource(2))
        assertNotEquals(manager.accountKeyForSource(1), manager.accountKeyForSource(3))
        manager.registerSource(source(4))
        assertEquals(manager.accountKeyForSource(1), manager.accountKeyForSource(4))
    }

    @Test fun activeAccountCannotBeSplitByAssociationOrHaveMetadataChangedByUnsavedIdentity() {
        val manager = AccountConnectionManager.createForTest(context)
        manager.updateProviderAccount(source(), 2, 1, 100)
        manager.registerSource(source(2))
        manager.registerSource(source(3, "other"))
        val oldKey = manager.accountKeyForSource(1)
        reserve(manager, request("old-identity"))
        assertFalse(manager.associateSources(2, 3))
        manager.updateProviderAccount(source(username = "unsaved-new-account"), 8, 7, 200)
        assertEquals(oldKey, manager.accountKeyForSource(1))
        assertEquals(2, manager.snapshotForSource(1)!!.effectiveMaximum)
        assertEquals(100L, manager.snapshotForSource(1)!!.providerObservationTimeMillis)
    }

    @Test fun refreshedMetadataRespectsExplicitUnknownAndDoesNotPersistServerUsage() {
        val manager = AccountConnectionManager.createForTest(context)
        manager.updateProviderAccount(source(), 4, 2, 100)
        assertEquals(4, manager.snapshotForSource(1)!!.effectiveMaximum)
        manager.setMaximumConnections(1, null)
        manager.updateProviderAccount(source(), 5, 3, 200)
        assertNull(manager.snapshotForSource(1)!!.effectiveMaximum)
        manager.setUseProviderMetadata(1, true)
        assertEquals(5, manager.snapshotForSource(1)!!.effectiveMaximum)
        val reloaded = AccountConnectionManager.createForTest(context)
        reloaded.registerSource(source())
        assertEquals(5, reloaded.snapshotForSource(1)!!.effectiveMaximum)
        assertNull(reloaded.snapshotForSource(1)!!.providerReportedActive)
        assertNull(reloaded.snapshotForSource(1)!!.providerObservationTimeMillis)
    }

    @Test fun recordingWaitsForActualOwnerCloseAndDoesNotRevokeAnotherRecording() = runTest {
        val manager = AccountConnectionManager.createForTest(context)
        manager.registerSource(source())
        manager.setMaximumConnections(1, 1)
        val live = reserve(manager, request("live"))
        var closed = false
        manager.registerOwner(live) {
            assertEquals(1, manager.snapshotForSource(1)!!.locallyReserved)
            manager.beginRelease(live)
            closed = true
            manager.release(live)
        }
        val recordRequest = request("recording", purpose = ConnectionPurpose.RECORDING)
        val recording = manager.reserveForRecording(recordRequest, allowInterruptLive = true) as ReservationResult.Reserved
        assertTrue(closed)
        assertEquals(ConnectionPurpose.RECORDING, recording.token.purpose)
        assertTrue(manager.reserveForRecording(request("recording-2", purpose = ConnectionPurpose.RECORDING),
            allowInterruptLive = true) is ReservationResult.Denied)
        assertEquals(1, manager.snapshotForSource(1)!!.locallyReserved)
    }

    @Test fun failedOwnerCloseCannotCreateCapacity() = runTest {
        val manager = AccountConnectionManager.createForTest(context)
        manager.registerSource(source())
        manager.setMaximumConnections(1, 1)
        val live = reserve(manager, request("live"))
        manager.registerOwner(live) { throw IllegalStateException("Close failed") }
        val result = manager.reserveForRecording(request("recording", purpose = ConnectionPurpose.RECORDING), allowInterruptLive = true)
        assertTrue(result is ReservationResult.Denied)
        assertEquals(1, manager.snapshotForSource(1)!!.locallyReserved)
        assertEquals(ReservationState.PENDING, manager.reservationState(live))
    }

    @Test fun unknownScheduledRecordingCannotReusePriorOneShotConsent() = runTest {
        val manager = AccountConnectionManager.createForTest(context)
        manager.registerSource(source())
        reserve(manager, request("live"))
        val immediate = request("immediate", purpose = ConnectionPurpose.RECORDING)
        val challenge = (manager.reserveForRecording(immediate) as ReservationResult.ApprovalRequired).challenge
        val approval = manager.approve(challenge)!!
        assertTrue(manager.reserveForRecording(immediate, approval) is ReservationResult.Reserved)
        assertTrue(manager.reserveForRecording(request("scheduled", purpose = ConnectionPurpose.RECORDING), approval)
            is ReservationResult.ApprovalRequired)
    }
}
