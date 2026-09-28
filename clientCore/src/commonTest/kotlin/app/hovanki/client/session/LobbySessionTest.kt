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
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.RolesRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.StreetZoneResponse
import app.hovanki.shared.protocol.StreetZoneState
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.protocol.ZoneShape
import app.hovanki.shared.rules.GameSetup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The lobby from the app's side: roles and setup go to the server, leaving tells it, the map follows the zone. */
@OptIn(ExperimentalCoroutinesApi::class)
class LobbySessionTest {
    private val house = BuildingArea(
        listOf(GeoPoint(50.0, 30.0), GeoPoint(50.0, 30.001), GeoPoint(50.001, 30.001), GeoPoint(50.0, 30.0)),
    )
    private val strip = ZonePolygon(
        listOf(
            GeoPoint(50.0, 30.0),
            GeoPoint(50.0, 30.01),
            GeoPoint(50.001, 30.01),
            GeoPoint(50.001, 30.0),
            GeoPoint(50.0, 30.0),
        ),
    )

    private fun TestScope.manager(api: GameApi, storage: ClientStorage = ClientStorage(FakeSecureStore())) =
        GameSessionManager(
            api,
            PollingGameConnection(api),
            ServerClock { 0L },
            FakeLocationProvider(),
            FakeBackgroundTracker(),
            ServerUrl("http://localhost:8080"),
            storage,
            backgroundScope,
        )

    @Test
    fun rolesGoToTheServer() = runTest {
        val api = FakeGameApi(
            onJoin = { SessionResponse(testSession, testSnapshot()) },
            onRoles = { testSnapshot() },
        ) { testSnapshot() }
        val manager = manager(api)
        manager.join("ABC234", "Anna")

        manager.setSeekers(listOf(PlayerId("boris")))
        manager.drawSeekers(2)

        assertEquals(
            listOf(RolesRequest(seekers = listOf(PlayerId("boris"))), RolesRequest(randomSeekers = 2)),
            api.rolesRequests,
        )
    }

    @Test
    fun theHostsSetupIsRememberedForTheNextGame() = runTest {
        val api = FakeGameApi(
            onJoin = { SessionResponse(testSession, testSnapshot()) },
            onSettings = { testSnapshot() },
        ) { testSnapshot() }
        val storage = ClientStorage(FakeSecureStore())
        val manager = manager(api, storage)
        assertEquals(GameSetup(), manager.lastGameSetup(), "the defaults the first time")
        manager.join("ABC234", "Anna")
        val setup = GameSetup(radiusMeters = 800, zoneShape = ZoneShape.STREETS, glowEveryMinutes = 3)

        manager.updateSettings(setup.settings(GeoPoint(50.0, 30.0)), setup)

        assertEquals(1, api.settingsRequests.size)
        assertEquals(setup, manager.lastGameSetup())
        assertEquals(setup, manager(api, storage).lastGameSetup(), "after the app was started again")
    }

    @Test
    fun aRefusedSetupIsNotRemembered() = runTest {
        val api = FakeGameApi(
            onJoin = { SessionResponse(testSession, testSnapshot()) },
            onSettings = { error("offline") },
        ) { testSnapshot() }
        val manager = manager(api)
        manager.join("ABC234", "Anna")
        val setup = GameSetup(radiusMeters = 800)

        manager.updateSettings(setup.settings(GeoPoint(50.0, 30.0)), setup)

        assertEquals(GameSetup(), manager.lastGameSetup())
    }

    @Test
    fun leavingTellsTheServer() = runTest {
        val api = FakeGameApi(onJoin = { SessionResponse(testSession, testSnapshot()) }) { testSnapshot() }
        val manager = manager(api)
        manager.join("ABC234", "Anna")

        manager.leave()
        runCurrent()

        assertNull(manager.state.value.session)
        assertEquals(listOf(testSession), api.leaves)
    }

    @Test
    fun closingTheResultsIsNoLeaving() = runTest {
        val finished = testSnapshot(phase = GamePhase.FINISHED)
        val api =
            FakeGameApi(onJoin = {
                SessionResponse(testSession, finished)
            }, onTracks = { error("offline") }) { finished }
        val manager = manager(api)
        manager.join("ABC234", "Anna")

        manager.leave()
        runCurrent()

        assertEquals(emptyList(), api.leaves)
    }

    @Test
    fun aNewZoneBringsNewBuildings() = runTest {
        var revision = 0
        var syncs = 0L
        val api = FakeGameApi(
            onJoin = { SessionResponse(testSession, testSnapshot(buildings = BuildingsState.READY)) },
            onBuildings = {
                BuildingsResponse(BuildingsState.READY, List(revision + 1) { house }, mapRevision = revision)
            },
        ) {
            testSnapshot(
                serverTimeMillis = 2_000 + ++syncs,
                buildings = BuildingsState.READY,
            ).copy(mapRevision = revision)
        }
        val manager = manager(api)
        manager.join("ABC234", "Anna")
        assertEquals(1, manager.state.first { it.buildings != null }.buildings?.buildings?.size)

        revision = 1

        assertEquals(2, manager.state.first { it.buildings?.mapRevision == 1 }.buildings?.buildings?.size)
    }

    @Test
    fun theZoneByStreetsOnceItIsBuilt() = runTest {
        var state = StreetZoneState.LOADING
        var syncs = 0L
        val api = FakeGameApi(
            onJoin = { SessionResponse(testSession, testSnapshot()) },
            onStreetZone = { StreetZoneResponse(StreetZoneState.READY, 0, listOf(strip)) },
        ) { testSnapshot(serverTimeMillis = 2_000 + ++syncs).copy(streetZone = state) }
        val manager = manager(api)
        manager.join("ABC234", "Anna")
        manager.state.first { (it.snapshot?.serverTimeMillis ?: 0) > 2_000 }
        runCurrent()
        assertEquals(0, api.streetZoneRequests)

        state = StreetZoneState.READY

        assertEquals(listOf(strip), manager.state.first { it.streetZone != null }.streetZone?.stages)
    }
}
