package app.hovanki.device.lab

/**
 * Why Core Haptics stopped its engine (`CHHapticEngineStoppedReason`), by name: the lab's `haptic` event says
 * `engine_stopped: audio_session_interrupt (1)`, not a bare number (docs/adr/0017-radar-techniques-and-big-run.md,
 * step 2). Reason 1 is the audio session's interruption, not a suspension: a locked iPhone's engine stops by it.
 * Pure, so it is tested on the JVM; `IosLabHaptics` only calls it.
 */
object HapticStopReason {
    /** The name of [code] as Apple's `CHHapticEngine.StoppedReason` spells it, in snake case; `unknown` otherwise. */
    fun name(code: Long): String = when (code) {
        1L -> "audio_session_interrupt"
        2L -> "application_suspended"
        3L -> "idle_timeout"
        4L -> "notify_when_finished"
        5L -> "engine_destroyed"
        6L -> "game_controller_disconnect"
        -1L -> "system_error"
        else -> "unknown"
    }

    /** The name and the number: `audio_session_interrupt (1)`. */
    fun describe(code: Long): String = "${name(code)} ($code)"
}
