package app.hovanki.client.lab

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
    fun orientationsFromGravity() {
        assertEquals(Orientation.FLAT_UP, Orientation.of(Gravity(0.0, 0.0, -1.0)))
        assertEquals(Orientation.FLAT_DOWN, Orientation.of(Gravity(0.0, 0.1, 0.99)))
        assertEquals(Orientation.UPRIGHT, Orientation.of(Gravity(0.1, -0.95, -0.2)))
        assertEquals(Orientation.UPSIDE_DOWN, Orientation.of(Gravity(0.0, 0.9, 0.3)))
        assertEquals(Orientation.TILTED, Orientation.of(Gravity(0.6, -0.6, -0.5)))
    }

    @Test
    fun theMotionSpreadTellsATableFromABreath() {
        val window = MotionWindow()
        repeat(5) { window.add(it * 100L, 1.0) }
        assertNull(window.std(), "too few readings")
        repeat(30) { window.add(500L + it * 100L, 1.0) }
        assertEquals(0.0, window.std())
        val breathing = MotionWindow()
        repeat(30) { breathing.add(it * 100L, if (it % 2 == 0) 1.05 else 0.95) }
        val std = breathing.std() ?: 0.0
        assertTrue(std in 0.04..0.06, "$std")
        // Only the last 3 seconds count.
        repeat(40) { breathing.add(3_000L + it * 100L, 1.0) }
        assertEquals(0.0, breathing.std())
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
    fun theBuiltInScenariosAreSane() {
        val ids = LabScenarios.ALL.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(14, LabScenarios.POCKET.steps.size)
        assertTrue(LabScenarios.ALL.all { scenario -> scenario.steps.all { it.seconds > 0 } })
        assertEquals(12, LabScenarios.DISTANCES.steps.size)
        assertEquals(40.0, LabScenarios.DISTANCES.steps.last().distance)
    }
}
