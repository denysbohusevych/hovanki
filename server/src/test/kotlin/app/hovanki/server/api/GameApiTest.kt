package app.hovanki.server.api

import app.hovanki.server.account.awaitCode
import app.hovanki.server.account.uniqueName
import app.hovanki.server.mail.EmailSender
import app.hovanki.server.mail.RecordingEmailSender
import app.hovanki.shared.debug.DebugBuildings
import app.hovanki.shared.protocol.AccountSession
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.BuildingsState
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
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.RegisterRequest
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.protocol.VerifyEmailRequest
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.shrinkingZone
import app.hovanki.shared.totp.catchCodeTotp
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Full HTTP round trips with the shared DTOs and the shared JSON settings, exactly as the app talks to the server. */
@SpringBootTest
@AutoConfigureMockMvc
class GameApiTest(@Autowired private val mvc: MockMvc, @Autowired emailSender: EmailSender) {
    private val emails = emailSender as RecordingEmailSender
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

    @Test
    fun loggedInPlayersAreNamedByTheirNickname() {
        val alice = registerVerified()
        val created = createGame(alice.token)
        val host = created.snapshot.players.single()
        assertEquals(alice.user.nickname, host.name)
        assertEquals(alice.user.id, host.userId)

        // The typed name doesn't matter, not even an empty one.
        val bob = registerVerified()
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
        val alice = registerVerified()
        val created = createGame()
        val host = created.session
        val joinCode = created.snapshot.joinCode
        val first = join(joinCode, alice.token).session
        post<GameSnapshot>(ApiRoutes.start(host.gameId), StartGameRequest(listOf(first.playerId)).toJson(), host)
        assertEquals(GamePhase.SEEKING, sync(first).phase)

        // Too late for anybody new, the game is running.
        postRaw(ApiRoutes.JOIN, JoinGameRequest(joinCode, "Late").toJson(), session = null, expectedStatus = 409)
        postRaw(ApiRoutes.JOIN, JoinGameRequest(joinCode, "").toJson(), registerVerified().token, expectedStatus = 409)

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
        val alice = registerVerified()
        val created = createGame()
        val first = join(created.snapshot.joinCode, alice.token)
        playUntilTheHostIsCaught(created.session, seeker = first.session)

        val back = join(created.snapshot.joinCode, alice.token)
        assertEquals(first.session.playerId, back.session.playerId)
        assertEquals(GamePhase.FINISHED, back.snapshot.phase)
    }

    @Test
    fun accountTokensOnCreateAndJoin() {
        val joinCode = createGame().snapshot.joinCode
        val unverified = register()
        val unknown = "0".repeat(64)
        val requests = mapOf(
            ApiRoutes.GAMES to CreateGameRequest("X", settings).toJson(),
            ApiRoutes.JOIN to JoinGameRequest(joinCode, "X").toJson(),
        )
        for ((path, json) in requests) {
            val notVerified = postRaw(path, json, unverified.token, expectedStatus = 403)
            assertError(notVerified, ErrorCode.FORBIDDEN, ErrorReason.EMAIL_NOT_VERIFIED)
            val unknownToken = postRaw(path, json, unknown, expectedStatus = 401)
            assertError(unknownToken, ErrorCode.UNAUTHORIZED, ErrorReason.SESSION_EXPIRED)
        }
    }

    /** Starts the lobby with [seeker] as the only seeker, who then catches the host, the only hider. */
    private fun playUntilTheHostIsCaught(host: PlayerSession, seeker: PlayerSession): GameSnapshot {
        post<GameSnapshot>(ApiRoutes.start(host.gameId), StartGameRequest(listOf(seeker.playerId)).toJson(), host)
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

    /** A new account with an unconfirmed email. */
    private fun register(): AccountSession {
        val nickname = uniqueName("game")
        return post(ApiRoutes.ACCOUNTS, RegisterRequest(nickname, "$nickname@example.com", PASSWORD).toJson())
    }

    /** A new account, its email confirmed with the emailed code. */
    private fun registerVerified(): AccountSession {
        val session = register()
        val code = emails.awaitCode(session.user.email)
        val profile = postAs<UserProfile>(session.token, ApiRoutes.ME_EMAIL_VERIFY, VerifyEmailRequest(code).toJson())
        return session.copy(user = profile)
    }

    private fun sync(session: PlayerSession): GameSnapshot {
        val fix = LocationSample(park, accuracyMeters = 5.0, timestampMillis = System.currentTimeMillis())
        return post(ApiRoutes.sync(session.gameId), SyncRequest(listOf(fix)).toJson(), session)
    }

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
