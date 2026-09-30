package app.hovanki.e2e.beacon

import app.hovanki.shared.lab.LabPlanState
import app.hovanki.shared.lab.LabRunPlan
import app.hovanki.shared.lab.LabRunScript
import app.hovanki.shared.lab.PhoneSetup
import app.hovanki.shared.lab.RunStep
import app.hovanki.shared.protocol.LabRunId
import app.hovanki.shared.protocol.LabRunStateView
import app.hovanki.shared.protocol.LabRunStatus

/**
 * The Mac in a run of the radio lab on the server (docs/adr/0017-radar-techniques-and-big-run.md §5, docs/radar-run.md
 * step 1): what [MacRunFollower] is for the phone's local run, for a run an admin drives from the console. The Mac
 * joined the run [runId] as [label] (`mac`) and got [radarToken]; from then on it moves the run's [script] by the
 * server's clock itself ([tick], [LabRunPlan.followByTime]; only while [clockKnown]) and takes the server's answers
 * as they come ([onAnswer]; the newer revision wins). The run's end is the server's to say: the last step is held
 * until an answer says it is over. For every step it sets its Bluetooth by the label's [PhoneSetup]:
 * [PhoneSetup.hider] advertises [radarToken] as a hider, [PhoneSetup.seeker] as a seeker's iBeacon, else nothing;
 * sniffing stays on. The Mac is never locked, so the phases don't matter here. When the run is over it stops
 * advertising and calls [onFinished]. Not thread-safe: one thread calls it.
 */
class MacServerRun(
    val runId: LabRunId,
    val script: LabRunScript,
    val radarToken: String,
    initial: LabRunStateView,
    private val serverNow: () -> Long,
    private val command: (String) -> Unit,
    private val onStep: (index: Int, step: RunStep, revision: Long) -> Unit = { _, _, _ -> },
    private val onFinished: () -> Unit = {},
    private val label: String = MacRunFollower.MAC_LABEL,
    /** False while the clock was never measured: the steps change with the server's answers only. */
    private val clockKnown: () -> Boolean = { true },
) {
    /** Where the run is, by the latest answer and the clock since. */
    var plan: LabPlanState = LabPlanState()
        private set

    /** The server's time of the latest answer: a step that starts at or after it started again (a REPEAT). */
    private var lastAnswerAt = initial.serverTimeMillis
    private var lastCommand: String? = null

    val finished: Boolean get() = plan.status == LabRunStatus.FINISHED

    init {
        // Quiet until the first step: the run's plan says when to advertise.
        apply(PhoneSetup())
        update(byClock(LabRunPlan.fromView(initial)))
    }

    /** The server's answer to a poll or a button: applied unless this Mac already has a newer revision. */
    fun onAnswer(view: LabRunStateView) {
        if (view.runId != runId || view.revision < plan.revision) return
        update(byClock(LabRunPlan.fromView(view)))
        lastAnswerAt = maxOf(lastAnswerAt, view.serverTimeMillis)
    }

    /** Call often (a few times a second): a timed step moves on at the same server millisecond as on the phones. */
    fun tick() {
        if (finished) return
        val next = byClock(plan)
        if (next != plan) update(next)
    }

    private fun byClock(state: LabPlanState): LabPlanState =
        if (clockKnown()) LabRunPlan.followByTime(script, state, serverNow()) else state

    private fun update(next: LabPlanState) {
        val previous = plan
        plan = next
        when {
            next.status == LabRunStatus.FINISHED -> if (previous.status != LabRunStatus.FINISHED) {
                apply(PhoneSetup())
                onFinished()
            }

            next.status == LabRunStatus.CREATED || next.stepIndex !in script.steps.indices -> Unit

            next.stepIndex != previous.stepIndex || restarted(previous, next) -> {
                onStep(next.stepIndex, script.steps[next.stepIndex], next.revision)
                apply(script.setupOf(label, next.stepIndex))
            }
        }
    }

    /** A REPEAT starts the step when it is pressed, after the latest answer; a RESUME only moves its start later. */
    private fun restarted(previous: LabPlanState, next: LabPlanState): Boolean {
        if (next.revision == previous.revision || next.stepStartedAtMillis == previous.stepStartedAtMillis) return false
        val startedAt = next.stepStartedAtMillis ?: return false
        return startedAt >= lastAnswerAt
    }

    private fun apply(setup: PhoneSetup) {
        val line = when {
            setup.seeker -> "ibeacon $radarToken"
            setup.hider -> "advertise $radarToken"
            else -> "stop"
        }
        if (line == lastCommand) return
        lastCommand = line
        command(line)
    }
}
