package app.hovanki.e2e.beacon

import app.hovanki.shared.lab.LabPlanState
import app.hovanki.shared.lab.LabRunPlan
import app.hovanki.shared.lab.LabRunScripts
import app.hovanki.shared.protocol.LabRunAction
import app.hovanki.shared.protocol.LabRunId
import app.hovanki.shared.protocol.LabRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Mac follows a run on the server by its answers and the server's clock (docs/radar-run.md step 1). */
class MacServerRunTest {
    private val script = LabRunScripts.RADIO
    private val runId = LabRunId("run1")
    private val token = "0a1b2c3d"
    private var now = 1_790_000_000_000L

    /** What the server says after [action] on [state] at [now]. */
    private fun server(state: LabPlanState, action: LabRunAction? = null) =
        (if (action == null) state else LabRunPlan.apply(script, state, action, now)).let {
            it to LabRunPlan.toView(it, runId, now)
        }

    private val commands = mutableListOf<String>()
    private val steps = mutableListOf<String>()
    private var finished = 0

    private fun follow(initial: LabPlanState) = MacServerRun(
        runId = runId,
        script = script,
        radarToken = token,
        initial = LabRunPlan.toView(initial, runId, now),
        serverNow = { now },
        command = { commands += it },
        onStep = { index, step, revision -> steps += "$index ${step.id} r$revision" },
        onFinished = { finished++ },
    )

    private fun expectedCommand(index: Int): String {
        val mac = script.setupOf("mac", index)
        return when {
            mac.seeker -> "ibeacon $token"
            mac.hider -> "advertise $token"
            else -> "stop"
        }
    }

    @Test
    fun theMacSetsItsRadioForEveryStepOfTheServersPlan() {
        val run = follow(LabPlanState())
        assertEquals(listOf("stop"), commands, "quiet until the admin starts the run")
        assertTrue(steps.isEmpty())

        val (started, view) = server(LabPlanState(), LabRunAction.NEXT)
        run.onAnswer(view)
        assertEquals(listOf("0 ${script.steps[0].id} r${started.revision}"), steps)

        val seen = mutableListOf<String>()
        val end = now + script.totalMillis + 1_000
        while (now < end) {
            run.tick()
            seen += commands.last()
            now += 200
        }
        assertEquals(script.steps.mapIndexed { index, step -> "$index ${step.id} r1" }, steps, "every step once")
        assertFalse(run.finished, "the last step is held: the server says when the run is over")
        assertEquals(0, finished)
        run.onAnswer(LabRunPlan.toView(LabRunPlan.advanceByTime(script, started, now), runId, now))
        assertTrue(run.finished)
        assertEquals(1, finished)
        assertEquals("stop", commands.last(), "nothing on the air after the run")
        for (index in script.steps.indices) {
            val tick = ((script.startOf(index) + 1_000) / 200).toInt()
            assertEquals(expectedCommand(index), seen[tick], script.steps[index].id)
        }
        assertTrue(commands.zipWithNext().none { it.first == it.second }, "only changes: $commands")
    }

    @Test
    fun aRepeatStartsTheStepAgainAPauseDoesNot() {
        var (state, view) = server(LabPlanState(), LabRunAction.NEXT)
        val run = follow(state)
        assertEquals(1, steps.size)

        now += 1_000
        server(state, LabRunAction.PAUSE).let { (s, v) ->
            state = s
            view = v
        }
        run.onAnswer(view)
        now += 5_000
        run.tick()
        // The polls go on during the pause.
        run.onAnswer(LabRunPlan.toView(state, runId, now))
        server(state, LabRunAction.RESUME).let { (s, v) ->
            state = s
            view = v
        }
        run.onAnswer(view)
        assertEquals(1, steps.size, "a pause and a resume go on with the same step")
        assertEquals(state.stepStartedAtMillis, run.plan.stepStartedAtMillis, "the start moved by the pause")

        now += 1_000
        val older = view
        server(state, LabRunAction.REPEAT).let { (s, v) ->
            state = s
            view = v
        }
        run.onAnswer(view)
        assertEquals(listOf("0 ${script.steps[0].id} r1", "0 ${script.steps[0].id} r4"), steps, "a repeat")

        run.onAnswer(older)
        assertEquals(state.revision, run.plan.revision, "an older answer changes nothing")

        server(state, LabRunAction.NEXT).let { (s, v) ->
            state = s
            view = v
        }
        run.onAnswer(view)
        assertEquals("1 ${script.steps[1].id} r5", steps.last(), "the next step")
        assertEquals(expectedCommand(1), commands.last())
    }
}
