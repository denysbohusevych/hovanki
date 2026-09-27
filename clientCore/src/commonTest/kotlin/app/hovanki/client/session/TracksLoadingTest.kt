package app.hovanki.client.session

import app.hovanki.client.network.FakeGameApi
import app.hovanki.client.network.PollingGameConnection
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.testSession
import app.hovanki.client.network.testSnapshot
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.storage.FakeSecureStore
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerTrack
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.TrackPoint
import app.hovanki.shared.protocol.TracksResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The replay on the results screen: the tracks come once the game is over, once. */
@OptIn(ExperimentalCoroutinesApi::class)
class TracksLoadingTest {
    private val tracks =
        TracksResponse(listOf(PlayerTrack(testSession.playerId, listOf(TrackPoint(50.45, 30.52, 1_000)))))

    private fun TestScope.manager(api: FakeGameApi) = GameSessionManager(
        api,
        PollingGameConnection(api),
        ServerClock { 0L },
        FakeLocationProvider(),
        FakeBackgroundTracker(),
        ServerUrl("http://localhost:8080"),
        ClientStorage(FakeSecureStore()),
        backgroundScope,
    )

    @Test
    fun loadedOnceTheGameIsOver() = runTest {
        var syncs = 0L
        var over = false
        var attempts = 0
        val api = FakeGameApi(
            onJoin = { SessionResponse(testSession, testSnapshot(phase = GamePhase.SEEKING)) },
            onTracks = { if (++attempts == 1) error("offline") else tracks },
        ) {
            testSnapshot(
                serverTimeMillis = 2_000 + ++syncs,
                syncIntervalSeconds = 1,
                phase = if (over) GamePhase.FINISHED else GamePhase.SEEKING,
            )
        }
        val manager = manager(api)

        manager.join("ABC234", "Anna")
        manager.state.first { (it.snapshot?.serverTimeMillis ?: 0) > 2_000 }
        runCurrent()
        assertEquals(0, api.tracksRequests, "secret while the round runs")

        over = true
        assertEquals(tracks, manager.state.first { it.tracks != null }.tracks, "a failed load is tried again")
        val seen = syncs
        manager.state.first { (it.snapshot?.serverTimeMillis ?: 0) > 2_000 + seen }
        assertEquals(2, api.tracksRequests)
    }
}
