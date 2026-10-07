package app.hovanki.server.api

import app.hovanki.server.account.uniqueName
import app.hovanki.server.moderation.ReportRepository
import app.hovanki.shared.debug.DebugBuildings
import app.hovanki.shared.protocol.AccountSession
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.ChatChannel
import app.hovanki.shared.protocol.ClaimCatchRequest
import app.hovanki.shared.protocol.ConfirmCatchRequest
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.LoginRequest
import app.hovanki.shared.protocol.PauseRequest
import app.hovanki.shared.protocol.PlayAgainRequest
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.SendChatRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.SosRequest
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.TracksResponse
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.ChatRules
import app.hovanki.shared.rules.shrinkingZone
import app.hovanki.shared.totp.catchCodeTotp
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Full HTTP round trips with the shared DTOs and the shared JSON settings, exactly as the app talks to the server. */
@SpringBootTest
@AutoConfigureMockMvc
class GameApiTest(@Autowired private val mvc: MockMvc, @Autowired private val reports: ReportRepository) {
    private val park = GeoPoint(50.4501, 30.5234)
    private val settings = GameSettings(zone = shrinkingZone(park), hidingSeconds = 0)

    @Test
    fun wholeRoundOverHttp() {
        val created = post<SessionResponse>(ApiRoutes.GAMES, CreateGameRequest("Host", settings).toJson())
        val host = created.session
        val lobby = post<SessionResponse>(ApiRoutes.JOIN, JoinGameRequest(created.snapshot.joinCode, "Seeker").toJson())
        val seeker = lobby.session
        assertEquals(listOf("Host", "Seeker"), lobby.snapshot.players.map { it.name })

        post<GameSnapshot>(ApiRoutes.start(host.gameId), StartGameRequest(listOf(seeker.playerId)).toJson(), host)

        // hidingSeconds = 0: the round is already in the seeking phase.
        val hostState = sync(host)
        assertEquals(GamePhase.SEEKING, hostState.phase)
        val secret = assertNotNull(hostState.me.catchCodeSecret)
        sync(seeker)

        post<GameSnapshot>(ApiRoutes.catches(host.gameId), ClaimCatchRequest(host.playerId).toJson(), seeker)
        val claim = sync(seeker).catches.single()
        val code = catchCodeTotp(secret, settings.rules).codeAt(System.currentTimeMillis())
        val after = post<GameSnapshot>(
            ApiRoutes.catchConfirm(host.gameId, claim.id),
            ConfirmCatchRequest(code).toJson(),
            seeker,
        )

        assertEquals(PlayerStatus.CAUGHT, after.players.single { it.id == host.playerId }.status)
        assertEquals(GamePhase.FINISHED, after.phase)
    }

    @Test
    fun pauseAndSosOverHttp() {
        val created = post<SessionResponse>(ApiRoutes.GAMES, CreateGameRequest("Host", settings).toJson())
        val host = created.session
        val seeker = post<SessionResponse>(ApiRoutes.JOIN, JoinGameRequest(created.snapshot.joinCode, "S").toJson())
            .session
        post<GameSnapshot>(ApiRoutes.start(host.gameId), StartGameRequest(listOf(seeker.playerId)).toJson(), host)
        sync(host)
        sync(seeker)

        val paused = post<GameSnapshot>(ApiRoutes.pause(host.gameId), PauseRequest(paused = true).toJson(), host)
        assertNotNull(paused.pause)
        val refused = postRaw(ApiRoutes.pause(host.gameId), PauseRequest(paused = false).toJson(), seeker, 403)
        assertError(refused, ErrorCode.FORBIDDEN, null)

        val called = post<GameSnapshot>(ApiRoutes.sos(host.gameId), SosRequest().toJson(), seeker)
        assertEquals(seeker.playerId, called.sos.single().playerId)
        sync(seeker)
        assertEquals(park, sync(host).sos.single().location?.point)
        val stillSos = postRaw(ApiRoutes.pause(host.gameId), PauseRequest(paused = false).toJson(), host, 409)
        assertError(stillSos, ErrorCode.WRONG_STATE, ErrorReason.SOS_ACTIVE)

        post<GameSnapshot>(
            ApiRoutes.sos(host.gameId),
            SosRequest(active = false, playerId = seeker.playerId).toJson(),
            host,
        )
        val goesOn = post<GameSnapshot>(ApiRoutes.pause(host.gameId), PauseRequest(paused = false).toJson(), host)
        assertNull(goesOn.pause)
        assertTrue(goesOn.sos.isEmpty())
    }

