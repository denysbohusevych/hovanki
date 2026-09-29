package app.hovanki.shared.protocol

import app.hovanki.shared.rules.shrinkingZone
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The live channel's frames on the wire (docs/adr/0015-websockets.md, section 2). */
class SocketTest {
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
    fun framesSayTheirType() {
        val sync: ClientFrame = ClientFrame.Sync(17, SyncRequest(chatAfter = 3))
        val json = protocolJson.encodeToString(ClientFrame.serializer(), sync)
        assertEquals(
            """{"type":"sync","seq":17,"request":{"samples":[],"chatAfter":3,"nearby":[]}}""",
            json,
        )
        assertEquals(sync, protocolJson.decodeFromString(ClientFrame.serializer(), json))
        assertEquals("""{"type":"poke"}""", protocolJson.encodeToString(ServerFrame.serializer(), ServerFrame.Poke))
    }

    @Test
    fun serverFramesRoundTrip() {
        val frames = listOf(
            ServerFrame.Snapshot(17, snapshot),
            ServerFrame.Poke,
            ServerFrame.Error(18, ApiError(ErrorCode.BAD_REQUEST, "Too many samples")),
            ServerFrame.Error(
                19,
                ApiError(ErrorCode.WRONG_STATE, "Slow down", ErrorReason.TOO_MANY_REQUESTS),
                retryAfterSeconds = 2,
            ),
        )
        for (frame in frames) {
            val json = protocolJson.encodeToString(ServerFrame.serializer(), frame)
            assertEquals(frame, protocolJson.decodeFromString(ServerFrame.serializer(), json))
        }
    }

    @Test
    fun aNewerFrameIsUnknownButNewerFieldsAreNot() {
        // The reader skips a frame it can't read: a newer side's new type.
        assertFailsWith<SerializationException> {
            protocolJson.decodeFromString(ServerFrame.serializer(), """{"type":"delta","seq":1}""")
        }
        val withMore = """{"type":"poke","reason":"chat"}"""
        assertEquals(ServerFrame.Poke, protocolJson.decodeFromString(ServerFrame.serializer(), withMore))
    }
}
