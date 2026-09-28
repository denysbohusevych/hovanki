package app.hovanki.server.api

import app.hovanki.server.MutableClock
import app.hovanki.server.account.AccountSessionRepository
import app.hovanki.server.account.AccountTestConfig
import app.hovanki.server.account.PasswordHasher
import app.hovanki.server.account.UserRepository
import app.hovanki.server.game.GameRegistry
import app.hovanki.server.game.IdGenerator
import app.hovanki.server.social.TestUser
import app.hovanki.server.social.TestUsers
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.RolesRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.SettingsRequest
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.StreetZoneResponse
import app.hovanki.shared.protocol.StreetZoneState
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.ZoneShape
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.shrinkingZone
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The lobby over HTTP (docs/adr/0009-game-setup-glow-streets.md): roles, setup, leaving, one game per account. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountTestConfig::class)
class LobbyApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val registry: GameRegistry,
    @Autowired users: UserRepository,
    @Autowired sessions: AccountSessionRepository,
    @Autowired hasher: PasswordHasher,
    @Autowired ids: IdGenerator,
    @Autowired jdbc: JdbcClient,
    @Autowired clock: MutableClock,
) {
    private val testUsers = TestUsers(users, sessions, hasher, ids, jdbc, clock)
    private val park = GeoPoint(50.4501, 30.5234)
    private val settings = GameSettings(zone = shrinkingZone(park, steps = 0), hidingSeconds = 0)

    @Test
    fun everybodySeesTheRolesAndTheDraw() {
        val (host, anna, boris) = lobby("Host", "Anna", "Boris")

        post(ApiRoutes.roles(host.gameId), RolesRequest(listOf(boris.playerId)).toJson(), host.token).ok<GameSnapshot>()
        assertEquals(Role.SEEKER, sync(anna).players.single { it.id == boris.playerId }.role)

        val drawn = post(
            ApiRoutes.roles(host.gameId),
            RolesRequest(randomSeekers = 2).toJson(),
            host.token,
        ).ok<GameSnapshot>()
        assertEquals(2, drawn.players.count { it.role == Role.SEEKER })
        assertNotNull(sync(boris).rolesDrawnAtMillis)

        post(ApiRoutes.roles(host.gameId), RolesRequest(randomSeekers = 1).toJson(), anna.token)
            .error(403, ErrorCode.FORBIDDEN)
    }

    @Test
    fun theHostChangesTheSetup() {
        val (host, anna) = lobby("Host", "Anna")
        val larger = settings.copy(
            zone = shrinkingZone(park, initialRadiusMeters = 800.0, steps = 0),
            glowEverySeconds = 300,
            glowForSeconds = 5,
        )

        val snapshot = post(
            ApiRoutes.settings(host.gameId),
            SettingsRequest(larger).toJson(),
            host.token,
        ).ok<GameSnapshot>()

        assertEquals(800.0, snapshot.settings.zone.initial.radiusMeters)
        assertEquals(1, snapshot.mapRevision)
        // The fake building source answers right away: the new zone's buildings are there.
        assertEquals(BuildingsState.READY, sync(anna).buildings)
        post(
            ApiRoutes.settings(host.gameId),
            SettingsRequest(larger).toJson(),
            anna.token,
        ).error(403, ErrorCode.FORBIDDEN)
        val huge = settings.copy(zone = shrinkingZone(park, initialRadiusMeters = 50_000.0, steps = 0))
        post(
            ApiRoutes.settings(host.gameId),
            SettingsRequest(huge).toJson(),
            host.token,
        ).error(400, ErrorCode.BAD_REQUEST)
    }

    @Test
    fun leavingForGood() {
        val (host, anna) = lobby("Host", "Anna")

        post(ApiRoutes.leave(host.gameId), null, host.token).expect(204)

        post(ApiRoutes.sync(host.gameId), SyncRequest().toJson(), host.token).error(401, ErrorCode.UNAUTHORIZED)
        val left = sync(anna)
        assertEquals(listOf(anna.playerId), left.players.map { it.id })
        assertEquals(anna.playerId, left.hostId, "Anna hosts now")
        post(ApiRoutes.leave(anna.gameId), null, anna.token).expect(204)
        assertNull(registry.get(anna.gameId), "the empty lobby is gone")
    }

    @Test
    fun theZoneByStreets() {
        val streets = settings.copy(
            zone = shrinkingZone(park, initialRadiusMeters = 300.0, steps = 0),
            zoneShape = ZoneShape.STREETS,
        )
        val host = post(ApiRoutes.GAMES, CreateGameRequest("Host", streets).toJson(), null).ok<SessionResponse>()

        assertEquals(StreetZoneState.READY, host.snapshot.streetZone)
        val zone = protocolJson.decodeFromString<StreetZoneResponse>(
            get(ApiRoutes.streetZone(host.session.gameId), host.session.token).expect(200).body,
        )
        assertEquals(StreetZoneState.READY, zone.state)
        assertEquals(1, zone.stages.size)
        assertTrue(zone.stages.single().outline.size >= 4)
        val buildings = protocolJson.decodeFromString<BuildingsResponse>(
            get(ApiRoutes.buildings(host.session.gameId), host.session.token).expect(200).body,
        )
        assertEquals(BuildingsState.READY, buildings.state)
    }

    @Test
    fun anAccountPlaysInOneLobbyAtATime() {
        val anna = testUsers.create()
        val boris = testUsers.create()
        val first = createGame(anna)
        val borisInFirst = join(first.snapshot.joinCode, boris).ok<SessionResponse>().session

        // Anna starts another game: she leaves the first lobby, Boris hosts it now.
        val second = createGame(anna)
        val lobby = sync(borisInFirst)
        assertEquals(listOf(borisInFirst.playerId), lobby.players.map { it.id })
        assertEquals(borisInFirst.playerId, lobby.hostId)

        // Boris joins Anna's new game: his old lobby is empty and goes.
        join(second.snapshot.joinCode, boris).ok<SessionResponse>()
        assertNull(registry.get(first.session.gameId))
    }

    @Test
    fun aRoundInProgressIsLeftOnlyOnPurpose() {
        val anna = testUsers.create()
        val boris = testUsers.create()
        val game = createGame(anna)
        val borisPlays = join(game.snapshot.joinCode, boris).ok<SessionResponse>().session
        post(
            ApiRoutes.start(game.session.gameId),
            StartGameRequest(listOf(game.session.playerId)).toJson(),
            game.session.token,
        ).ok<GameSnapshot>()

        post(ApiRoutes.GAMES, CreateGameRequest("", settings).toJson(), boris.token)
            .error(409, ErrorCode.WRONG_STATE, ErrorReason.IN_ANOTHER_GAME)

        post(
            ApiRoutes.GAMES,
            CreateGameRequest("", settings, leaveOtherGame = true).toJson(),
            boris.token,
        ).ok<SessionResponse>()
        val round = sync(game.session)
        assertEquals(GamePhase.FINISHED, round.phase, "Boris was the only hider")
        val out = round.players.single { it.id == borisPlays.playerId }
        assertEquals(PlayerStatus.ELIMINATED, out.status)
        assertTrue(out.left)
    }

    private fun lobby(vararg names: String): List<PlayerSession> {
        val host = post(
            ApiRoutes.GAMES,
            CreateGameRequest(names.first(), settings).toJson(),
            null,
        ).ok<SessionResponse>()
        return listOf(host.session) + names.drop(1).map { name ->
            post(
                ApiRoutes.JOIN,
                JoinGameRequest(host.snapshot.joinCode, name).toJson(),
                null,
            ).ok<SessionResponse>().session
        }
    }

    private fun createGame(user: TestUser): SessionResponse =
        post(ApiRoutes.GAMES, CreateGameRequest("", settings).toJson(), user.token).ok()

    private fun join(joinCode: String, user: TestUser): Response =
        post(ApiRoutes.JOIN, JoinGameRequest(joinCode, "").toJson(), user.token)

    private fun sync(session: PlayerSession): GameSnapshot =
        post(ApiRoutes.sync(session.gameId), SyncRequest().toJson(), session.token).ok()

    private data class Response(val status: Int, val body: String) {
        fun expect(status: Int) = also { assertEquals(status, this.status, body) }

        inline fun <reified T> ok(): T = protocolJson.decodeFromString(expect(200).body)

        fun error(status: Int, code: ErrorCode, reason: ErrorReason? = null) {
            val error = protocolJson.decodeFromString<ApiError>(expect(status).body)
            assertEquals(code, error.code, body)
            if (reason != null) assertEquals(reason, error.reason, body)
        }
    }

    private inline fun <reified T> T.toJson(): String = protocolJson.encodeToString(this)

    private fun post(path: String, json: String?, token: String?): Response {
        val response = mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            if (json != null) content = json
            if (token != null) header("Authorization", "${ApiRoutes.AUTH_SCHEME} $token")
        }.andReturn().response
        return Response(response.status, response.getContentAsString(Charsets.UTF_8))
    }

    private fun get(path: String, token: String?): Response {
        val response = mvc.get(path) {
            if (token != null) header("Authorization", "${ApiRoutes.AUTH_SCHEME} $token")
        }.andReturn().response
        return Response(response.status, response.getContentAsString(Charsets.UTF_8))
    }
}
