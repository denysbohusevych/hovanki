package app.hovanki.device

import app.hovanki.shared.protocol.Activity
import app.hovanki.shared.protocol.Carry

/**
 * One second of what [CarryClassifier] reads: [screenOn] (Android: `isInteractive`; iOS: the app active; null: not
 * known), [screenOffByProximity] (the screen is turned off by the proximity sensor while the app stays active: the
 * sensor then says whether it is dark, [near]), [lux] (Android only; not used by the rule yet, logged for the lab),
 * [orientation], [std] (the spread of the acceleration's magnitude over the last seconds, in g) and [activity];
 * null: no reading.
 */
data class CarryInputs(
    val atMillis: Long,
    val screenOn: Boolean?,
    val screenOffByProximity: Boolean = false,
    val near: Boolean? = null,
    val lux: Double? = null,
    val orientation: Orientation? = null,
    val std: Double? = null,
    val activity: Activity? = null,
)

/** Where the phone is, and why in words: `screen_on`, `dark+moving`, `dark+upright`, `flat_still_60s`… */
data class CarryVerdict(val carry: Carry, val reason: String)

/**
 * `carry.v2`: where the phone is by the screen, the proximity sensor and the motion (docs/radio-lab.md §7.3,
 * docs/adr/0017-radar-techniques-and-big-run.md §2.3), the rule the lab checks in the shadow of the game's carry
 * monitor (`carry.v1`) before it may replace it. Unlike `carry.v1`, somebody standing still keeps the phone in the
 * pocket:
 *
 * - the screen lit (on, and not turned off by the proximity sensor over something near) → [Carry.IN_HAND];
 * - otherwise the phone is dark: the screen off, or turned off by the proximity sensor with something [near];
 * - dark, flat screen down with something near → [Carry.UNKNOWN] at once: it lies on a table face down;
 * - dark, flat (screen up or down) and still (std below [TABLE_STD]) for [TABLE_MILLIS] → [Carry.UNKNOWN]: put down;
 * - dark and in the pocket already → it stays there, standing and sitting included;
 * - dark and moved within [MOVE_WINDOW_MILLIS] (std at least [MOVE_STD], or walking or running), or upright, upside
 *   down or tilted → [Carry.IN_POCKET];
 * - dark, flat and not moved → [Carry.UNKNOWN].
 *
 * A second without the screen's state, or without any motion (no [CarryInputs.std] and no orientation), keeps the last
 * state. The thresholds are the plan's guesses (§7.3: a table's «zero» below ~0.03 g, somebody standing ~0.03–0.4);
 * the lab's records decide them. Fed once a second; pure, not thread-safe.
 */
class CarryClassifier {
    private var state = Carry.UNKNOWN
    private var lastMoveAt: Long? = null
    private var flatStillSince: Long? = null

    fun add(inputs: CarryInputs): CarryVerdict {
        val at = inputs.atMillis
        val moving = (inputs.std != null && inputs.std >= MOVE_STD) ||
            inputs.activity == Activity.WALKING ||
            inputs.activity == Activity.RUNNING
        if (moving) lastMoveAt = at
        val flat = inputs.orientation == Orientation.FLAT_UP || inputs.orientation == Orientation.FLAT_DOWN
        flatStillSince = if (flat && inputs.std != null && inputs.std < TABLE_STD) flatStillSince ?: at else null

        val coveredByProximity = inputs.screenOffByProximity && inputs.near == true
        val verdict = when {
            inputs.screenOn == true && !coveredByProximity -> CarryVerdict(Carry.IN_HAND, "screen_on")

            inputs.screenOn == null && !coveredByProximity -> CarryVerdict(state, "no_screen")

            inputs.std == null && inputs.orientation == null -> CarryVerdict(state, "no_motion")

            inputs.orientation == Orientation.FLAT_DOWN && inputs.near == true ->
                CarryVerdict(Carry.UNKNOWN, "flat_down+near")

            flatStillSince?.let { at - it >= TABLE_MILLIS } == true -> CarryVerdict(Carry.UNKNOWN, "flat_still_60s")

            state == Carry.IN_POCKET -> CarryVerdict(Carry.IN_POCKET, "dark+kept")

            lastMoveAt?.let { at - it <= MOVE_WINDOW_MILLIS } == true -> CarryVerdict(Carry.IN_POCKET, "dark+moving")

            !flat && inputs.orientation != null -> CarryVerdict(Carry.IN_POCKET, "dark+${inputs.orientation.key}")

            else -> CarryVerdict(Carry.UNKNOWN, "dark+still")
        }
        state = verdict.carry
        return verdict
    }

    companion object {
        /** g: the spread above which the phone moves (somebody standing still is ~0.03–0.4). */
        const val MOVE_STD = 0.03
        const val MOVE_WINDOW_MILLIS = 10_000L

        /** g: the spread below which a flat phone lies on something that doesn't move. */
        const val TABLE_STD = 0.03
        const val TABLE_MILLIS = 60_000L
    }
}
