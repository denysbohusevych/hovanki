package app.hovanki.device

import app.hovanki.device.lab.HapticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The background modes' ids and the phones without them (docs/radar-run.md, §5.1 and §5.3). */
class BackgroundModesTest {
    @Test
    fun theIdsAreTheOnesTheBigRunNames() {
        assertEquals(listOf("mode.audio", "mode.notification_wake", "mode.live_activity"), ModeIds.ALL)
    }

    @Test
    fun aPhoneWithoutModesRefusesToTurnOneOn() {
        val modes = NoopBackgroundModes()
        assertTrue(modes.available.isEmpty())
        ModeIds.ALL.forEach { id ->
            val result = modes.set(id, on = true)
            assertFalse(result.ok, id)
            assertNotNull(result.error, id)
        }
        // Off is always fine: the lab turns every mode off at the end of a step.
        assertTrue(modes.set(ModeIds.AUDIO, on = false).ok)
        modes.stopAll()
    }

    @Test
    fun noLiveActivityWithoutAHost() {
        val host = NoopLiveActivityHost()
        assertFalse(host.isAvailable)
        assertFalse(host.start("Hovanki lab", "the run is on"))
    }

    @Test
    fun theAudioSessionsHapticsHaveTheirOwnKey() {
        assertEquals("core_haptics_audio", HapticKind.CORE_HAPTICS_AUDIO.key)
        assertFalse(HapticKind.CORE_HAPTICS_AUDIO.isNotification)
    }
}
