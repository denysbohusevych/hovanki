package app.hovanki.client.lab

import app.hovanki.shared.lab.LabPlaces
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One step of a lab scenario: what to do ([hint]) for [seconds], and the truth the merge compares the sensors with
 * ([place], [action], [distance]).
 */
data class LabStep(
    val label: String,
    val seconds: Int,
    val place: String? = null,
    val action: String? = null,
    val distance: Double? = null,
    val hint: String = "",
)

/** An experiment of docs/radio-lab.md §8 as a list of steps with timers. */
data class LabScenario(val id: String, val title: String, val steps: List<LabStep>)

/** The scenarios built into the debug build (docs/radio-lab.md §8), so nothing has to be made up in the street. */
object LabScenarios {
    private fun step(label: String, seconds: Int, place: String? = null, action: String? = null, hint: String = "") =
        LabStep(label, seconds, place, action, hint = hint)

    val E1 = LabScenario(
        "e1",
        "E1 overflow mask, Mac 1 m",
        listOf(
            step("on screen", 60, LabPlaces.HAND, LabPlaces.STAND, "Probe: pattern 0x5A. Mac: --sniff. 1 m away."),
            step("locked", 180, LabPlaces.TABLE_UP, LabPlaces.LIE, "Lock the phone, leave it next to the Mac."),
        ),
    )

    val E2 = LabScenario(
        "e2",
        "E2 mask survives a token change",
        listOf(
            step(
                "locked, token changes",
                180,
                LabPlaces.TABLE_UP,
                LabPlaces.LIE,
                "Probe: token. Press «Rotate in 60 s», then lock right away.",
            ),
            step("unlocked", 60, LabPlaces.HAND, LabPlaces.STAND, "Unlock and keep the lab on screen."),
        ),
    )

    val E3 = LabScenario(
        "e3",
        "E3 vibration from the background",
        listOf(
            step("in hand, locked", 60, LabPlaces.HAND_LOCKED, LabPlaces.STAND, "Start the vibration test, lock."),
            step("felt? (hand)", 30, LabPlaces.HAND, LabPlaces.STAND, "Unlock, press the groups you felt."),
            step("in pocket, locked", 60, LabPlaces.POCKET_FRONT, LabPlaces.STAND, "Start the test again, pocket."),
            step("felt? (pocket)", 30, LabPlaces.HAND, LabPlaces.STAND, "Unlock, press the groups you felt."),
            step("without GPS", 60, LabPlaces.HAND_LOCKED, LabPlaces.STAND, "«As in a game» off, test, lock."),
            step("felt? (no GPS)", 30, LabPlaces.HAND, LabPlaces.STAND, "Unlock, press the groups you felt."),
        ),
    )

    val E4 = LabScenario(
        "e4",
        "E4 screen off by the proximity sensor",
        listOf(
            step("walk", 300, LabPlaces.POCKET_PROXIMITY, LabPlaces.WALK, "Proximity on, pulse haptics, pocket."),
            step("stand", 180, LabPlaces.POCKET_PROXIMITY, LabPlaces.STAND),
            step("sit", 180, LabPlaces.POCKET_PROXIMITY, LabPlaces.SIT),
            step("upside down, walk", 240, LabPlaces.POCKET_PROXIMITY, LabPlaces.WALK, "Turn the phone upside down."),
        ),
    )

    /** E5 on an iPhone, E10 on Android: the carry classifier's truth (docs/radio-lab.md §8). */
    val POCKET = LabScenario(
        "pocket",
        "Pocket (E5 / E10)",
        listOf(
            step("hand, screen on", 90, LabPlaces.HAND, LabPlaces.WALK, "Walk with the phone in hand."),
            step("front pocket, walk", 90, LabPlaces.POCKET_FRONT, LabPlaces.WALK, "Lock, front pocket, walk."),
            step("front pocket, stand still", 120, LabPlaces.POCKET_FRONT, LabPlaces.STAND, "Stand still."),
            step("front pocket, sit", 90, LabPlaces.POCKET_FRONT, LabPlaces.SIT, "Sit down."),
            step("back pocket, walk", 60, LabPlaces.POCKET_BACK, LabPlaces.WALK),
            step("back pocket, stand", 60, LabPlaces.POCKET_BACK, LabPlaces.STAND),
            step("jacket, walk", 60, LabPlaces.JACKET, LabPlaces.WALK),
            step("jacket, stand", 60, LabPlaces.JACKET, LabPlaces.STAND),
            step("backpack, walk", 60, LabPlaces.BACKPACK, LabPlaces.WALK),
            step("backpack, stand", 60, LabPlaces.BACKPACK, LabPlaces.STAND),
            step("table, screen up", 120, LabPlaces.TABLE_UP, LabPlaces.LIE, "Lay it on a table, screen up."),
            step("table, screen down", 120, LabPlaces.TABLE_DOWN, LabPlaces.LIE, "Screen down."),
            step("hand, locked, arm down", 90, LabPlaces.HAND_LOCKED, LabPlaces.WALK, "Locked, arm along the body."),
            step(
                "pocket, screen off by proximity",
                120,
                LabPlaces.POCKET_PROXIMITY,
                LabPlaces.WALK,
                "Not locked: proximity on, pocket; walk, then stand.",
            ),
        ),
    )

