package app.hovanki.server.api

import app.hovanki.server.MutableClock
import app.hovanki.server.account.AccountTestConfig
import app.hovanki.server.features.FeatureFlagRepository
import app.hovanki.server.features.FeatureFlags
import app.hovanki.server.game.GameException
import app.hovanki.server.lab.FieldEventWriter
import app.hovanki.server.lab.LabChunks
import app.hovanki.server.lab.LabRunRepository
import app.hovanki.server.mail.EmailSender
import app.hovanki.server.mail.RecordingEmailSender
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.lab.FieldKinds
import app.hovanki.shared.lab.ServerFields
import app.hovanki.shared.lab.ServerKinds
import app.hovanki.shared.protocol.AdminFeatureRequest
import app.hovanki.shared.protocol.AdminFeatures
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.ClaimCatchRequest
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.DeviceReport
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.FeatureMode
import app.hovanki.shared.protocol.FieldJoinRequest
import app.hovanki.shared.protocol.FieldJoinResponse
import app.hovanki.shared.protocol.GameFeatures
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.LabCapabilities
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.UserRole
import app.hovanki.shared.rules.shrinkingZone
import app.hovanki.shared.totp.catchCodeTotp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The test server's shadow-only features (`hovanki.features.shadow-only`, docs/adr/0018-field-test-build.md §3.3):
 * the proximity catch and the pocket stealth never change a game there, whatever their switches say, while the field
 * log still answers what they would have.
 */
