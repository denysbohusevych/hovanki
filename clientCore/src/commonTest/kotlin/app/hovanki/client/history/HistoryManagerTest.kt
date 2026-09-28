package app.hovanki.client.history

import app.hovanki.client.account.AccountManager
import app.hovanki.client.account.FakeAccountApi
import app.hovanki.client.account.TEST_ACCOUNT_TOKEN
import app.hovanki.client.account.sessionExpired
import app.hovanki.client.account.testUser
import app.hovanki.client.network.ApiException
import app.hovanki.client.network.ApiResult
import app.hovanki.client.network.HistoryApi
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.storage.FakeSecureStore
import app.hovanki.client.storage.SavedAccount
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameHistoryEntry
import app.hovanki.shared.protocol.GameHistoryResponse
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameRecording
import app.hovanki.shared.protocol.GameRoute
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStats
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.RecordedPlayer
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.RoutePoint
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.rules.shrinkingZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryManagerTest {
    private val server = "https://hovanki.example.org"
    private val storage = ClientStorage(FakeSecureStore())
    private val accountApi = FakeAccountApi()
    private val api = FakeHistoryApi()

    private lateinit var account: AccountManager

    /** A history manager of a player restored as [user] (null: logged out). */
    private fun TestScope.history(user: UserProfile? = testUser): HistoryManager {
        if (user != null) storage.saveAccount(SavedAccount(server, TEST_ACCOUNT_TOKEN, user))
        account = AccountManager(accountApi, storage, ServerUrl(server), backgroundScope)
        account.restore()
        return HistoryManager(api, account, backgroundScope).also { runCurrent() }
    }

    @Test
    fun loadsTheStatisticsAndTheNewestGamesThenOlderOnes() = runTest {
        val history = history()
        api.pages = listOf(listOf(game("g3"), game("g2")), listOf(game("g1")))

        assertIs<ApiResult.Success<Unit>>(history.refresh())
        assertEquals(api.stats, history.state.value.stats)
        assertEquals(listOf("g3", "g2"), history.state.value.games.map { it.gameId.value })
        assertTrue(history.state.value.hasMore)

        assertIs<ApiResult.Success<Unit>>(history.loadMore())
        assertEquals(listOf("g3", "g2", "g1"), history.state.value.games.map { it.gameId.value })
        assertFalse(history.state.value.hasMore)
        // Nothing more to load: no call.
        val calls = api.calls.size
        assertIs<ApiResult.Success<Unit>>(history.loadMore())
        assertEquals(calls, api.calls.size)
        assertEquals(listOf("games null", "stats", "games 2"), api.calls)
    }

    @Test
    fun aListedGameIsAlwaysCountedInTheStatistics() = runTest {
        val history = history()
        api.stats = api.stats.copy(games = 0)
        // The game is saved while the app loads the history, between its two requests.
        api.afterCall = {
            api.afterCall = null
            api.pages = listOf(listOf(game("g1")))
            api.stats = api.stats.copy(games = 1)
        }

        assertIs<ApiResult.Success<Unit>>(history.refresh())
        val state = history.state.value
        val counted = checkNotNull(state.stats).games
        assertTrue(state.games.size <= counted, "${state.games.size} games listed, $counted counted")
    }

    @Test
    fun aDeletedRouteIsGoneFromTheHistory() = runTest {
        val history = history()
        api.pages = listOf(listOf(game("g2", hasRoute = true), game("g1", hasRoute = true)))
        history.refresh()

        assertIs<ApiResult.Success<Unit>>(history.deleteRoute(GameId("g2")))

        assertEquals(listOf(false, true), history.state.value.games.map { it.hasRoute })
        assertEquals("deleteRoute g2", api.calls.last())
    }

    @Test
    fun turningSavingOffLeavesNoRouteAndOnIsRemembered() = runTest {
        val history = history()
        api.pages = listOf(listOf(game("g2", hasRoute = true), game("g1", hasRoute = true)))
        history.refresh()

        assertIs<ApiResult.Success<Unit>>(history.setSaveRoutes(false))
        assertEquals(listOf(false, false), history.state.value.games.map { it.hasRoute })
        assertFalse(account.state.value.user!!.saveRoutes)

        assertIs<ApiResult.Success<Unit>>(history.setSaveRoutes(true))
        assertTrue(account.state.value.user!!.saveRoutes)
        assertEquals("setSaveRoutes $TEST_ACCOUNT_TOKEN", accountApi.calls.last())
    }

    @Test
    fun aRouteThatIsNotSavedIsRejected() = runTest {
        val history = history()

        val missing = assertIs<ApiResult.Rejected>(history.route(GameId("nope")))
        assertEquals(ErrorCode.NOT_FOUND, missing.code)
        assertEquals(api.route, assertIs<ApiResult.Success<GameRoute>>(history.route(GameId("g1"))).value)
    }

    @Test
    fun aGamesRecordingIsLoadedWhileItIsKept() = runTest {
        val history = history()

        val recording = assertIs<ApiResult.Success<GameRecording>>(history.recording(GameId("g1"))).value
        assertEquals(api.recording, recording)
        val gone = assertIs<ApiResult.Rejected>(history.recording(GameId("old")))
        assertEquals(ErrorCode.NOT_FOUND, gone.code)
        assertEquals(listOf("recording g1", "recording old"), api.calls)
    }

    @Test
    fun needsAnAccountAndForgetsItOnLogout() = runTest {
        val loggedOut = history(user = null)
        assertEquals(ErrorReason.ACCOUNT_REQUIRED, assertIs<ApiResult.Rejected>(loggedOut.refresh()).reason)
        assertTrue(api.calls.isEmpty())

        val history = history()
        history.refresh()
        assertTrue(history.state.value.isLoaded)
        account.logOut()
        runCurrent()
        assertEquals(HistoryState(), history.state.value)
    }

    @Test
    fun aRejectedSessionLogsOut() = runTest {
        val history = history()
        api.failWith = sessionExpired()

        assertIs<ApiResult.Rejected>(history.refresh())

        assertNull(account.state.value.user)
        assertTrue(account.state.value.sessionExpired)
    }

    private fun game(id: String, hasRoute: Boolean = false) = GameHistoryEntry(
        gameId = GameId(id),
        startedAtMillis = 1,
        finishedAtMillis = 2,
        role = Role.HIDER,
        status = PlayerStatus.ACTIVE,
        won = true,
        players = 3,
        seekers = 1,
        hasRoute = hasRoute,
    )

    /** Answers with [pages] one after the other (`nextBefore` is the page's number), [stats] and [route] for g1. */
    private class FakeHistoryApi : HistoryApi {
        var pages: List<List<GameHistoryEntry>> = listOf(emptyList())
        var stats = PlayerStats(games = 3, distanceMeters = 4200.0, movingSeconds = 3000)
        val route = GameRoute(
            gameId = GameId("g1"),
            role = Role.HIDER,
            zone = shrinkingZone(GeoPoint(50.45, 30.52)),
            startedAtMillis = 1,
            finishedAtMillis = 2,
            points = listOf(RoutePoint(50.45, 30.52, 5.0, 1)),
            expiresAtMillis = 3,
        )
        val recording = GameRecording(
            gameId = GameId("g1"),
            zone = shrinkingZone(GeoPoint(50.45, 30.52)),
            startedAtMillis = 1,
            finishedAtMillis = 2,
            players = listOf(
                RecordedPlayer(PlayerId("p1"), "Olya", Role.SEEKER, PlayerStatus.ACTIVE, isMe = true),
                RecordedPlayer(PlayerId("p2"), "Guest", Role.HIDER, PlayerStatus.CAUGHT, points = listOf()),
            ),
            expiresAtMillis = 3,
        )
        var failWith: Exception? = null

        /** Runs after each answer: what changes on the server between two requests. */
        var afterCall: (() -> Unit)? = null
        val calls = mutableListOf<String>()

        override suspend fun stats(token: String) = call("stats") { stats }

        override suspend fun games(token: String, before: Long?) = call("games $before") {
            val index = before?.toInt()?.minus(1) ?: 0
            val next = (index + 2).takeIf { it <= pages.size }?.toLong()
            GameHistoryResponse(pages[index], next)
        }

        override suspend fun route(token: String, gameId: GameId) = call("route ${gameId.value}") {
            if (gameId != route.gameId) throw ApiException(404, ApiError(ErrorCode.NOT_FOUND, "No route"))
            route
        }

        override suspend fun deleteRoute(token: String, gameId: GameId) = call("deleteRoute ${gameId.value}") {}

        override suspend fun recording(token: String, gameId: GameId) = call("recording ${gameId.value}") {
            if (gameId != recording.gameId) throw ApiException(404, ApiError(ErrorCode.NOT_FOUND, "No recording"))
            recording
        }

        private fun <T> call(description: String, answer: () -> T): T {
            calls += description
            failWith?.let { throw it }
            return answer().also { afterCall?.invoke() }
        }
    }
}
