package app.hovanki.shared.live

import app.hovanki.shared.live.v2.Down
import app.hovanki.shared.live.v2.GamePhase
import app.hovanki.shared.live.v2.GameSection
import app.hovanki.shared.live.v2.Location
import app.hovanki.shared.live.v2.State
import app.hovanki.shared.live.v2.VisibilityReason
import app.hovanki.shared.totp.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * An older phone reads what a newer server sends (docs/live-v2/spec.md §15, §18 item 5): unknown fields, sections and
 * enum values are skipped, the rest reads as before.
 */
class LiveCompatibilityTest {
    @Test
    fun anUnknownFieldIsSkipped() {
        // GameSection with phase = SEEKING, then field 30 (varint 7) that this phone doesn't know.
        val bytes =
            GameSection.ADAPTER.encode(GameSection(phase = GamePhase.GAME_PHASE_SEEKING)) +
                byteArrayOf(0xf0.toByte(), 0x01, 0x07)
        val section = GameSection.ADAPTER.decode(bytes)
        assertEquals(GamePhase.GAME_PHASE_SEEKING, section.phase)
        assertEquals("f00107", section.unknownFields.hex())
    }

    @Test
    fun anUnknownSectionDoesNotStopTheState() {
        // State with game, then section 14 (a message of one varint field) from a newer server.
        val state =
            State.ADAPTER.encode(State(game = GameSection(join_code = "KQ7M"))) + byteArrayOf(0x72, 0x02, 0x08, 0x01)
        val down = Down(state_id = 1, state = State.ADAPTER.decode(state))
        val back = Down.ADAPTER.decode(Down.ADAPTER.encode(down))
        val applied = assertNotNull(LiveStates.apply(LiveStates.EMPTY, assertNotNull(back.state)))
        assertEquals("KQ7M", applied.game?.join_code)
    }

    @Test
    fun anUnknownEnumValueReadsAsZero() {
        // Location.reason = 99 and cause = 98: values this phone doesn't know.
        val bytes = byteArrayOf(0x28, 99, 0x30, 98)
        val location = Location.ADAPTER.decode(bytes)
        assertEquals(VisibilityReason.VISIBILITY_REASON_TEAMMATE, location.reason)
        // The optional one stays unset rather than becoming a wrong value.
        assertEquals(null, location.cause)
    }

    @Test
    fun theWireOfASectionIsStable() {
        // Field numbers are forever (§15): game_id 1, join_code 2, host_id 3, phase 4.
        val section =
            GameSection(game_id = "g1", join_code = "KQ", host_id = "p1", phase = GamePhase.GAME_PHASE_SEEKING)
        assertEquals("0a026731" + "12024b51" + "1a027031" + "2002", GameSection.ADAPTER.encode(section).toHex())
    }
}
