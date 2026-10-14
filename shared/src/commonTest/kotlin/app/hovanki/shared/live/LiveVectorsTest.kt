package app.hovanki.shared.live

import app.hovanki.shared.live.v2.Down
import app.hovanki.shared.live.v2.MeSection
import app.hovanki.shared.live.v2.Role
import app.hovanki.shared.live.v2.State
import app.hovanki.shared.live.v2.TokensSection
import app.hovanki.shared.live.v2.Up
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.NearbySighting
import app.hovanki.shared.totp.hexToBytes
import app.hovanki.shared.totp.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The test vectors of docs/live-v2/spec.md §18 (docs/live-v2/vectors.py prints them): the bytes of the schema, the
 * header, the nonce and the RESET are the same in Kotlin. The encrypted datagrams are checked in jvmTest
 * (`LiveAeadVectorsTest`).
 */
class LiveVectorsTest {
    @Test
    fun up() {
        val up =
            LiveUplink.up(
                seq = 1,
                ackState = 0,
                timeMillis = TIME,
                samples =
                listOf(
                    LocationSample(GeoPoint(50.4501, 30.5234), 4.2, TIME - 1_200),
                    LocationSample(GeoPoint(50.4501009, 30.5233986), 3.8, TIME - 200),
                ),
                nearby = listOf(NearbySighting("3fa91c07", -71, TIME - 850)),
                chatAfter = 0,
            )
        assertEquals(UP, Up.ADAPTER.encode(up).toHex())
        assertEquals(54, Up.ADAPTER.encode(up).size)
    }

    @Test
    fun upBackOnTheServer() {
        val up = Up.ADAPTER.decode(UP.hexToBytes())
        assertEquals(
            listOf(
                LocationSample(GeoPoint(50.4501, 30.5234), 4.2, TIME - 1_200),
                LocationSample(GeoPoint(50.4501009, 30.5233986), 3.8, TIME - 200),
            ),
            LiveUplink.samples(up),
        )
        assertEquals(listOf(NearbySighting("3fa91c07", -71, TIME - 850)), LiveUplink.nearby(up))
        assertEquals(0L, up.chat_after)
    }

    @Test
    fun down() {
        val down =
            Down(
                reply_to = 1,
                ack_up = 1,
                server_time_ms = TIME + 150,
                state_id = 1,
                state =
                State(
                    me = MeSection(player_id = "p7Qk2Lm9Xa1B", role = Role.ROLE_SEEKER, radar_on = true),
                    tokens = TokensSection(hider = listOf(0x3fa91c07, 0x0b44e2d1)),
                ),
            )
        assertEquals(DOWN, Down.ADAPTER.encode(down).toHex())
        assertEquals(down, Down.ADAPTER.decode(DOWN.hexToBytes()))
    }

    @Test
    fun headerAndNonce() {
        assertEquals("21a1b2c3d4e5f6071800000001", LiveDatagrams.header(SESSION, 1).toHex())
        assertEquals("000000000000000000000001", LiveDatagrams.nonce(1).toHex())
    }

    @Test
    fun reset() {
        val tag = "5db9c3d4b54d3a1a55839d6ff976fe7d".hexToBytes()
        val reset = LiveDatagrams.reset(SESSION, tag)
        assertEquals("22a1b2c3d4e5f607185db9c3d4b54d3a1a55839d6ff976fe7d", reset.toHex())
        assertTrue(LiveDatagrams.isReset(reset, SESSION, tag))
    }

    companion object {
        const val TIME = 1_760_457_600_000L
        const val SESSION = -0x5e4d3c2b1a09f8e8L // a1b2c3d4e5f60718
        const val UP =
            "08011880d8cc9b9e3322110890cc90e10310a0818ca302182a20df1222090812101b1826208f03" +
                "2a0b0d071ca93f108d0118a30d3800"
        const val DOWN =
            "080110011896d9cc9b9e332001322022120a0c7037516b324c6d3958613142100158013a0a1208071ca93fd1e2440b"
    }
}
