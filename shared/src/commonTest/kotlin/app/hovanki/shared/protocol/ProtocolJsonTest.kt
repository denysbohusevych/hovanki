package app.hovanki.shared.protocol

import app.hovanki.shared.rules.shrinkingZone
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtocolJsonTest {
    private val me = PlayerId("p1")
    private val snapshot = GameSnapshot(
        gameId = GameId("g1"),
        joinCode = "ABC123",
        hostId = me,
        phase = GamePhase.LOBBY,
        settings = GameSettings(zone = shrinkingZone(GeoPoint(50.45, 30.52))),
        serverTimeMillis = 1_700_000_000_000,
        players = listOf(PlayerView(me, "Denys", Role.HIDER, PlayerStatus.ACTIVE)),
        me = MyState(me, Role.HIDER, PlayerStatus.ACTIVE),
    )

    @Test
    fun roundTrip() {
        val json = protocolJson.encodeToString(snapshot)
        assertEquals(snapshot, protocolJson.decodeFromString<GameSnapshot>(json))
    }

    @Test
    fun idsAreEncodedAsPlainStringsAndNullsAreOmitted() {
        val json = protocolJson.encodeToString(snapshot)
        assertTrue(""""gameId":"g1"""" in json, json)
        assertTrue("phaseEndsAtMillis" !in json, json)
    }

    @Test
    fun olderClientsIgnoreNewFields() {
        val json = """{"code":"TOO_FAR","message":"Too far","hint":"walk closer"}"""
        assertEquals(ApiError(ErrorCode.TOO_FAR, "Too far"), protocolJson.decodeFromString<ApiError>(json))
    }

    /** What the first app versions know of a revealed player: `reason` without a default, no `cause`. */
    @Serializable
    private data class FirstVersionVisibleLocation(
        val point: GeoPoint,
        val accuracyMeters: Double,
        val atMillis: Long,
        val reason: VisibilityReason,
    )

    @Test
    fun aBuildingRevealStaysReadableForTheFirstAppVersions() {
        val revealed = VisibleLocation(
            GeoPoint(50.45, 30.52),
            8.0,
            1_700_000_000_000,
            reason = VisibilityReason.OUT_OF_ZONE,
            cause = VisibilityReason.INSIDE_BUILDING,
        )
        val json = protocolJson.encodeToString(revealed)

        assertEquals(
            VisibilityReason.OUT_OF_ZONE,
            protocolJson.decodeFromString<FirstVersionVisibleLocation>(json).reason,
        )
        assertEquals(revealed, protocolJson.decodeFromString<VisibleLocation>(json))
    }

    @Test
    fun laterValuesOfDefaultedEnumsFallBackToNull() {
        val location = """{"point":{"lat":50.45,"lon":30.52},"accuracyMeters":8.0,"atMillis":1,""" +
            """"reason":"STALE_SIGNAL","cause":"SOMETHING_NEWER"}"""
        assertEquals(null, protocolJson.decodeFromString<VisibleLocation>(location).cause)

        val withNewState = protocolJson.encodeToString(snapshot).dropLast(1) + ""","buildings":"SOMETHING_NEWER"}"""
        assertEquals(null, protocolJson.decodeFromString<GameSnapshot>(withNewState).buildings)
    }

    @Test
    fun routesAreFilled() {
        assertEquals("/api/v1/games/g1/catches/c1/confirm", ApiRoutes.catchConfirm(GameId("g1"), CatchId("c1")))
        assertEquals("/api/v1/games/g1/buildings", ApiRoutes.buildings(GameId("g1")))
    }
}