    @Test
    fun oneScanAndTheReplayOverHttp() {
        val created = post<SessionResponse>(ApiRoutes.GAMES, CreateGameRequest("Host", settings).toJson())
        val host = created.session
        val seeker =
            post<SessionResponse>(ApiRoutes.JOIN, JoinGameRequest(created.snapshot.joinCode, "Seeker").toJson()).session
        post<GameSnapshot>(ApiRoutes.start(host.gameId), StartGameRequest(listOf(seeker.playerId)).toJson(), host)
        val secret = assertNotNull(sync(host).me.catchCodeSecret)
        sync(seeker)
        val tracksPath = ApiRoutes.tracks(host.gameId)
        val secretTracks = get(tracksPath, seeker, expectedStatus = 409)
        assertEquals(ErrorCode.WRONG_STATE, protocolJson.decodeFromString<ApiError>(secretTracks).code)

        // The seeker scanned the host's QR code: claim and code in one request.
        val code = catchCodeTotp(secret, settings.rules).codeAt(System.currentTimeMillis())
        val after = post<GameSnapshot>(
            ApiRoutes.catches(host.gameId),
            ClaimCatchRequest(host.playerId, code).toJson(),
            seeker,
        )

        val caught = after.players.single { it.id == host.playerId }
        assertEquals(PlayerStatus.CAUGHT, caught.status)
        assertEquals(seeker.playerId, caught.caughtBy)
        assertEquals(GamePhase.FINISHED, after.phase)
        assertEquals(caught.outAtMillis, after.finishedAtMillis)
        val tracks = protocolJson.decodeFromString<TracksResponse>(get(tracksPath, host, expectedStatus = 200))
        assertEquals(setOf(host.playerId, seeker.playerId), tracks.tracks.map { it.playerId }.toSet())
    }

