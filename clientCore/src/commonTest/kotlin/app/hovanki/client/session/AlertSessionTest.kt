package app.hovanki.client.session

import app.hovanki.client.network.FakeGameApi
import app.hovanki.client.network.PollingGameConnection
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.testSession
import app.hovanki.client.network.testSnapshot
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.storage.FakeSecureStore
import app.hovanki.client.storage.SavedSession
import app.hovanki.client.tracking.AlertKind
import app.hovanki.client.tracking.HiderAlert
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.MyState
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The hider's alerts reach the background tracker, which vibrates for them while the app is not on screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlertSessionTest {
    private val storage = ClientStorage(FakeSecureStore())
    private val tracker = FakeBackgroundTracker()

    private fun TestScope.manager(api: FakeGameApi) = GameSessionManager(
        api,
        PollingGameConnection(api),
        ServerClock { 0L },
        FakeLocationProvider(),
        tracker,
        ServerUrl("http://10.0.2.2:8080"),
        storage,
        backgroundScope,
    )

    @Test
    fun anAlertVibratesAndEndsWithTheSnapshots() = runTest {
        storage.saveSession(SavedSession("http://10.0.2.2:8080", testSession))
        val out = MyState(testSession.playerId, Role.HIDER, PlayerStatus.ACTIVE, outOfZoneDeadlineMillis = 60_000L)
        var syncs = 0
        // Polling runs in real time (Dispatchers.Default): a sync a second, out of the zone in the first one only.
        val api = FakeGameApi {
            val sync = syncs++
            val snapshot = testSnapshot(
                serverTimeMillis = 1_000L + sync * 1_000L,
                syncIntervalSeconds = 1,
                phase = GamePhase.SEEKING,
            )
            if (sync == 0) snapshot.copy(me = out) else snapshot
        }
        val manager = manager(api)

        manager.resumeSavedGame()
        manager.state.first { it.snapshot != null }
        assertEquals(listOf(HiderAlert(AlertKind.OUT_OF_ZONE, 60_000L)), tracker.alerts, "right away")

        manager.state.first { it.snapshot?.serverTimeMillis?.let { time -> time > 1_000L } == true }
        assertEquals(listOf(AlertKind.OUT_OF_ZONE), tracker.endedAlerts, "over once back in the zone")
        assertEquals(1, tracker.alerts.size)
    }

    @Test
    fun leavingEndsTheAlerts() = runTest {
        storage.saveSession(SavedSession("http://10.0.2.2:8080", testSession))
        val inside =
            MyState(testSession.playerId, Role.HIDER, PlayerStatus.ACTIVE, insideBuildingRevealAtMillis = 9_000L)
        val manager = manager(FakeGameApi { testSnapshot(phase = GamePhase.SEEKING).copy(me = inside) })

        manager.resumeSavedGame()
        manager.state.first { it.snapshot != null }
        manager.leave()

        assertEquals(listOf(AlertKind.IN_BUILDING), tracker.endedAlerts)
    }
}
