package app.hovanki.client.lab

import app.hovanki.shared.lab.LabPlaces
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabPartsTest {
    @Test
    fun theClockTakesTheShortestRoundTrip() = runTest {
        var device = 1_000_000L
        var mono = 0L
        // The server is 500 ms ahead; the round trips take 300, 40 and 120 ms, the server answering halfway.
        val trips = ArrayDeque(listOf(300L, 40L, 120L))
        val sync = LabClockSync(
            serverTime = {
                val trip = trips.removeFirst()
                device += trip / 2
                mono += trip / 2
                val server = device + 500
                device += trip / 2
                mono += trip / 2
                server
            },
            deviceTimeMillis = { device },
            monotonicMillis = { mono },
        )
        val estimate = sync.measure(count = 3)
        assertEquals(500L, estimate?.offsetMillis)
        assertEquals(40L, estimate?.rttMillis)
        assertEquals(3, estimate?.samples)
    }

    @Test
    fun noAnswerNoEstimate() = runTest {
        val sync = LabClockSync({ error("offline") }, { 0L }, { 0L })
        assertNull(sync.measure())
        var calls = 0
        val flaky = LabClockSync({ if (calls++ == 0) error("offline") else 10_000L }, { 1_000L }, { 0L })
        assertEquals(1, flaky.measure(count = 2)?.samples)
    }

    @Test
    fun aScenarioMarksEveryStepAndSaysWhenTimeIsUp() {
        var now = 0L
        val marks = mutableListOf<String>()
        val elapsed = mutableListOf<String>()
        var finished = 0
        val runner = LabScenarioRunner(
            nowMillis = { now },
            onStep = { _, index, step -> marks += "${index + 1} ${step.label} ${step.place}" },
            onElapsed = { elapsed += it.label },
            onFinished = { finished++ },
        )
        val scenario = LabScenario(
            "t",
            "test",
            listOf(LabStep("one", 10, LabPlaces.HAND), LabStep("two", 5, LabPlaces.POCKET_FRONT)),
        )
        runner.start(scenario)
        assertEquals(listOf("1 one hand"), marks)
        now = 9_000
        runner.tick()
        assertEquals(emptyList(), elapsed)
        now = 10_000
        runner.tick()
        runner.tick()
        assertEquals(listOf("one"), elapsed, "once")
        runner.next()
        assertEquals("2 two pocket_front", marks.last())
        assertEquals(5_000L, runner.run.value?.remainingMillis(now))
        runner.next()
        assertNull(runner.run.value)
        assertEquals(1, finished)
    }

    @Test
    fun anAutoAdvancingScenarioMovesOnByItself() {
        var now = 0L
        val marks = mutableListOf<String>()
        var finished = 0
        val runner = LabScenarioRunner(
            nowMillis = { now },
            autoAdvance = true,
            onStep = { _, _, step -> marks += step.label },
            onFinished = { finished++ },
        )
        runner.start(LabScenario("t", "test", listOf(LabStep("one", 10), LabStep("two", 5))))
        now = 10_000
        runner.tick()
        assertEquals(listOf("one", "two"), marks)
        assertEquals(10_000L, runner.run.value?.stepStartedMillis)
        now = 15_000
        runner.tick()
        assertNull(runner.run.value)
        assertEquals(1, finished)
    }

    @Test
    fun theBuiltInScenariosAreSane() {
        val ids = LabScenarios.ALL.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(14, LabScenarios.POCKET.steps.size)
        assertTrue(LabScenarios.ALL.all { scenario -> scenario.steps.all { it.seconds > 0 } })
        assertEquals(12, LabScenarios.DISTANCES.steps.size)
        assertEquals(40.0, LabScenarios.DISTANCES.steps.last().distance)
    }
}
