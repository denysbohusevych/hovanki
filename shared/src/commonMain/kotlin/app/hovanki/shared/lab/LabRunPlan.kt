package app.hovanki.shared.lab

import app.hovanki.shared.protocol.LabRunAction
import app.hovanki.shared.protocol.LabRunId
import app.hovanki.shared.protocol.LabRunStateView
import app.hovanki.shared.protocol.LabRunStatus

/**
 * Where a run of the radio lab is ([LabRunPlan]): the step [stepIndex] (-1 before the first) since
 * [stepStartedAtMillis], paused since [pausedAtMillis]; [revision] grows with every control action, so a REPEAT that
 * keeps the index is still a change. All times are the server's.
 */
data class LabPlanState(
    val status: LabRunStatus = LabRunStatus.CREATED,
    val stepIndex: Int = -1,
    val stepStartedAtMillis: Long? = null,
    val pausedAtMillis: Long? = null,
    val revision: Long = 0,
)

/**
 * The run's plan by the server's clock (docs/adr/0017-radar-techniques-and-big-run.md §5): the same pure functions on
 * the server and on every phone, so a timed step ends at the same server millisecond everywhere, even between the
 * phones' polls. Nothing here keeps time: it comes in as `nowMillis`.
 */
object LabRunPlan {
    /**
     * Timed steps that elapsed move on (several at once if the clock jumped), each starting where the one before
     * ended; after the last timed step: FINISHED. A step without a timer waits for NEXT. Paused: nothing moves.
     */
    fun advanceByTime(script: LabRunScript, state: LabPlanState, nowMillis: Long): LabPlanState {
        if (state.status != LabRunStatus.RUNNING) return state
        var current = state
        while (true) {
            val step = script.steps.getOrNull(current.stepIndex) ?: return current
            val seconds = step.seconds ?: return current
            val startedAt = current.stepStartedAtMillis ?: return current
            val endsAt = startedAt + seconds * 1000L
            if (nowMillis < endsAt) return current
            if (current.stepIndex == script.steps.lastIndex) {
                return current.copy(status = LabRunStatus.FINISHED, pausedAtMillis = null)
            }
            current = current.copy(stepIndex = current.stepIndex + 1, stepStartedAtMillis = endsAt)
        }
    }

    /**
     * [advanceByTime] for those who follow a run (the phones, the Mac): the timed steps move on by their idea of the
     * server's clock, but the last one is held when its time is up. Whether the run is over is the server's to say: a
     * button pressed in the last moment (a REPEAT, a PAUSE) keeps it going, and a clock that is off must not end it.
     */
    fun followByTime(script: LabRunScript, state: LabPlanState, nowMillis: Long): LabPlanState {
        var current = state
        while (true) {
            val endsAt = stepEndsAt(script, current) ?: return current
            if (nowMillis < endsAt) return current
            val next = advanceByTime(script, current, endsAt)
            if (next.status == LabRunStatus.FINISHED || next == current) return current
            current = next
        }
    }

    /** The last step's time is up by [nowMillis] ([followByTime] holds it): time to ask the server. */
    fun endIsDue(script: LabRunScript, state: LabPlanState, nowMillis: Long): Boolean =
        state.status == LabRunStatus.RUNNING && advanceByTime(script, state, nowMillis).status == LabRunStatus.FINISHED

    /**
     * NEXT / REPEAT / PAUSE / RESUME at [nowMillis], after the timed steps due by then ([advanceByTime]); revision + 1.
     * NEXT starts the first step, moves on (from a pause too) and after the last step finishes the run; REPEAT restarts
     * the current step; RESUME shifts the step's start by the pause's length. Illegal (e.g. PAUSE on CREATED, anything
     * on FINISHED): the state unchanged.
     */
    fun apply(script: LabRunScript, state: LabPlanState, action: LabRunAction, nowMillis: Long): LabPlanState {
        val current = advanceByTime(script, state, nowMillis)
        val revision = current.revision + 1
        return when (current.status) {
            LabRunStatus.FINISHED -> current

            LabRunStatus.CREATED -> when (action) {
                LabRunAction.NEXT -> LabPlanState(LabRunStatus.RUNNING, 0, nowMillis, null, revision)
                else -> current
            }

            LabRunStatus.RUNNING, LabRunStatus.PAUSED -> when (action) {
                LabRunAction.NEXT -> if (current.stepIndex >= script.steps.lastIndex) {
                    current.copy(status = LabRunStatus.FINISHED, pausedAtMillis = null, revision = revision)
                } else {
                    LabPlanState(LabRunStatus.RUNNING, current.stepIndex + 1, nowMillis, null, revision)
                }

                LabRunAction.REPEAT -> LabPlanState(LabRunStatus.RUNNING, current.stepIndex, nowMillis, null, revision)

                LabRunAction.PAUSE -> if (current.status == LabRunStatus.RUNNING) {
                    current.copy(status = LabRunStatus.PAUSED, pausedAtMillis = nowMillis, revision = revision)
                } else {
                    current
                }

                LabRunAction.RESUME -> if (current.status == LabRunStatus.PAUSED) {
                    val paused = nowMillis - (current.pausedAtMillis ?: nowMillis)
                    current.copy(
                        status = LabRunStatus.RUNNING,
                        stepStartedAtMillis = current.stepStartedAtMillis?.plus(paused),
                        pausedAtMillis = null,
                        revision = revision,
                    )
                } else {
                    current
                }
            }
        }
    }

    /** The run ends where it is (the admin's «Finish»); a finished one stays as it is. */
    fun finish(state: LabPlanState): LabPlanState = if (state.status == LabRunStatus.FINISHED) {
        state
    } else {
        state.copy(status = LabRunStatus.FINISHED, pausedAtMillis = null, revision = state.revision + 1)
    }

    /** When the current step ends by its timer; null for a button step, before the start, paused or finished. */
    fun stepEndsAt(script: LabRunScript, state: LabPlanState): Long? {
        if (state.status != LabRunStatus.RUNNING) return null
        val seconds = script.steps.getOrNull(state.stepIndex)?.seconds ?: return null
        return state.stepStartedAtMillis?.plus(seconds * 1000L)
    }

    fun toView(state: LabPlanState, runId: LabRunId, nowMillis: Long): LabRunStateView = LabRunStateView(
        runId = runId,
        status = state.status,
        stepIndex = state.stepIndex,
        stepStartedAtMillis = state.stepStartedAtMillis,
        pausedAtMillis = state.pausedAtMillis,
        revision = state.revision,
        serverTimeMillis = nowMillis,
    )

    fun fromView(view: LabRunStateView): LabPlanState = LabPlanState(
        status = view.status,
        stepIndex = view.stepIndex,
        stepStartedAtMillis = view.stepStartedAtMillis,
        pausedAtMillis = view.pausedAtMillis,
        revision = view.revision,
    )
}