    @Test
    fun responsesUseTheSharedJsonFormat() {
        val json =
            postRaw(ApiRoutes.GAMES, CreateGameRequest("Host", settings).toJson(), session = null, expectedStatus = 200)
        assertTrue(""""phase":"LOBBY"""" in json, json)
        // kotlinx.serialization (explicitNulls = false) omits nulls; Jackson would write them.
        assertFalse("phaseEndsAtMillis" in json, json)
    }

    @Test
    fun errorsAreApiErrors() {
        val unknownCode =
            postRaw(ApiRoutes.JOIN, JoinGameRequest("NOPE00", "X").toJson(), session = null, expectedStatus = 404)
        assertEquals(ErrorCode.NOT_FOUND, protocolJson.decodeFromString<ApiError>(unknownCode).code)

        val host = post<SessionResponse>(ApiRoutes.GAMES, CreateGameRequest("Host", settings).toJson()).session
        val noToken = postRaw(ApiRoutes.sync(host.gameId), SyncRequest().toJson(), session = null, expectedStatus = 401)
        assertEquals(ErrorCode.UNAUTHORIZED, protocolJson.decodeFromString<ApiError>(noToken).code)

        postRaw(ApiRoutes.GAMES, "{not json", session = null, expectedStatus = 400)
    }

    @Test
    fun unknownPathsAndMethodsAreClientErrors() {
        // Must not end up in the "unexpected error" handler (500 + stack trace in the log).
        val unknownPath = mvc.get("/api/v1/nope").andExpect { status { isNotFound() } }.andReturn().response
        assertEquals(ErrorCode.NOT_FOUND, protocolJson.decodeFromString<ApiError>(unknownPath.contentAsString).code)

        val wrongMethod = mvc.get(ApiRoutes.GAMES).andExpect { status { isMethodNotAllowed() } }.andReturn().response
        assertEquals(ErrorCode.BAD_REQUEST, protocolJson.decodeFromString<ApiError>(wrongMethod.contentAsString).code)
    }

    @Test
    fun buildingsOfTheZoneOverHttp() {
        val created = post<SessionResponse>(ApiRoutes.GAMES, CreateGameRequest("Host", settings).toJson())
        // Tests use the fixed test quarter (src/test/resources/config/application.yaml), which answers at once.
        assertEquals(BuildingsState.READY, created.snapshot.buildings)

        val response = mvc.get(ApiRoutes.buildings(created.session.gameId)) {
            accept = MediaType.APPLICATION_JSON
            header("Authorization", "${ApiRoutes.AUTH_SCHEME} ${created.session.token}")
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        val buildings = protocolJson.decodeFromString<BuildingsResponse>(response)

        assertEquals(DebugBuildings.around(park).buildings, buildings.buildings)
        assertEquals(1, buildings.passages.size)
        mvc.get(ApiRoutes.buildings(created.session.gameId)).andExpect { status { isUnauthorized() } }
    }

    // ---- Accounts (docs/adr/0004-accounts-friends-chat.md) ----
    // None of them confirmed its email (register()): confirming is optional, an account plays right away.

    @Test
    fun loggedInPlayersAreNamedByTheirNickname() {
        val alice = register()
        val created = createGame(alice.token)
        val host = created.snapshot.players.single()
        assertEquals(alice.user.nickname, host.name)
        assertEquals(alice.user.id, host.userId)

        // The typed name doesn't matter, not even an empty one.
        val bob = register()
        val joined = join(created.snapshot.joinCode, bob.token, name = "")
        assertEquals(bob.user.id, joined.snapshot.players.single { it.id == joined.session.playerId }.userId)
        // Guests as before: the name they typed, no account.
        val guest = join(created.snapshot.joinCode, accountToken = null, name = " Guest ")
        assertEquals(
            listOf(alice.user.nickname to alice.user.id, bob.user.nickname to bob.user.id, "Guest" to null),
            guest.snapshot.players.map { it.name to it.userId },
        )
    }

    @Test
    fun aLoggedInPlayerComesBackOnAnotherPhone() {
        val alice = register()
        val created = createGame()
        val host = created.session
        val joinCode = created.snapshot.joinCode
        val first = join(joinCode, alice.token).session
        post<GameSnapshot>(ApiRoutes.start(host.gameId), StartGameRequest(listOf(first.playerId)).toJson(), host)
        assertEquals(GamePhase.SEEKING, sync(first).phase)

        // Too late for anybody new, the game is running.
        postRaw(ApiRoutes.JOIN, JoinGameRequest(joinCode, "Late").toJson(), session = null, expectedStatus = 409)
        postRaw(ApiRoutes.JOIN, JoinGameRequest(joinCode, "").toJson(), register().token, expectedStatus = 409)

        // Alice on her new phone, logged in there with a token of its own: the same player, with a new game token.
        val secondPhone = post<AccountSession>(ApiRoutes.LOGIN, LoginRequest(alice.user.nickname, PASSWORD).toJson())
        val back = join(joinCode, secondPhone.token)
        assertEquals(first.playerId, back.session.playerId)
        assertNotEquals(first.token, back.session.token)
        assertEquals(Role.SEEKER, back.snapshot.me.role)
        assertEquals(2, back.snapshot.players.size)

        // The first phone is out of the game; the others play on.
        val oldToken = postRaw(ApiRoutes.sync(host.gameId), SyncRequest().toJson(), first, expectedStatus = 401)
        assertError(oldToken, ErrorCode.UNAUTHORIZED, reason = null)
        assertEquals(GamePhase.SEEKING, sync(back.session).phase)
        assertEquals(GamePhase.SEEKING, sync(host).phase)
    }

    @Test
    fun aFinishedGameCanBeRejoinedToo() {
        val alice = register()
        val created = createGame()
        val first = join(created.snapshot.joinCode, alice.token)
        playUntilTheHostIsCaught(created.session, seeker = first.session)

        val back = join(created.snapshot.joinCode, alice.token)
        assertEquals(first.session.playerId, back.session.playerId)
        assertEquals(GamePhase.FINISHED, back.snapshot.phase)
    }

    @Test
    fun playAgainWithTheSamePlayers() {
        val alice = register()
        val created = createGame()
        val host = created.session
        val anna = join(created.snapshot.joinCode, alice.token).session
        val boris = join(created.snapshot.joinCode, accountToken = null, name = "Boris").session
        val press = PlayAgainRequest(requestId = "5b1e7c2a9d3f4e6a8b0c1d2e3f405162")
        val early = postRaw(ApiRoutes.playAgain(host.gameId), press.toJson(), host, expectedStatus = 409)
        assertError(early, ErrorCode.WRONG_STATE, reason = null)
        playUntilTheHostIsCaught(host, seeker = anna, otherSeekers = listOf(boris))
        assertNull(sync(boris).playAgain, "nothing to come into yet")

        // Only the host opens the next game.
        val tooSoon = postRaw(ApiRoutes.playAgain(host.gameId), PlayAgainRequest().toJson(), boris, 409)
        assertError(tooSoon, ErrorCode.WRONG_STATE, reason = null)
        val next = post<SessionResponse>(ApiRoutes.playAgain(host.gameId), press.toJson(), host)
        assertNotEquals(host.gameId, next.session.gameId)
        assertEquals(GamePhase.LOBBY, next.snapshot.phase)
        assertEquals(next.session.playerId, next.snapshot.hostId)
        assertEquals(listOf("Host"), next.snapshot.players.map { it.name })
        assertEquals(settings, next.snapshot.settings.copy(openBuildings = null))
        // The answer got lost: the same press gets the same player back.
        val again = post<SessionResponse>(ApiRoutes.playAgain(host.gameId), press.toJson(), host)
        assertEquals(next.session.playerId, again.session.playerId)

        // The others see it on their results and come in with one tap, under their names and accounts.
        assertEquals(next.snapshot.joinCode, sync(boris).playAgain?.joinCode)
        val borisNext = post<SessionResponse>(ApiRoutes.playAgain(host.gameId), PlayAgainRequest().toJson(), boris)
        val annaNext = post<SessionResponse>(ApiRoutes.playAgain(host.gameId), PlayAgainRequest().toJson(), anna)
        assertEquals(next.session.gameId, borisNext.session.gameId)
        assertEquals(
            listOf("Host" to null, "Boris" to null, alice.user.nickname to alice.user.id),
            annaNext.snapshot.players.map { it.name to it.userId },
        )
        // An account comes back as its player.
        val annaBack = post<SessionResponse>(ApiRoutes.playAgain(host.gameId), PlayAgainRequest().toJson(), anna)
        assertEquals(annaNext.session.playerId, annaBack.session.playerId)
        assertEquals(GamePhase.FINISHED, sync(host).phase)
    }

    @Test
    fun aJoinSentAgainGivesBackThePlayer() {
        val created = createGame()
        val request = JoinGameRequest(created.snapshot.joinCode, "Anna", requestId = "0f8fad5bd9cb469fa16570867728950e")
        val first = postAs<SessionResponse>(null, ApiRoutes.JOIN, request.toJson())

        // The answer got lost, the app sends the same request again: the same player, with a new token.
        val again = postAs<SessionResponse>(null, ApiRoutes.JOIN, request.toJson())
        assertEquals(first.session.playerId, again.session.playerId)
        assertNotEquals(first.session.token, again.session.token)
        assertEquals(listOf("Host", "Anna"), again.snapshot.players.map { it.name })
        postRaw(ApiRoutes.sync(created.session.gameId), SyncRequest().toJson(), first.session, expectedStatus = 401)
        assertEquals(2, sync(again.session).players.size)

        // Another press of "Join" has another id: another player.
        val anotherPress = request.copy(requestId = "another-press-0001")
        val other = postAs<SessionResponse>(null, ApiRoutes.JOIN, anotherPress.toJson())
        assertNotEquals(first.session.playerId, other.session.playerId)
        // Guessable ids are refused.
        val guessable = postRaw(ApiRoutes.JOIN, request.copy(requestId = "1").toJson(), token = null, 400)
        assertError(guessable, ErrorCode.BAD_REQUEST, reason = null)
    }

    @Test
    fun unknownAccountTokensOnCreateAndJoin() {
        val joinCode = createGame().snapshot.joinCode
        val unknown = "0".repeat(64)
        val requests = mapOf(
            ApiRoutes.GAMES to CreateGameRequest("X", settings).toJson(),
            ApiRoutes.JOIN to JoinGameRequest(joinCode, "X").toJson(),
        )
        for ((path, json) in requests) {
            val unknownToken = postRaw(path, json, unknown, expectedStatus = 401)
            assertError(unknownToken, ErrorCode.UNAUTHORIZED, ErrorReason.SESSION_EXPIRED)
        }
    }

    // ---- Chat ----

    @Test
    fun chatOverHttp() {
        val created = createGame()
        val host = created.session
        val guest = join(created.snapshot.joinCode, accountToken = null, name = "Guest").session

        val hello = sendChat(host, SendChatRequest(" hello\n", chatAfter = 0)).chat.single()
        assertEquals(Triple(host.playerId, "hello", ChatChannel.ALL), Triple(hello.playerId, hello.text, hello.channel))

        // The others get it with their next sync; the cursor says what they have already.
        assertEquals(listOf(hello), sync(guest, chatAfter = 0).chat)
        assertEquals(emptyList(), sync(guest, chatAfter = hello.seq).chat)
        val reply = sendChat(guest, SendChatRequest("hi", team = true, chatAfter = hello.seq)).chat.single()
        assertEquals("hi" to ChatChannel.ALL, reply.text to reply.channel)
        assertEquals(listOf(hello, reply), sync(host, chatAfter = 0).chat)

        // An app without chat sends no cursor, and gets no chat.
        val oldApp = postRaw(ApiRoutes.sync(host.gameId), """{"samples":[]}""", host, expectedStatus = 200)
        assertTrue(""""chat":[]""" in oldApp && "hello" !in oldApp, oldApp)

        val empty = postRaw(ApiRoutes.chat(host.gameId), SendChatRequest(" \n ").toJson(), host, expectedStatus = 400)
        assertError(empty, ErrorCode.BAD_REQUEST, ErrorReason.INVALID_MESSAGE)
        postRaw(ApiRoutes.chat(host.gameId), SendChatRequest("hi").toJson(), session = null, expectedStatus = 401)
    }

    @Test
    fun aMessageSentAgainIsKeptOnceOverHttp() {
        val host = createGame().session
        val request = SendChatRequest("hello", chatAfter = 0, clientMessageId = "0f8fad5bd9cb469fa16570867728950e")

        val first = sendChat(host, request).chat.single()
        assertEquals(listOf(first), sendChat(host, request).chat)
        assertEquals(listOf(first), sync(host, chatAfter = 0).chat)
        val invalid = postRaw(ApiRoutes.chat(host.gameId), request.copy(clientMessageId = "x").toJson(), host, 400)
        assertError(invalid, ErrorCode.BAD_REQUEST, reason = null)
    }

    @Test
    fun fiveMessagesInTenSecondsOverHttp() {
        val host = createGame().session
        repeat(ChatRules.RATE_LIMIT_MESSAGES) { sendChat(host, SendChatRequest("message $it")) }

        val response = mvc.post(ApiRoutes.chat(host.gameId)) {
            contentType = MediaType.APPLICATION_JSON
            content = SendChatRequest("one more").toJson()
            header("Authorization", "${ApiRoutes.AUTH_SCHEME} ${host.token}")
        }.andReturn().response
        assertEquals(429, response.status)
        assertError(response.contentAsString, ErrorCode.WRONG_STATE, ErrorReason.TOO_MANY_REQUESTS)
        assertTrue(assertNotNull(response.getHeader(HttpHeaders.RETRY_AFTER)).toLong() in 1..10)
    }

    @Test
    fun reportingChatMessages() {
        val alice = register()
        val created = createGame(alice.token)
        val host = created.session
        val guest = join(created.snapshot.joinCode, accountToken = null, name = "Guest").session
        val rude = sendChat(host, SendChatRequest("rude words", chatAfter = 0)).chat.single()

        // A guest reports alice's message; twice is still one report.
        repeat(2) {
            val snapshot = protocolJson.decodeFromString<GameSnapshot>(report(guest, rude.seq))
            assertEquals(guest.playerId, snapshot.me.playerId)
        }
        val stored = reportsOf(host).single()
        assertEquals(rude.seq, stored.messageSeq)
        assertEquals(guest.playerId.value to null, stored.reporterPlayerId to stored.reporterUserId)
        assertEquals(alice.user.id.value to alice.user.nickname, stored.reportedUserId to stored.reportedName)
        assertEquals("rude words", stored.text)

        assertError(report(host, rude.seq, expectedStatus = 403), ErrorCode.FORBIDDEN, reason = null)
        assertError(report(guest, rude.seq + 100, expectedStatus = 404), ErrorCode.NOT_FOUND, reason = null)
        assertError(report(guest, "abc", expectedStatus = 400), ErrorCode.BAD_REQUEST, reason = null)

        // Alice reports the guest's answer.
        val answer = sendChat(guest, SendChatRequest("no u", chatAfter = rude.seq)).chat.single()
        report(host, answer.seq)
        val byAlice = reportsOf(host).single { it.messageSeq == answer.seq }
        assertEquals(host.playerId.value to alice.user.id.value, byAlice.reporterPlayerId to byAlice.reporterUserId)
        assertEquals(null to "Guest", byAlice.reportedUserId to byAlice.reportedName)
        assertEquals(2, reportsOf(host).size)
    }

    private fun sendChat(session: PlayerSession, request: SendChatRequest): GameSnapshot =
        post(ApiRoutes.chat(session.gameId), request.toJson(), session)

    /** Reports message [seq] (a number, or not) as the app does: a POST without a body. */
    private fun report(session: PlayerSession, seq: Any, expectedStatus: Int = 200): String =
        mvc.post("${ApiRoutes.chat(session.gameId)}/$seq/report") {
            header("Authorization", "${ApiRoutes.AUTH_SCHEME} ${session.token}")
        }.andExpect {
            status { isEqualTo(expectedStatus) }
        }.andReturn().response.getContentAsString(Charsets.UTF_8)

    private fun reportsOf(session: PlayerSession) =
        reports.latest(Int.MAX_VALUE).filter { it.gameId == session.gameId.value }

    /** Starts the lobby with [seeker] as the only seeker, who then catches the host, the only hider. */
    private fun playUntilTheHostIsCaught(
        host: PlayerSession,
        seeker: PlayerSession,
        otherSeekers: List<PlayerSession> = emptyList(),
    ): GameSnapshot {
        val seekers = (listOf(seeker) + otherSeekers).map { it.playerId }
        post<GameSnapshot>(ApiRoutes.start(host.gameId), StartGameRequest(seekers).toJson(), host)
        val secret = assertNotNull(sync(host).me.catchCodeSecret)
        sync(seeker)
        post<GameSnapshot>(ApiRoutes.catches(host.gameId), ClaimCatchRequest(host.playerId).toJson(), seeker)
        val claim = sync(seeker).catches.single()
        val code = catchCodeTotp(secret, settings.rules).codeAt(System.currentTimeMillis())
        val after = post<GameSnapshot>(
            ApiRoutes.catchConfirm(host.gameId, claim.id),
            ConfirmCatchRequest(code).toJson(),
            seeker,
        )
        assertEquals(GamePhase.FINISHED, after.phase)
        return after
    }

    private fun assertError(json: String, code: ErrorCode, reason: ErrorReason?) {
        val error = protocolJson.decodeFromString<ApiError>(json)
        assertEquals(code to reason, error.code to error.reason, json)
    }

    /** A game hosted by a guest named "Host", or by [accountToken]'s player. */
    private fun createGame(accountToken: String? = null): SessionResponse =
        postAs(accountToken, ApiRoutes.GAMES, CreateGameRequest("Host", settings).toJson())

    /** Joins [joinCode] as [accountToken]'s player, or as a guest named [name]. */
    private fun join(joinCode: String, accountToken: String?, name: String = ""): SessionResponse =
        postAs(accountToken, ApiRoutes.JOIN, JoinGameRequest(joinCode, name).toJson())

    /** A new account, its email not confirmed: confirming is optional, the account plays right away. */
    private fun register(): AccountSession {
        val nickname = uniqueName("game")
        val request = RegisterRequest(nickname, "$nickname@example.com", PASSWORD)
        return post<AccountSession>(ApiRoutes.ACCOUNTS, request.toJson()).also { assertFalse(it.user.emailVerified) }
    }

    private fun sync(session: PlayerSession, chatAfter: Long? = null): GameSnapshot {
        val fix = LocationSample(park, accuracyMeters = 5.0, timestampMillis = System.currentTimeMillis())
        return post(ApiRoutes.sync(session.gameId), SyncRequest(listOf(fix), chatAfter).toJson(), session)
    }

    private fun get(path: String, session: PlayerSession, expectedStatus: Int): String = mvc.get(path) {
        accept = MediaType.APPLICATION_JSON
        header("Authorization", "${ApiRoutes.AUTH_SCHEME} ${session.token}")
    }.andExpect {
        status { isEqualTo(expectedStatus) }
    }.andReturn().response.getContentAsString(Charsets.UTF_8)

    private inline fun <reified T> T.toJson(): String = protocolJson.encodeToString(this)

    private inline fun <reified T> post(path: String, json: String, session: PlayerSession? = null): T =
        protocolJson.decodeFromString(postRaw(path, json, session, expectedStatus = 200))

    /** POST with [token] (an account or a game token, or none): a 200 with a [T]. */
    private inline fun <reified T> postAs(token: String?, path: String, json: String): T =
        protocolJson.decodeFromString(postRaw(path, json, token, expectedStatus = 200))

    private fun postRaw(path: String, json: String, session: PlayerSession?, expectedStatus: Int): String =
        postRaw(path, json, session?.token, expectedStatus)

    private fun postRaw(path: String, json: String, token: String?, expectedStatus: Int): String = mvc.post(path) {
        contentType = MediaType.APPLICATION_JSON
        accept = MediaType.APPLICATION_JSON
        content = json
        if (token != null) header("Authorization", "${ApiRoutes.AUTH_SCHEME} $token")
    }.andExpect {
        status { isEqualTo(expectedStatus) }
    }.andReturn().response.getContentAsString(Charsets.UTF_8)

    private companion object {
        const val PASSWORD = "correct horse battery"
    }
}
