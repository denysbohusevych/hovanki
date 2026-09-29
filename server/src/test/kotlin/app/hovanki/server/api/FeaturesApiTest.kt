package app.hovanki.server.api

import app.hovanki.server.features.FeatureFlags
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.CustomQuestRequest
import app.hovanki.shared.protocol.DeviceReport
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.FeatureMode
import app.hovanki.shared.protocol.GameFeatures
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ItemKind
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.NearbySighting
import app.hovanki.shared.protocol.PerkKind
import app.hovanki.shared.protocol.PlaceItemRequest
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.QuestKind
import app.hovanki.shared.protocol.QuestReviewRequest
import app.hovanki.shared.protocol.QuestStatus
import app.hovanki.shared.protocol.ScanCheckpointRequest
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.SettingsRequest
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.UsePerkRequest
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.shrinkingZone
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The server features over HTTP (docs/adr/0012-nearby-radar.md, docs/adr/0013-quests-sparks-and-sensors.md): a
 * setup uses only what the operator turned on, and the board's routes.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FeaturesApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val features: FeatureFlags,
    @Autowired private val clock: Clock,
) {
    private val park = GeoPoint(50.4501, 30.5234)
    private val settings = GameSettings(zone = shrinkingZone(park, steps = 0), hidingSeconds = 0)

    /** Exactly [enabled] on, everything else off (the tests share one database). */
    private fun enable(vararg enabled: ServerFeature) {
        for (feature in ServerFeature.entries) {
            if (features.isEnabled(feature) != (feature in enabled)) {
                features.set(feature, feature in enabled, "test", clock.instant())
            }
        }
    }

    @Test
    fun aSetupUsesOnlyWhatTheOperatorTurnedOn() {
        enable()
        val radar = settings.copy(features = GameFeatures(radar = FeatureMode.OPTIONAL, hiderSense = true))
        post(ApiRoutes.GAMES, CreateGameRequest("Host", radar).toJson(), null)
            .error(409, ErrorCode.WRONG_STATE, ErrorReason.FEATURE_DISABLED)
        val plain = post(ApiRoutes.GAMES, CreateGameRequest("Host", settings).toJson(), null).ok<SessionResponse>()
        assertEquals(emptyList(), plain.snapshot.enabledFeatures)

        enable(ServerFeature.RADAR, ServerFeature.HIDER_SENSE)
        val host = post(ApiRoutes.GAMES, CreateGameRequest("Host", radar).toJson(), null).ok<SessionResponse>()
        assertEquals(listOf("HIDER_SENSE", "RADAR"), host.snapshot.enabledFeatures)
        assertEquals(radar.features, host.snapshot.settings.features)
        // A claim up close needs its own switch.
        val upClose = radar.copy(features = radar.features.copy(proximityCatch = true))
        post(ApiRoutes.settings(host.session.gameId), SettingsRequest(upClose).toJson(), host.session.token)
            .error(409, ErrorCode.WRONG_STATE, ErrorReason.FEATURE_DISABLED)
        // What the phone says about itself and hears goes with the sync; everybody sees the abilities.
        val guest = post(ApiRoutes.JOIN, JoinGameRequest(host.snapshot.joinCode, "Guest").toJson(), null)
            .ok<SessionResponse>().session
        val report = DeviceReport(Platform.ANDROID, BluetoothState.ON)
        val sighting = NearbySighting("0123abcd", -70, clock.millis())
        val synced = post(
            ApiRoutes.sync(guest.gameId),
            SyncRequest(device = report, nearby = listOf(sighting)).toJson(),
            guest.token,
        ).ok<GameSnapshot>()
        val me = synced.players.single { it.id == guest.playerId }
        assertEquals(BluetoothState.ON, assertNotNull(me.capabilities).bluetooth)
        assertEquals(Platform.ANDROID, me.capabilities?.platform)
        val flood = SyncRequest(nearby = List(201) { sighting })
        post(ApiRoutes.sync(guest.gameId), flood.toJson(), guest.token).error(400, ErrorCode.BAD_REQUEST)
        enable()
    }

    @Test
    fun theBoardOverHttp() {
        enable(ServerFeature.QUESTS, ServerFeature.CHECKPOINTS, ServerFeature.PICKUPS, ServerFeature.PERKS)
        val board = settings.copy(
            features = GameFeatures(quests = true, checkpoints = true, pickups = true, perks = true),
            quests = listOf(QuestKind.SPRINT),
        )
        val host = post(ApiRoutes.GAMES, CreateGameRequest("Host", board).toJson(), null).ok<SessionResponse>()
        val gameId = host.session.gameId
        val guest = post(ApiRoutes.JOIN, JoinGameRequest(host.snapshot.joinCode, "Guest").toJson(), null)
            .ok<SessionResponse>().session

        // Items: the host places them, the snapshot has them, the code of a scan checkpoint for the host only.
        val checkpoint = PlaceItemRequest(ItemKind.CHECKPOINT_SCAN, park, name = "Fountain")
        post(ApiRoutes.items(gameId), checkpoint.toJson(), guest.token).error(403, ErrorCode.FORBIDDEN)
        val placed = post(ApiRoutes.items(gameId), checkpoint.toJson(), host.session.token).ok<GameSnapshot>()
        val item = placed.items.single()
        assertEquals("Fountain", item.name)
        assertNotNull(item.code)
        assertNull(sync(guest).items.single().code)
        val pickup = PlaceItemRequest(ItemKind.PICKUP, park, perk = PerkKind.SENSE)
        val two = post(ApiRoutes.items(gameId), pickup.toJson(), host.session.token).ok<GameSnapshot>()
        assertEquals(2, two.items.size)
        val one = post(ApiRoutes.itemRemove(gameId, two.items.last().id), null, host.session.token).ok<GameSnapshot>()
        assertEquals(listOf(item.id), one.items.map { it.id })

        // The host's own quest: said done in the round, confirmed by the host, sparks for it.
        val quest = CustomQuestRequest("Take a selfie with a dog", sparks = 3)
        post(ApiRoutes.quests(gameId), quest.toJson(), guest.token).error(403, ErrorCode.FORBIDDEN)
        val withQuest = post(ApiRoutes.quests(gameId), quest.toJson(), host.session.token).ok<GameSnapshot>()
        val questId = withQuest.quests.single { it.kind == QuestKind.CUSTOM }.id
        post(ApiRoutes.questDone(gameId, questId), null, guest.token)
            .error(409, ErrorCode.WRONG_STATE, ErrorReason.QUEST_NOT_ACTIVE)
        val started = post(
            ApiRoutes.start(gameId),
            StartGameRequest(listOf(host.session.playerId)).toJson(),
            host.session.token,
        ).ok<GameSnapshot>()
        assertEquals(GamePhase.SEEKING, started.phase)
        val said = post(ApiRoutes.questDone(gameId, questId), null, guest.token).ok<GameSnapshot>()
        assertEquals(QuestStatus.PENDING_REVIEW, said.quests.single { it.id == questId }.status)
        val review = QuestReviewRequest(guest.playerId, approved = true)
        post(ApiRoutes.questReview(gameId, questId), review.toJson(), guest.token).error(403, ErrorCode.FORBIDDEN)
        val reviewed = post(
            ApiRoutes.questReview(gameId, questId),
            review.toJson(),
            host.session.token,
        ).ok<GameSnapshot>()
        assertEquals(3, reviewed.players.single { it.id == guest.playerId }.sparks)
        val mine = sync(guest)
        assertEquals(3, mine.me.sparks)
        assertEquals(QuestStatus.DONE, mine.quests.single { it.id == questId }.status)
        assertEquals(listOf(QuestKind.SPRINT, QuestKind.CUSTOM), mine.quests.map { it.kind })

        // A checkpoint's code that is not this game's; a perk bought for the sparks, then the cooldown.
        post(ApiRoutes.checkpointScan(gameId), ScanCheckpointRequest("NOPE").toJson(), guest.token)
            .error(404, ErrorCode.NOT_FOUND)
        val sense = UsePerkRequest(PerkKind.SENSE)
        post(ApiRoutes.perks(gameId), sense.toJson(), host.session.token)
            .error(409, ErrorCode.WRONG_STATE, ErrorReason.PERK_UNAVAILABLE)
        val used = post(ApiRoutes.perks(gameId), sense.toJson(), guest.token).ok<GameSnapshot>()
        assertEquals(1, used.me.sparks)
        assertNotNull(used.me.hint)
        post(ApiRoutes.perks(gameId), sense.toJson(), guest.token)
            .error(409, ErrorCode.WRONG_STATE, ErrorReason.PERK_UNAVAILABLE)
        enable()
    }

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
}
