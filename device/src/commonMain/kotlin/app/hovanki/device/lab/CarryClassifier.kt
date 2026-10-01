package app.hovanki.device.lab

import app.hovanki.shared.protocol.Carry

/**
 * What the phone's sensors say in a second, for [CarryClassifier]: [screenOn] the screen on (Android: interactive;
 * iOS: the app active), [near] the proximity sensor covered, [orientation] how it lies, [std] the spread of the
 * acceleration over the last seconds in g (`MotionWindow`), [moving] walking or running (`ActivityClassifier`). Null:
 * the phone doesn't say.
 */
data class CarrySignals(
    val atMillis: Long,
    val screenOn: Boolean?,
    val near: Boolean? = null,
    val orientation: Orientation? = null,
    val std: Double? = null,
    val moving: Boolean? = null,
)

/** [state] and in a word [why]: `screen`, `moved`, `upright`, `stays`, `table`, `face_down`, `still`, `unknown`. */
data class CarryVerdict(val state: Carry, val why: String)

/**
 * `carry.v2`, the pocket's candidate classifier (docs/radio-lab.md §7.3, ADR 0017 §2.3 «Конкурируют»), run in the
 * shadow beside the game's monitors (`carry.v1`) and only written to the journal. The hypothesis, to be checked on
 * the recordings:
 *
 * - **in the hand** while the screen is on and the proximity sensor uncovered;
 * - **into the pocket** with the screen off (or covered: a screen the proximity sensor turned off) when the phone
 *   moved within [movedMillis], or lies upright, upside down or tilted rather than flat;
 * - **stays in the pocket** while the screen is off, standing and sitting included: the game's monitors lose a still
 *   hider after 3 s;
 * - **out of it** when it lies flat and quite still ([stillStd]) longer than [tableMillis] (put on a table), or at
 *   once face down, still and covered.
 *
 * Pure: the platforms feed it ([CarrySignals]) once a second. Not thread-safe.
 */
class CarryClassifier(
    private val movedMillis: Long = MOVED_MILLIS,
    private val stillStd: Double = STILL_STD,
    private val tableMillis: Long = TABLE_MILLIS,
) {
    private var state = Carry.UNKNOWN
    private var lastMovedAt: Long? = null
    private var flatStillSince: Long? = null

    fun classify(signals: CarrySignals): CarryVerdict {
        val now = signals.atMillis
        val still = signals.std != null && signals.std < stillStd
        if (signals.moving == true || (signals.std != null && !still)) lastMovedAt = now
        val flat = signals.orientation == Orientation.FLAT_UP || signals.orientation == Orientation.FLAT_DOWN
        flatStillSince = if (flat && still) flatStillSince ?: now else null
        val verdict = when {
            signals.screenOn == null -> CarryVerdict(Carry.UNKNOWN, "unknown")

            signals.screenOn && signals.near != true -> CarryVerdict(Carry.IN_HAND, "screen")

            signals.orientation == Orientation.FLAT_DOWN && signals.near == true && still ->
                CarryVerdict(Carry.UNKNOWN, "face_down")

            flatStillSince?.let { now - it >= tableMillis } == true -> CarryVerdict(Carry.UNKNOWN, "table")

            state == Carry.IN_POCKET -> CarryVerdict(Carry.IN_POCKET, "stays")

            lastMovedAt?.let { now - it <= movedMillis } == true -> CarryVerdict(Carry.IN_POCKET, "moved")

            signals.orientation != null && !flat -> CarryVerdict(Carry.IN_POCKET, "upright")

            else -> CarryVerdict(Carry.UNKNOWN, "still")
        }
        state = verdict.state
        return verdict
    }

    companion object {
        const val MOVED_MILLIS = 30_000L

        /** Below this spread (g) the phone lies on something; a standing person's pocket sways more (radio-lab §7.3). */
        const val STILL_STD = 0.03
        const val TABLE_MILLIS = 60_000L
    }
}
