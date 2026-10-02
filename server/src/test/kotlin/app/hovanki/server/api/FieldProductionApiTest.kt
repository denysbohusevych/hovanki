package app.hovanki.server.api

import app.hovanki.server.MutableClock
import app.hovanki.server.account.AccountTestConfig
import app.hovanki.server.features.FeatureFlagRepository
import app.hovanki.server.features.FeatureFlags
import app.hovanki.server.game.GameException
import app.hovanki.server.mail.EmailSender
import app.hovanki.server.mail.RecordingEmailSender
import app.hovanki.shared.protocol.AdminFeatureRequest
import app.hovanki.shared.protocol.AdminFeatures
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.FieldJoinRequest
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LabRunId
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.UserRole
import app.hovanki.shared.rules.shrinkingZone
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * A server that may not have the field log, as production (`hovanki.field.allowed` off,
 * docs/adr/0018-field-test-build.md §9): FIELD_LOG stays off whatever its switch in the database says, and the admin
 * can't turn it on.
 */
@SpringBootTest(properties = ["hovanki.field.allowed=false"])
@AutoConfigureMockMvc
@Import(AccountTestConfig::class)
class FieldProductionApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired emailSender: EmailSender,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val features: FeatureFlags,
    @Autowired private val switches: FeatureFlagRepository,
) {
    private val admin = AdminTestClient(mvc, emailSender as RecordingEmailSender, clock, jdbc)
    private val settings = GameSettings(zone = shrinkingZone(GeoPoint(50.4501, 30.5234)))

    /** The switches live in the database every test context shares: back off for the others. */
    @AfterTest
    fun switchOff() {
        switches.set(ServerFeature.FIELD_LOG, false, "test", clock.instant())
        features.reload()
    }

    @Test
    fun theAdminCannotTurnTheFieldLogOn() {
        val staff = admin.staff(UserRole.ADMIN)
        val route = ApiRoutes.adminFeature(ServerFeature.FIELD_LOG)
        admin.post(route, AdminFeatureRequest(true, "the field test"), staff)
            .error(409, ErrorCode.WRONG_STATE, ErrorReason.FEATURE_DISABLED)
        val listed = admin.get(ApiRoutes.ADMIN_FEATURES, staff).ok<AdminFeatures>()
        assertFalse(listed.features.single { it.feature == ServerFeature.FIELD_LOG }.enabled)
        // Off always works; the other features are switched as before.
        admin.post(route, AdminFeatureRequest(false, "nothing to do"), staff).ok<AdminFeatures>()
        val error = assertFailsWith<GameException> {
            features.set(ServerFeature.FIELD_LOG, true, "test", clock.instant())
        }
        assertEquals(ErrorReason.FEATURE_DISABLED, error.reason)
    }

    @Test
    fun aSwitchOnInTheDatabaseChangesNothing() {
        // Turned on before the server knew better, or by hand in SQL.
        switches.set(ServerFeature.FIELD_LOG, true, "sql", clock.instant())
        features.reload()
        assertFalse(features.isEnabled(ServerFeature.FIELD_LOG))
        assertFalse(ServerFeature.FIELD_LOG.name in features.enabledNames())

        val game = post(ApiRoutes.GAMES, CreateGameRequest("Host", settings).asJson()).ok<SessionResponse>()
        post(
            ApiRoutes.GAME_FIELD_JOIN.replace("{gameId}", game.session.gameId.value),
            FieldJoinRequest(consentAtMillis = clock.millis()).asJson(),
            game.session.token,
        ).error(404, ErrorCode.NOT_FOUND)
        // The upload route is not there either (the lab is off).
        post(ApiRoutes.labEvents(LabRunId("any")), "{}", "nonsense")
            .error(404, ErrorCode.NOT_FOUND)
    }

    private fun post(path: String, json: String, token: String? = null): TestResponse {
        val response = mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = json
            if (token != null) header(HttpHeaders.AUTHORIZATION, "${ApiRoutes.AUTH_SCHEME} $token")
        }.andReturn().response
        return TestResponse(response.status, response.getContentAsString(Charsets.UTF_8))
    }
}
