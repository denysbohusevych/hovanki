package app.hovanki.shared.protocol

import app.hovanki.shared.rules.shrinkingZone
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

    @Test
    fun routesAreFilled() {
        assertEquals("/api/v1/games/g1/catches/c1/confirm", ApiRoutes.catchConfirm(GameId("g1"), CatchId("c1")))
    }
}
