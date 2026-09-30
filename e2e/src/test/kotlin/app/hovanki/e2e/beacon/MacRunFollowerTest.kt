package app.hovanki.e2e.beacon

import app.hovanki.client.lab.LabRunToken
import app.hovanki.shared.lab.LabRunScripts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Mac follows the phone's automatic run by the server's clock (docs/radio-lab-tests.md). */
class MacRunFollowerTest {
    private val script = LabRunScripts.RADIO
    private val startAt = 1_790_000_060_000L
    private val token = LabRunToken.encode(script.version, startAt)

    private var now = startAt - 8_000
    private val commands = mutableListOf<String>()
    private val events = mutableListOf<String>()
    private val follower = MacRunFollower(
        serverNow = { now },
        command = { commands += it },
        onRunStart = { heard, _, at -> events += "start $heard $at" },
        onStep = { _, step -> events += step.id },
        onRunEnd = { events += "end $it" },
    )

    @Test
    fun theMacSetsItsRadioForEveryStep() {
        follower.idle()
        assertEquals(listOf("advertise cafe0001"), commands)
        follower.onHeard("cafe0001")
        follower.onHeard("0a1b2c3d")
        assertFalse(follower.inRun, "not a run's token")

        follower.onHeard(token)
        follower.onHeard(token)
        assertTrue(follower.inRun)
        assertEquals(listOf("start $token $startAt"), events)
        assertEquals("stop", commands.last(), "quiet until the start: the phone's announcement is heard clean")

        val seen = mutableListOf<String>()
        while (now < startAt + script.totalMillis + 1_000) {
            follower.tick()
            seen += commands.last()
            now += 200
        }
        assertEquals(listOf("start $token $startAt") + script.steps.map { it.id } + "end $token", events)
        assertFalse(follower.inRun)
        assertEquals("advertise cafe0001", commands.last(), "idle again")
        for ((index, step) in script.steps.withIndex()) {
            val mac = script.setupOf("mac", index)
            val expected = when {
                mac.seeker -> "ibeacon cafe0002"
                mac.hider -> "advertise cafe0001"
                else -> "stop"
            }
            val tick = ((script.startOf(index) + 1_000 + 8_000) / 200).toInt()
            assertEquals(expected, seen[tick], step.id)
        }
        assertTrue(commands.zipWithNext().none { it.first == it.second }, "only changes: $commands")
    }

    @Test
    fun anOldRunIsNotFollowed() {
        now = startAt + script.totalMillis + 1
        follower.onHeard(token)
        assertFalse(follower.inRun)
        assertEquals(emptyList(), commands)
    }

    @Test
    fun aNewRunReplacesTheOldOne() {
        follower.onHeard(token)
        now = startAt + 30_000
        follower.tick()
        val next = LabRunToken.encode(script.version, startAt + 40_000)
        follower.onHeard(next)
        assertEquals(
            listOf("start $token $startAt", script.steps[0].id, "end $token", "start $next ${startAt + 40_000}"),
            events,
        )
    }
}
