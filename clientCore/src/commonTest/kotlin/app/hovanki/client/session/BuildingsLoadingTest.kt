package app.hovanki.client.session

import app.hovanki.client.network.FakeGameApi
import app.hovanki.client.network.GameApi
import app.hovanki.client.network.PollingGameConnection
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.testSession
import app.hovanki.client.network.testSnapshot
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.storage.FakeSecureStore
import app.hovanki.shared.protocol.BuildingArea
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.SessionResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The map draws the buildings the server judges by: loaded once per session, when the snapshot says they are ready. */
@OptIn(ExperimentalCoroutinesApi::class)
class BuildingsLoadingTest {
    private val house = BuildingArea(
        listOf(GeoPoint(50.0, 30.0), GeoPoint(50.0, 30.001), GeoPoint(50.001, 30.001), GeoPoint(50.0, 30.0)),
    )
    private val buildings = BuildingsResponse(BuildingsState.READY, listOf(house))

    private fun TestScope.manager(api: GameApi) = GameSessionManager(
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
    fun loadsTheBuildingsOnceTheyAreReady() = runTest {
        var syncs = 0L
        var ready = false
        val api = FakeGameApi(
            onJoin = { SessionResponse(testSession, testSnapshot(buildings = BuildingsState.LOADING)) },
            onBuildings = { buildings },
        ) {
            testSnapshot(
                serverTimeMillis = 2_000 + ++syncs,
                buildings = if (ready) BuildingsState.READY else BuildingsState.LOADING,
            )
        }
        val manager = manager(api)

        manager.join("ABC234", "Anna")
        manager.state.first { (it.snapshot?.serverTimeMillis ?: 0) > 2_000 }
        runCurrent()
        assertEquals(0, api.buildingsRequests, "not while the server is still loading them")

        ready = true
        val loaded = manager.state.first { it.buildings != null }
        assertEquals(buildings, loaded.buildings)
        val seen = syncs
        manager.state.first { (it.snapshot?.serverTimeMillis ?: 0) > 2_000 + seen }
        assertEquals(1, api.buildingsRequests, "once per session")
    }

    @Test
    fun aFailedLoadIsRetriedWithALaterSnapshot() = runTest {
        var attempts = 0
        var syncs = 0L
        val api = FakeGameApi(
            onJoin = { SessionResponse(testSession, testSnapshot(buildings = BuildingsState.READY)) },
            onBuildings = { if (++attempts == 1) error("offline") else buildings },
        ) { testSnapshot(serverTimeMillis = 2_000 + ++syncs, buildings = BuildingsState.READY) }
        val manager = manager(api)

        manager.join("ABC234", "Anna")

        assertEquals(buildings, manager.state.first { it.buildings != null }.buildings)
        assertEquals(2, api.buildingsRequests)
    }

    @Test
    fun withoutTheRuleNothingIsLoaded() = runTest {
        val api = FakeGameApi(
            onJoin = { SessionResponse(testSession, testSnapshot(buildings = BuildingsState.UNAVAILABLE)) },
        ) { testSnapshot(serverTimeMillis = 2_000, buildings = BuildingsState.UNAVAILABLE) }
        val manager = manager(api)

        manager.join("ABC234", "Anna")
        manager.state.first { it.snapshot?.serverTimeMillis == 2_000L }

        assertEquals(0, api.buildingsRequests)
        assertNull(manager.state.value.buildings)
    }
}
