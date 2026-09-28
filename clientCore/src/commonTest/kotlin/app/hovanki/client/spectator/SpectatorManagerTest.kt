package app.hovanki.client.spectator

import app.hovanki.client.account.AccountManager
import app.hovanki.client.account.FakeAccountApi
import app.hovanki.client.account.TEST_ACCOUNT_TOKEN
import app.hovanki.client.account.testUser
import app.hovanki.client.network.ApiException
import app.hovanki.client.network.ApiResult
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.SpectatorApi
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.storage.FakeSecureStore
import app.hovanki.client.storage.SavedAccount
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.SpectatorId
import app.hovanki.shared.protocol.SpectatorSession
import app.hovanki.shared.protocol.SpectatorSnapshot
import app.hovanki.shared.protocol.StreetZoneResponse
import app.hovanki.shared.protocol.StreetZoneState
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.protocol.WatchRequest
import app.hovanki.shared.protocol.WatchResponse
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.rules.shrinkingZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SpectatorManagerTest {
    private val server = "https://hovanki.example.org"
    private val storage = ClientStorage(FakeSecureStore())
    private val api = FakeSpectatorApi()

    private lateinit var account: AccountManager

    private fun TestScope.spectator(user: UserProfile? = testUser): SpectatorManager {
        if (user != null) storage.saveAccount(SavedAccount(server, TEST_ACCOUNT_TOKEN, user))
        account = AccountManager(FakeAccountApi(), storage, ServerUrl(server), backgroundScope)
        account.restore()
        return SpectatorManager(api, account, backgroundScope, testTimeSource).also { runCurrent() }
    }

    @Test
    fun watchesByCodeAndFollowsTheGame() = runTest {
        val spectator = spectator()

        assertIs<ApiResult.Success<Unit>>(spectator.watch(" abc234 "))

        assertEquals("watch $TEST_ACCOUNT_TOKEN abc234", api.calls.single())
        val state = spectator.state.value
        assertTrue(state.isWatching)
        assertEquals(GamePhase.LOBBY, state.snapshot?.phase)
        assertEquals(api.atMillis, state.shownAtMillis())
        // Between polls the game shown moves on with the clock.
        advanceTimeBy(1_000)
        assertEquals(api.atMillis + 1_000, spectator.state.value.shownAtMillis())

        api.phase = GamePhase.SEEKING
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(GamePhase.SEEKING, spectator.state.value.snapshot?.phase)
        assertEquals("spectate s1", api.calls.last())
    }

    @Test
    fun loadsTheZoneByStreetsOncePerRevision() = runTest {
        val spectator = spectator()
        api.streetZone = StreetZoneState.READY
        spectator.watch("ABC234")

        advanceTimeBy(3_001)
        assertNotNull(spectator.state.value.streetZone)
        advanceTimeBy(3_001)
        assertEquals(1, api.calls.count { it.startsWith("streetZone") })
    }

    @Test
    fun theEndOfTheWatchingOnTheServerIsShown() = runTest {
        val spectator = spectator()
        spectator.watch("ABC234")

        api.failWith = ApiException(401, ApiError(ErrorCode.UNAUTHORIZED, "Unknown or expired token"))
        advanceTimeBy(3_001)

        assertTrue(spectator.state.value.ended)
        val calls = api.calls.size
        advanceTimeBy(10_000)
        assertEquals(calls, api.calls.size, "no more polls")
    }

    @Test
    fun networkTroubleKeepsTrying() = runTest {
        val spectator = spectator()
        spectator.watch("ABC234")

        api.failWith = IllegalStateException("no network")
        advanceTimeBy(3_001)
        assertTrue(spectator.state.value.isReconnecting)
        api.failWith = null
        advanceTimeBy(3_001)
        assertFalse(spectator.state.value.isReconnecting)
        assertFalse(spectator.state.value.ended)
    }

    @Test
    fun stoppingTellsTheServerAndLoggingOutStops() = runTest {
        val spectator = spectator()
        spectator.watch("ABC234")
        spectator.stop()
        runCurrent()
        assertEquals("leave s1", api.calls.last())
        assertFalse(spectator.state.value.isWatching)

        spectator.watch("ABC234")
        account.logOut()
        runCurrent()
        assertFalse(spectator.state.value.isWatching)
    }

    @Test
    fun theServersNoIsPassedOnAndAnAccountIsNeeded() = runTest {
        val loggedOut = spectator(user = null)
        assertEquals(ErrorReason.ACCOUNT_REQUIRED, assertIs<ApiResult.Rejected>(loggedOut.watch("ABC234")).reason)
        assertTrue(api.calls.isEmpty())

        val spectator = spectator()
        api.failWith = ApiException(
            403,
            ApiError(ErrorCode.FORBIDDEN, "Not open", ErrorReason.GAME_NOT_OPEN),
        )
        val rejected = assertIs<ApiResult.Rejected>(spectator.watch("ABC234"))
        assertEquals(ErrorReason.GAME_NOT_OPEN, rejected.reason)
        assertNull(spectator.state.value.session)
    }

    private class FakeSpectatorApi : SpectatorApi {
        val session = SpectatorSession(GameId("g"), SpectatorId("s1"), "spectator-token")
        val atMillis = 1_700_000_000_000L
        var phase = GamePhase.LOBBY
        var streetZone: StreetZoneState? = null
        var failWith: Exception? = null
        val calls = mutableListOf<String>()
        private val settings = GameSettings(zone = shrinkingZone(GeoPoint(50.45, 30.52)), openGame = true)

        private fun snapshot() = SpectatorSnapshot(
            gameId = session.gameId,
            settings = settings,
            serverTimeMillis = atMillis + 60_000,
            atMillis = atMillis,
            delaySeconds = 60,
            phase = phase,
            streetZone = streetZone,
            mapRevision = 1,
        )

        override suspend fun watch(accountToken: String, request: WatchRequest): WatchResponse =
            call("watch $accountToken ${request.joinCode}") { WatchResponse(session, snapshot()) }

        override suspend fun spectate(session: SpectatorSession): SpectatorSnapshot =
            call("spectate ${session.spectatorId.value}") { snapshot() }

        override suspend fun streetZone(session: SpectatorSession): StreetZoneResponse =
            call("streetZone ${session.spectatorId.value}") {
                val square = listOf(
                    GeoPoint(50.45, 30.52),
                    GeoPoint(50.45, 30.53),
                    GeoPoint(50.46, 30.53),
                    GeoPoint(50.45, 30.52),
                )
                StreetZoneResponse(StreetZoneState.READY, 1, listOf(ZonePolygon(square)))
            }

        override suspend fun leave(session: SpectatorSession) = call("leave ${session.spectatorId.value}") {}

        private fun <T> call(description: String, answer: () -> T): T {
            calls += description
            failWith?.let { throw it }
            return answer()
        }
    }
}
