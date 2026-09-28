package app.hovanki.client.session

import app.hovanki.client.account.AccountCredentials
import app.hovanki.client.network.FakeGameApi
import app.hovanki.client.network.GameApi
import app.hovanki.client.network.PollingGameConnection
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.testSession
import app.hovanki.client.network.testSnapshot
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.storage.FakeSecureStore
import app.hovanki.shared.protocol.BigGameId
import app.hovanki.shared.protocol.BigGameInfo
import app.hovanki.shared.protocol.BuildingArea
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.CapacityState
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.RolesRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.StreetZoneResponse
import app.hovanki.shared.protocol.StreetZoneState
import app.hovanki.shared.protocol.TerrainAreas
import app.hovanki.shared.protocol.ZoneCapacity
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

    private fun TestScope.manager(
        api: GameApi,
        storage: ClientStorage = ClientStorage(FakeSecureStore()),
        account: AccountCredentials = AccountCredentials.None,
    ) = GameSessionManager(
        api,
        PollingGameConnection(api),
        ServerClock { 0L },
        FakeLocationProvider(),
        FakeBackgroundTracker(),
        ServerUrl("http://localhost:8080"),
        storage,
        backgroundScope,
        account,
    )

    private object LoggedIn : AccountCredentials {
        override val accountToken: String = "account-token"

        override fun onTokenRejected(token: String) = Unit
    }

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
    fun theHostPlaysAnyway() = runTest {
        val crowded = ZoneCapacity(CapacityState.READY, players = 2, areas = TerrainAreas(denseSquareMeters = 2_000))
        val api = FakeGameApi(
            onJoin = { SessionResponse(testSession, testSnapshot().copy(capacity = crowded)) },
            onAcceptCrowding = { testSnapshot().copy(capacity = crowded.copy(accepted = true)) },
        ) { testSnapshot().copy(capacity = crowded.copy(accepted = true)) }
        val manager = manager(api)
        manager.join("ABC234", "Anna")

        manager.acceptCrowding()

        assertEquals(1, api.crowdingAccepts)
        assertEquals(true, manager.state.value.snapshot?.capacity?.accepted)
    }

    @Test
    fun intoABigGamesLobby() = runTest {
        val big = testSnapshot().copy(
            hostId = PlayerId("server"),
            bigGame = BigGameInfo(BigGameId("saturday"), "Saturday", 1_900_000_000_000, "Europe/Kyiv", signedUp = 120),
        )
        var answers = 0
        val api = FakeGameApi(onJoinBigGame = { _, _ ->
            if (answers++ == 0) error("the answer got lost")
            SessionResponse(testSession, big)
        }) { big }
        val manager = manager(api, account = LoggedIn)

        assertEquals(false, manager.joinBigGame(BigGameId("saturday")))
        assertEquals(true, manager.joinBigGame(BigGameId("saturday")))

        assertEquals("Saturday", manager.state.value.snapshot?.bigGame?.title)
        val (first, again) = api.bigGameJoins
        assertEquals("account-token", first.third)
        assertEquals(first.second.requestId, again.second.requestId, "the same request, sent again")
    }

    @Test
    fun aGuestCantComeIntoABigGame() = runTest {
        val api = FakeGameApi { testSnapshot() }
        val manager = manager(api)

        assertEquals(false, manager.joinBigGame(BigGameId("saturday")))

        assertEquals(ErrorReason.ACCOUNT_REQUIRED, (manager.state.value.lastError as SessionError.Rejected).reason)
        assertEquals(emptyList(), api.bigGameJoins)
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
