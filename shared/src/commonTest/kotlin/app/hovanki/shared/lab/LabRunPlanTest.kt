package app.hovanki.shared.lab

import app.hovanki.shared.protocol.LabRunAction
import app.hovanki.shared.protocol.LabRunId
import app.hovanki.shared.protocol.LabRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LabRunPlanTest {
    private val script = LabRunScripts.E2E
    private val start = 1_790_000_000_000L

    private fun started(): LabPlanState = LabRunPlan.apply(script, LabPlanState(), LabRunAction.NEXT, start)

    @Test
    fun nextStartsTheFirstStep() {
        val created = LabPlanState()
        assertEquals(LabPlanState(), LabRunPlan.advanceByTime(script, created, start + 60_000), "not started: waits")
        assertNull(LabRunPlan.stepEndsAt(script, created))
        val state = started()
        assertEquals(LabPlanState(LabRunStatus.RUNNING, 0, start, null, 1), state)
        assertEquals(start + 8_000, LabRunPlan.stepEndsAt(script, state))
    }

    @Test
    fun timedStepsMoveOnByTheClock() {
        val state = started()
        assertEquals(state, LabRunPlan.advanceByTime(script, state, start + 7_999))
        val second = LabRunPlan.advanceByTime(script, state, start + 8_000)
        assertEquals(1, second.stepIndex)
        assertEquals(start + 8_000, second.stepStartedAtMillis)
        assertEquals(1L, second.revision, "the clock is no control action")
        assertEquals(start + 14_000, LabRunPlan.stepEndsAt(script, second))
    }

    @Test
    fun severalStepsAtOnceWhenTheClockJumped() {
        val state = LabRunPlan.advanceByTime(script, started(), start + 15_000)
        assertEquals(2, state.stepIndex)
        assertEquals(start + 14_000, state.stepStartedAtMillis, "each step starts where the one before ended")
        val done = LabRunPlan.advanceByTime(script, started(), start + 60_000)
        assertEquals(LabRunStatus.FINISHED, done.status)
        assertEquals(2, done.stepIndex)
        assertNull(LabRunPlan.stepEndsAt(script, done))
    }

    @Test
    fun theFollowersHoldTheLastStepForTheServer() {
        // The same steps by the clock, several at once too, but the run's end is the server's to say.
        assertEquals(LabRunPlan.advanceByTime(script, started(), start + 15_000), followed(start + 15_000))
        val held = followed(start + 60_000)
        assertEquals(LabRunStatus.RUNNING to 2, held.status to held.stepIndex)
        assertEquals(start + 14_000, held.stepStartedAtMillis)
        assertEquals(false, LabRunPlan.endIsDue(script, followed(start + 21_999), start + 21_999))
        assertEquals(true, LabRunPlan.endIsDue(script, held, start + 22_000))
        // What is not running stays as it is.
        val finished = LabRunPlan.finish(started())
        assertEquals(finished, LabRunPlan.followByTime(script, finished, start + 60_000))
        assertEquals(false, LabRunPlan.endIsDue(script, finished, start + 60_000))
    }

    private fun followed(nowMillis: Long) = LabRunPlan.followByTime(script, started(), nowMillis)

    @Test
    fun repeatRestartsTheStepAndCounts() {
        val state = LabRunPlan.apply(script, started(), LabRunAction.REPEAT, start + 5_000)
        assertEquals(0, state.stepIndex)
        assertEquals(start + 5_000, state.stepStartedAtMillis)
        assertEquals(2L, state.revision)
        assertEquals(start + 13_000, LabRunPlan.stepEndsAt(script, state))
    }

    @Test
    fun thePauseShiftsTheStart() {
        val paused = LabRunPlan.apply(script, started(), LabRunAction.PAUSE, start + 3_000)
        assertEquals(LabRunStatus.PAUSED, paused.status)
        assertEquals(start + 3_000, paused.pausedAtMillis)
        assertNull(LabRunPlan.stepEndsAt(script, paused))
        assertEquals(paused, LabRunPlan.advanceByTime(script, paused, start + 100_000), "paused: nothing moves")
        val resumed = LabRunPlan.apply(script, paused, LabRunAction.RESUME, start + 10_000)
        assertEquals(LabRunStatus.RUNNING, resumed.status)
        assertEquals(start + 7_000, resumed.stepStartedAtMillis)
        assertNull(resumed.pausedAtMillis)
        assertEquals(3L, resumed.revision)
        assertEquals(start + 15_000, LabRunPlan.stepEndsAt(script, resumed))
    }

    @Test
    fun nextAfterTheLastStepFinishes() {
        var state = started()
        state = LabRunPlan.apply(script, state, LabRunAction.NEXT, start + 1_000)
        state = LabRunPlan.apply(script, state, LabRunAction.NEXT, start + 2_000)
        assertEquals(2, state.stepIndex)
        state = LabRunPlan.apply(script, state, LabRunAction.NEXT, start + 3_000)
        assertEquals(LabRunStatus.FINISHED, state.status)
        assertEquals(4L, state.revision)
    }

    @Test
    fun nextFromAPauseMovesOnAndRuns() {
        val paused = LabRunPlan.apply(script, started(), LabRunAction.PAUSE, start + 1_000)
        val next = LabRunPlan.apply(script, paused, LabRunAction.NEXT, start + 2_000)
        assertEquals(LabPlanState(LabRunStatus.RUNNING, 1, start + 2_000, null, 3), next)
    }

    @Test
    fun illegalActionsChangeNothing() {
        val created = LabPlanState()
        for (action in listOf(LabRunAction.REPEAT, LabRunAction.PAUSE, LabRunAction.RESUME)) {
            assertEquals(created, LabRunPlan.apply(script, created, action, start))
        }
        val running = started()
        assertEquals(running, LabRunPlan.apply(script, running, LabRunAction.RESUME, start + 1_000))
        val paused = LabRunPlan.apply(script, running, LabRunAction.PAUSE, start + 1_000)
        assertEquals(paused, LabRunPlan.apply(script, paused, LabRunAction.PAUSE, start + 2_000))
        val finished = LabRunPlan.finish(running)
        assertEquals(LabRunStatus.FINISHED, finished.status)
        assertEquals(2L, finished.revision)
        assertEquals(finished, LabRunPlan.finish(finished))
        for (action in LabRunAction.entries) {
            assertEquals(finished, LabRunPlan.apply(script, finished, action, start + 3_000))
        }
    }

    @Test
    fun aButtonStepWaits() {
        val manual = LabRunScript(
            "manual",
            1,
            "Manual",
            listOf("A"),
            listOf(RunStep("one", "One", null, emptyMap()), RunStep("two", "Two", 5, emptyMap())),
        )
        val state = LabRunPlan.apply(manual, LabPlanState(), LabRunAction.NEXT, start)
        assertNull(LabRunPlan.stepEndsAt(manual, state))
        assertEquals(state, LabRunPlan.advanceByTime(manual, state, start + 3_600_000))
    }

    @Test
    fun theViewRoundTrips() {
        val state = LabRunPlan.apply(script, started(), LabRunAction.PAUSE, start + 1_000)
        val view = LabRunPlan.toView(state, LabRunId("run1"), start + 2_000)
        assertEquals(LabRunId("run1"), view.runId)
        assertEquals(start + 2_000, view.serverTimeMillis)
        assertEquals(state, LabRunPlan.fromView(view))
    }
}