@SpringBootTest(properties = ["hovanki.features.shadow-only=PROXIMITY_CATCH,POCKET_STEALTH"])
@AutoConfigureMockMvc
@Import(AccountTestConfig::class)
class FieldShadowApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired emailSender: EmailSender,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val features: FeatureFlags,
    @Autowired private val switches: FeatureFlagRepository,
    @Autowired private val labRuns: LabRunRepository,
    @Autowired private val fieldEventWriter: FieldEventWriter,
) {
    private val admin = AdminTestClient(mvc, emailSender as RecordingEmailSender, clock, jdbc)
    private val park = GeoPoint(50.4501, 30.5234)
    private val withRadar = GameFeatures(radar = FeatureMode.OPTIONAL, hiderSense = true)
    private val settings = GameSettings(
        zone = shrinkingZone(park),
        hidingSeconds = 30,
        seekingSeconds = 300,
        features = withRadar,
    )

    @BeforeTest
    fun serverOn() {
        // What the operator switched on, and what somebody switched on in SQL: the radar and the two shadows.
        for (feature in listOf(ServerFeature.RADAR, ServerFeature.HIDER_SENSE, ServerFeature.FIELD_LOG)) {
            switches.set(feature, true, "test", clock.instant())
        }
        for (feature in listOf(ServerFeature.PROXIMITY_CATCH, ServerFeature.POCKET_STEALTH)) {
            switches.set(feature, true, "sql", clock.instant())
        }
        features.reload()
    }

    /** The switches live in the database every test context shares: back off for the others. */
    @AfterTest
    fun serverOff() {
        for (feature in ServerFeature.entries) switches.set(feature, false, "test", clock.instant())
        features.reload()
    }

    @Test
    fun theShadowsAreNeverOnAndTheAdminCannotTurnThemOn() {
        assertTrue(features.isShadowOnly(ServerFeature.PROXIMITY_CATCH))
        assertTrue(features.isShadowOnly(ServerFeature.POCKET_STEALTH))
        assertFalse(features.isShadowOnly(ServerFeature.RADAR))
        // On in the database, off for the server; the radar itself is untouched.
        assertFalse(features.isEnabled(ServerFeature.PROXIMITY_CATCH))
        assertFalse(features.isEnabled(ServerFeature.POCKET_STEALTH))
        assertTrue(features.isEnabled(ServerFeature.RADAR))
        assertTrue(features.isEnabled(ServerFeature.HIDER_SENSE))

        val staff = admin.staff(UserRole.ADMIN)
        admin.post(
            ApiRoutes.adminFeature(ServerFeature.PROXIMITY_CATCH),
            AdminFeatureRequest(true, "the field test"),
            staff,
        ).error(409, ErrorCode.WRONG_STATE, ErrorReason.FEATURE_DISABLED)
        val error = assertFailsWith<GameException> {
            features.set(ServerFeature.POCKET_STEALTH, true, "test", clock.instant())
        }
        assertEquals(ErrorReason.FEATURE_DISABLED, error.reason)
        val listed = admin.get(ApiRoutes.ADMIN_FEATURES, staff).ok<AdminFeatures>().features.associateBy { it.feature }
        for (feature in listOf(ServerFeature.PROXIMITY_CATCH, ServerFeature.POCKET_STEALTH)) {
            assertTrue(listed.getValue(feature).shadowOnly)
            assertFalse(listed.getValue(feature).enabled)
        }
        assertFalse(listed.getValue(ServerFeature.RADAR).shadowOnly)
        assertTrue(listed.getValue(ServerFeature.RADAR).enabled)
    }

    @Test
    fun aSetupAskingForAShadowIsRefusedAndTheClientsAreNotOfferedIt() {
        for (asking in listOf(withRadar.copy(proximityCatch = true), withRadar.copy(pocketStealth = true))) {
            post(ApiRoutes.GAMES, CreateGameRequest("Host", settings.copy(features = asking)).asJson())
                .error(409, ErrorCode.WRONG_STATE, ErrorReason.FEATURE_DISABLED)
        }
        val game = post(ApiRoutes.GAMES, CreateGameRequest("Host", settings).asJson()).ok<SessionResponse>()
        val offered = game.snapshot.enabledFeatures
        assertTrue(ServerFeature.RADAR.name in offered && ServerFeature.HIDER_SENSE.name in offered, "$offered")
        assertFalse(ServerFeature.PROXIMITY_CATCH.name in offered || ServerFeature.POCKET_STEALTH.name in offered)
    }

    @Test
    fun aClaimNotHeardByTheRadarStillCatchesByCodeAndTheFieldLogSaysItWouldRefuse() {
        val created = post(ApiRoutes.GAMES, CreateGameRequest("Host", settings).asJson()).ok<SessionResponse>()
        val host = created.session
        val guest = post(ApiRoutes.JOIN, JoinGameRequest(created.snapshot.joinCode, "Guest").asJson())
            .ok<SessionResponse>().session
        post(ApiRoutes.start(host.gameId), StartGameRequest(listOf(host.playerId)).asJson(), host.token).expect(200)
        val phone = fieldJoin(host).ok<FieldJoinResponse>()
        fieldJoin(guest).ok<FieldJoinResponse>()
        clock.advance(Duration.ofSeconds(settings.hidingSeconds.toLong()))

        // Both phones have the radar on, 10 m apart by GPS, and never heard each other.
        val device = DeviceReport(Platform.ANDROID, BluetoothState.ON)
        fun sync(session: PlayerSession, at: GeoPoint): GameSnapshot = post(
            ApiRoutes.sync(host.gameId),
            SyncRequest(listOf(LocationSample(at, 5.0, clock.millis())), device = device).asJson(),
            session.token,
        ).ok()
        sync(host, park)
        val hiderView = sync(guest, park.moveBy(10.0, 0.0))
        val code = catchCodeTotp(checkNotNull(hiderView.me.catchCodeSecret), settings.rules).codeAt(clock.millis())

        // The rule is off for the game: the claim with the right code confirms the catch.
        post(ApiRoutes.catches(host.gameId), ClaimCatchRequest(guest.playerId, code).asJson(), host.token)
            .expect(200)
        fieldEventWriter.awaitIdle()

        val server = labRuns.devicesOf(phone.runId.value).single { it.label == FieldKinds.SERVER_DEVICE }
        val claim = LabChunks.lines(labRuns.chunksOf(server.id), labRuns::chunkBody)
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { it["k"]?.jsonPrimitive?.content == ServerKinds.CLAIM }
            .toList()
            .single()
        assertEquals("open", claim[ServerFields.OUTCOME]?.jsonPrimitive?.content)
        assertEquals(false, claim[ServerFields.PROXIMITY]?.jsonPrimitive?.booleanOrNull, "the rule is not on")
        assertEquals(true, claim[ServerFields.RADAR]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, claim[ServerFields.SHADOW_ACCEPT]?.jsonPrimitive?.booleanOrNull, "it would refuse: $claim")
    }

    private fun fieldJoin(session: PlayerSession) = post(
        ApiRoutes.GAME_FIELD_JOIN.replace("{gameId}", session.gameId.value),
        FieldJoinRequest(
            model = "Pixel 8",
            os = "Android 16",
            build = "preview",
            commit = "abc1234",
            capabilities = LabCapabilities(platform = Platform.ANDROID),
            consentAtMillis = clock.millis(),
        ).asJson(),
        session.token,
    )

    private fun post(path: String, json: String, token: String? = null): TestResponse {
        val response = mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = json
            if (token != null) header(HttpHeaders.AUTHORIZATION, "${ApiRoutes.AUTH_SCHEME} $token")
        }.andReturn().response
        return TestResponse(response.status, response.getContentAsString(Charsets.UTF_8))
    }
}
