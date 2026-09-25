package app.hovanki.server.api

import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ClaimCatchRequest
import app.hovanki.shared.protocol.ConfirmCatchRequest
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.shrinkingZone
import app.hovanki.shared.totp.catchCodeTotp
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Full HTTP round trips with the shared DTOs and the shared JSON settings, exactly as the app talks to the server. */
@SpringBootTest
@AutoConfigureMockMvc
class GameApiTest(@Autowired private val mvc: MockMvc) {
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

    private fun sync(session: PlayerSession): GameSnapshot {
        val fix = LocationSample(park, accuracyMeters = 5.0, timestampMillis = System.currentTimeMillis())
        return post(ApiRoutes.sync(session.gameId), SyncRequest(listOf(fix)).toJson(), session)
    }

    private inline fun <reified T> T.toJson(): String = protocolJson.encodeToString(this)

    private inline fun <reified T> post(path: String, json: String, session: PlayerSession? = null): T =
        protocolJson.decodeFromString(postRaw(path, json, session, expectedStatus = 200))

    private fun postRaw(path: String, json: String, session: PlayerSession?, expectedStatus: Int): String =
        mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = json
            if (session != null) header("Authorization", "${ApiRoutes.AUTH_SCHEME} ${session.token}")
        }.andExpect {
            status { isEqualTo(expectedStatus) }
        }.andReturn().response.contentAsString
}
