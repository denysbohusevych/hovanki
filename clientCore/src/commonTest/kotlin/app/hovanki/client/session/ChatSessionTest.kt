package app.hovanki.client.session

import app.hovanki.client.account.AccountCredentials
import app.hovanki.client.network.ApiException
import app.hovanki.client.network.FakeGameApi
import app.hovanki.client.network.GameApi
import app.hovanki.client.network.PollingGameConnection
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.testMessage
import app.hovanki.client.network.testPlayer
import app.hovanki.client.network.testSession
import app.hovanki.client.network.testSnapshot
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.storage.FakeSecureStore
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.InviteRequest
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.SendChatRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.rules.RequestIds
import app.hovanki.shared.rules.shrinkingZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The game's chat in [GameSessionManager], the account token of create/join, and polling after the game ended. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionTest {
    /** An account as [GameSessionManager] sees it; remembers the tokens the server rejected. */
    private class FakeCredentials(override var accountToken: String?) : AccountCredentials {
        val rejected = mutableListOf<String>()

        override fun onTokenRejected(token: String) {
            rejected += token
            accountToken = null
        }
    }

    private val storage = ClientStorage(FakeSecureStore())
    private val locations = FakeLocationProvider()
    private val tracker = FakeBackgroundTracker()
    private val bo = PlayerView(PlayerId("bo"), "Bo", Role.SEEKER, PlayerStatus.ACTIVE, userId = UserId("u2"))
    private val players = listOf(testPlayer, bo)

    private fun TestScope.manager(api: GameApi, account: AccountCredentials = AccountCredentials.None) =
        GameSessionManager(
            api,
            PollingGameConnection(api),
            ServerClock { 0L },
            locations,
            tracker,
            ServerUrl("http://10.0.2.2:8080"),
            storage,
            backgroundScope,
            account,
        )

    /**
     * Answers the polls from [script] in order (the last one again and again). The connection polls on
     * `Dispatchers.Default`, in real time: [snapshot]s ask for the shortest interval, 1 s.
     */
    private fun scriptedPolls(vararg script: (SyncRequest) -> Any): FakeGameApi {
        var polls = 0
        return FakeGameApi(onJoin = { SessionResponse(testSession, testSnapshot(players = players)) }) { request ->
            val answer = script[minOf(polls++, script.lastIndex)](request)
            if (answer is Exception) throw answer
            answer as GameSnapshot
        }
    }

    private fun snapshot(time: Long, vararg chat: Long, phase: GamePhase = GamePhase.LOBBY, from: PlayerId = bo.id) =
        testSnapshot(
            serverTimeMillis = time,
            syncIntervalSeconds = 1,
            phase = phase,
            players = players,
            chat = chat.map { testMessage(it, from = from) },
        )

    /** Waits (in real time: polls run on `Dispatchers.Default`) until [condition] holds. */
    private suspend fun eventually(condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                while (!condition()) delay(20)
            }
        }
    }

    /** Lets real time pass, e.g. for a poll that must not come. */
    private suspend fun wait(millis: Long) {
        withContext(Dispatchers.Default) { delay(millis) }
    }

    @Test
    fun chatIsMergedFromPollsAndCommands() = runTest {
        val api = FakeGameApi(
            onJoin = { SessionResponse(testSession, testSnapshot(players = players)) },
            onSendChat = { snapshot(time = 5_000, 3, from = testSession.playerId) },
        ) { request -> if (request.chatAfter == 0L) snapshot(2_000, 1, 2) else snapshot(3_000) }
        val manager = manager(api)

        manager.join("ABC234", "Anna")
        manager.state.first { it.chat.isNotEmpty() }
        assertTrue(manager.sendChat("Hi", team = true))

        assertEquals(listOf(1L, 2L, 3L), manager.state.value.chat.map { it.seq })
        val sent = api.chatRequests.single()
        assertEquals(SendChatRequest("Hi", team = true, chatAfter = 2), sent.copy(clientMessageId = null))
        assertTrue(RequestIds.isValid(assertNotNull(sent.clientMessageId)))
    }

    @Test
    fun aMessageWithoutAnAnswerIsSentAgainWithItsId() = runTest {
        var sends = 0
        val api = FakeGameApi(
            onJoin = { SessionResponse(testSession, testSnapshot(players = players)) },
            onSendChat = {
                sends++
                if (sends == 1 || sends == 4) throw Exception("connection reset")
                snapshot(5_000)
            },
        ) { snapshot(2_000) }
        val manager = manager(api)
        manager.join("ABC234", "Anna")

        assertFalse(manager.sendChat("Hi"))
        assertIs<SessionError.Network>(manager.state.value.lastError)
        assertTrue(manager.sendChat("Hi"))
        assertTrue(manager.sendChat("Hi"))
        assertFalse(manager.sendChat("Hi"))
        assertTrue(manager.sendChat("Bye"))

        val ids = api.chatRequests.map { assertNotNull(it.clientMessageId) }
        assertEquals(ids[0], ids[1], "sent again after no answer: the same message")
        assertNotEquals(ids[1], ids[2], "the same text after it went through: a new message")
        assertNotEquals(ids[3], ids[4], "another text after no answer: a new message")
    }

    @Test
    fun aJoinWithoutAnAnswerIsSentAgainWithItsId() = runTest {
        val joins = mutableListOf<JoinGameRequest>()
        val api = FakeGameApi(
            onJoin = { request ->
                joins += request
                when (joins.size) {
                    1 -> throw Exception("connection reset")
                    3 -> throw ApiException(404, ApiError(ErrorCode.NOT_FOUND, "No game with this code"))
                    else -> SessionResponse(testSession, testSnapshot())
                }
            },
        ) { testSnapshot() }
        val manager = manager(api)

        assertFalse(manager.join("abc234 ", "Anna"))
        assertTrue(manager.join("ABC234", "Anna"))
        manager.leave()
        assertFalse(manager.join("XYZ789", "Anna"))
        assertTrue(manager.join("XYZ789", "Anna"))

        val ids = joins.map { assertNotNull(it.requestId) }
        assertTrue(ids.all(RequestIds::isValid), "$ids")
        assertEquals(ids[0], ids[1], "pressed again after no answer: the same request")
        assertNotEquals(ids[1], ids[2], "another game: another request")
        assertNotEquals(ids[2], ids[3], "pressed again after a refusal: a new request")
    }

    @Test
    fun theCursorFollowsTheNewestMessage() = runTest {
        val api = scriptedPolls({ snapshot(2_000, 1, 2) }, { snapshot(3_000, 3) }, { snapshot(4_000) })
        val manager = manager(api)

        manager.join("ABC234", "Anna")
        eventually { api.syncRequests.size >= 3 }

        assertEquals(listOf<Long?>(0, 2, 3), api.syncRequests.take(3).map { it.chatAfter })
        assertEquals(listOf(1L, 2L, 3L), manager.state.value.chat.map { it.seq })
    }

    @Test
    fun anOvertakenPollStillBringsItsMessages() = runTest {
        val api = FakeGameApi(
            onJoin = { SessionResponse(testSession, testSnapshot(serverTimeMillis = 9_000, players = players)) },
        ) { snapshot(time = 2_000, 1) }
        val manager = manager(api)

        manager.join("ABC234", "Anna")
        manager.state.first { it.chat.isNotEmpty() }

        assertEquals(9_000, manager.state.value.snapshot?.serverTimeMillis, "the older snapshot is not shown")
        assertEquals(listOf(1L), manager.state.value.chat.map { it.seq })
    }

    @Test
    fun aNewGameStartsWithAnEmptyChat() = runTest {
        val api = scriptedPolls({ snapshot(2_000, 1, 2) })
        val manager = manager(api)
        manager.join("ABC234", "Anna")
        manager.state.first { it.chat.isNotEmpty() }
        manager.markChatRead()

        manager.leave()
        assertEquals(SessionState(), manager.state.value)
        manager.join("ABC234", "Anna")

        assertEquals(emptyList(), manager.state.value.chat)
        assertEquals(0, manager.state.value.chatReadSeq)
    }

    @Test
    fun unreadUntilMarkedRead() = runTest {
        val api = scriptedPolls({ snapshot(2_000, 1, 2) }, { snapshot(3_000, 3) }, { snapshot(4_000) })
        val manager = manager(api)
        manager.join("ABC234", "Anna")
        manager.state.first { it.chat.isNotEmpty() }
        assertEquals(2, manager.state.value.unreadChatCount())
        assertEquals(0, manager.state.value.unreadChatCount(blocked = setOf(UserId("u2"))), "Bo is blocked")

        manager.markChatRead()
        assertEquals(0, manager.state.value.unreadChatCount())

        manager.state.first { it.chat.size == 3 }
        assertEquals(1, manager.state.value.unreadChatCount())
        assertEquals(2, manager.state.value.chatReadSeq)
    }

    @Test
    fun reportAndInviteGoToTheServer() = runTest {
        val api = FakeGameApi(
            onJoin = { SessionResponse(testSession, testSnapshot(players = players)) },
            onReportChat = { snapshot(5_000) },
            onInvite = { snapshot(5_000) },
        ) { snapshot(2_000) }
        val manager = manager(api)
        manager.join("ABC234", "Anna")

        assertTrue(manager.reportChat(7))
        assertTrue(manager.invite(listOf(UserId("u2")), GroupId("g1")))

        assertEquals(listOf(7L), api.reportedSeqs)
        assertEquals(listOf(InviteRequest(listOf(UserId("u2")), GroupId("g1"))), api.inviteRequests)
    }

    @Test
    fun aRateLimitedMessageKeepsTheReason() = runTest {
        val api = FakeGameApi(
            onJoin = { SessionResponse(testSession, testSnapshot(players = players)) },
            onSendChat = {
                throw ApiException(
                    429,
                    ApiError(ErrorCode.WRONG_STATE, "Slow down", ErrorReason.TOO_MANY_REQUESTS),
                    retryAfterSeconds = 5,
                )
            },
        ) { snapshot(2_000) }
        val manager = manager(api)
        manager.join("ABC234", "Anna")

        assertFalse(manager.sendChat("Hi"))

        assertEquals(
            SessionError.Rejected(ErrorCode.WRONG_STATE, "Slow down", ErrorReason.TOO_MANY_REQUESTS, 5),
            manager.state.value.lastError,
        )
    }

    @Test
    fun afterTheGameEndsPollingGoesOnForTheChat() = runTest {
        val api = scriptedPolls(
            { snapshot(2_000, phase = GamePhase.SEEKING) },
            { snapshot(3_000, 1, phase = GamePhase.FINISHED) },
            { snapshot(4_000, 2, phase = GamePhase.FINISHED) },
            { snapshot(5_000, 3, phase = GamePhase.FINISHED) },
            { snapshot(6_000, phase = GamePhase.FINISHED) },
        )
        val manager = manager(api)
        manager.join("ABC234", "Anna")
        manager.state.first { it.snapshot?.phase == GamePhase.SEEKING }
        runCurrent()
        assertTrue(tracker.running)

        manager.state.first { it.snapshot?.phase == GamePhase.FINISHED }
        runCurrent()
        assertFalse(tracker.running, "no foreground service on the results screen")
        assertEquals(0, locations.collectors, "no location either")
        assertNull(storage.loadSession(), "nothing to resume")

        manager.state.first { it.chat.size == 3 }
        assertTrue(api.syncRequests.drop(2).all { it.samples.isEmpty() })
        assertEquals(testSession, manager.state.value.session)

        manager.leave()
        val polls = api.syncRequests.size
        wait(1_500)
        assertEquals(polls, api.syncRequests.size, "leaving the results stops it")
    }

    @Test
    fun theGameDeletedAfterItEndedIsNoError() = runTest {
        for (status in listOf(401, 404)) {
            val api = scriptedPolls(
                { snapshot(2_000, 1, phase = GamePhase.FINISHED) },
                { ApiException(status, ApiError(ErrorCode.NOT_FOUND, "gone")) },
            )
            val manager = manager(api)
            manager.join("ABC234", "Anna")
            manager.state.first { it.snapshot?.phase == GamePhase.FINISHED }
            eventually { api.syncRequests.size == 2 }
            wait(1_500)

            val state = manager.state.value
            assertNull(state.lastError, "HTTP $status")
            assertEquals(testSession, state.session, "the results stay until the player leaves")
            assertEquals(listOf(1L), state.chat.map { it.seq })
            assertEquals(2, api.syncRequests.size, "HTTP $status: no more polling")
        }
    }

    @Test
    fun theAccountTokenGoesWithCreateAndJoin() = runTest {
        val account = FakeCredentials("account-token")
        val api = FakeGameApi(
            onJoin = { SessionResponse(testSession, testSnapshot()) },
            onCreate = { SessionResponse(testSession, testSnapshot()) },
        ) { testSnapshot() }
        val manager = manager(api, account)

        manager.create("Anna", GameSettings(zone = shrinkingZone(GeoPoint(50.45, 30.52))))
        manager.leave()
        manager.join("ABC234", "Anna")
        manager.leave()
        account.accountToken = null
        manager.join("ABC234", "Anna")

        assertEquals(listOf("account-token", "account-token", null), api.accountTokens)
    }

    @Test
    fun aRejectedAccountTokenLogsOut() = runTest {
        val unauthorized = ApiException(401, ApiError(ErrorCode.UNAUTHORIZED, "Log in", ErrorReason.SESSION_EXPIRED))
        val account = FakeCredentials("account-token")
        val manager = manager(FakeGameApi(onJoin = { throw unauthorized }) { testSnapshot() }, account)

        assertFalse(manager.join("ABC234", "Anna"))

        assertEquals(listOf("account-token"), account.rejected)
        val error = manager.state.value.lastError as SessionError.Rejected
        assertEquals(ErrorReason.SESSION_EXPIRED, error.reason)
    }

    @Test
    fun aGuestIsNeverLoggedOut() = runTest {
        val account = FakeCredentials(null)
        val api = FakeGameApi(onJoin = { throw ApiException(401, ApiError(ErrorCode.UNAUTHORIZED, "?")) }) {
            testSnapshot()
        }

        assertFalse(manager(api, account).join("ABC234", "Anna"))

        assertEquals(emptyList(), account.rejected)
    }
}