    val E6 = LabScenario(
        "e6",
        "E6 / E9 iBeacon ranging while locked",
        listOf(
            step("on screen", 60, LabPlaces.HAND, LabPlaces.STAND, "Bench as hider, «as in a game». Beacon 1 m."),
            step("locked", 300, LabPlaces.TABLE_UP, LabPlaces.LIE, "Lock."),
            step("on screen again", 60, LabPlaces.HAND, LabPlaces.STAND, "Unlock, lab on screen."),
        ),
    )

    /** E7 and E11: 30 s in hand and 30 s in the pocket at every distance. */
    val DISTANCES = LabScenario(
        "distances",
        "Distances (E7 / E11)",
        LabPlaces.DISTANCES.filter { it >= 1.0 }.flatMap { meters ->
            val at = if (meters % 1.0 == 0.0) meters.toInt().toString() else meters.toString()
            listOf(
                LabStep("$at m, hand", 30, LabPlaces.HAND, LabPlaces.STAND, meters, "Walk to $at m, hold it."),
                LabStep("$at m, pocket", 30, LabPlaces.POCKET_FRONT, LabPlaces.STAND, meters, "Lock, front pocket."),
            )
        },
    )

    val E8 = LabScenario(
        "e8",
        "E8 masks: all listeners; the street's bits",
        listOf(
            step("on screen", 60, LabPlaces.HAND, LabPlaces.STAND, "Probe: pattern. Android and B listen."),
            step("locked", 180, LabPlaces.TABLE_UP, LabPlaces.LIE, "Lock."),
            step("street's masks", 600, LabPlaces.HAND, LabPlaces.WALK, "Android listens to everything in the street."),
        ),
    )

    val ALL: List<LabScenario> = listOf(E1, E2, E3, E4, POCKET, E6, DISTANCES, E8)
}

/** A scenario in progress: the [index]th step, started at [stepStartedMillis] (monotonic). */
data class LabRun(val scenario: LabScenario, val index: Int, val stepStartedMillis: Long, val elapsed: Boolean) {
    val step: LabStep get() = scenario.steps[index]
    val isLast: Boolean get() = index == scenario.steps.lastIndex

    fun remainingMillis(nowMillis: Long): Long = (step.seconds * 1000L - (nowMillis - stepStartedMillis))
}

/**
 * Runs a scenario: [start], [next] at the tester's hand; every step puts its own mark ([onStep]); [tick] says once when
 * a step's time is up, for the signal ([onElapsed]), and with [autoAdvance] moves on to the next step by itself: the
 * tester follows the signals instead of a timer. Time comes in ([nowMillis], monotonic). Main thread.
 */
class LabScenarioRunner(
    private val nowMillis: () -> Long,
    private val autoAdvance: Boolean = false,
    private val onStep: (LabScenario, Int, LabStep) -> Unit,
    private val onElapsed: (LabStep) -> Unit = {},
    private val onFinished: (LabScenario) -> Unit = {},
) {
    private val mutableRun = MutableStateFlow<LabRun?>(null)
    val run: StateFlow<LabRun?> = mutableRun.asStateFlow()

    fun start(scenario: LabScenario) {
        require(scenario.steps.isNotEmpty())
        enter(scenario, 0)
    }

    /** On to the next step; after the last, the scenario ends. */
    fun next() {
        val current = mutableRun.value ?: return
        if (current.isLast) {
            mutableRun.value = null
            onFinished(current.scenario)
        } else {
            enter(current.scenario, current.index + 1)
        }
    }

    fun stop() {
        val current = mutableRun.value ?: return
        mutableRun.value = null
        onFinished(current.scenario)
    }

    /** Call every second: tells [onElapsed] once when the step's time is up. */
    fun tick() {
        val current = mutableRun.value ?: return
        if (!current.elapsed && current.remainingMillis(nowMillis()) <= 0) {
            mutableRun.value = current.copy(elapsed = true)
            onElapsed(current.step)
            if (autoAdvance) next()
        }
    }

    private fun enter(scenario: LabScenario, index: Int) {
        mutableRun.value = LabRun(scenario, index, nowMillis(), elapsed = false)
        onStep(scenario, index, scenario.steps[index])
    }
}
