package app.hovanki.device.lab

import kotlin.test.Test
import kotlin.test.assertEquals

/** Core Haptics' stop reasons by name (docs/adr/0017-radar-techniques-and-big-run.md, step 2). */
class HapticStopReasonTest {
    @Test
    fun namesEveryReasonApplePublishes() {
        val names = mapOf(
            1L to "audio_session_interrupt",
            2L to "application_suspended",
            3L to "idle_timeout",
            4L to "notify_when_finished",
            5L to "engine_destroyed",
            6L to "game_controller_disconnect",
            -1L to "system_error",
        )
        names.forEach { (code, name) -> assertEquals(name, HapticStopReason.name(code), "reason $code") }
    }

    @Test
    fun anUnknownCodeKeepsItsNumber() {
        assertEquals("unknown", HapticStopReason.name(0))
        assertEquals("unknown", HapticStopReason.name(42))
        assertEquals("unknown (42)", HapticStopReason.describe(42))
    }

    @Test
    fun describesTheNameAndTheNumber() {
        assertEquals("audio_session_interrupt (1)", HapticStopReason.describe(1))
        assertEquals("system_error (-1)", HapticStopReason.describe(-1))
    }
}
