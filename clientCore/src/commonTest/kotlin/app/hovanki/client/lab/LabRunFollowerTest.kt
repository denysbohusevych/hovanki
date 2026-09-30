package app.hovanki.client.lab

import app.hovanki.client.network.ApiException
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.lab.LabRunScript
import app.hovanki.shared.lab.LabRunScripts
import app.hovanki.shared.lab.ProbeMode
import app.hovanki.shared.protocol.LabRunAction
import app.hovanki.shared.protocol.LabRunStatus
import app.hovanki.shared.rules.OverflowCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabRunFollowerTest {
    /** The lab, the server's run of `LabRunScripts.E2E` on the server's clock (700 ms ahead), uploads, the phone. */
    private class Rig(
        scope: TestScope,
        scenarioId: String? = null,
        script: LabRunScript = LabRunScripts.E2E,
        clockWorks: Boolean = true,
        /** The server's clock ahead of the device's. */
        serverAhead: Long = 700,
        precisionSupported: Boolean = false,
    ) {
        val lab = Lab(scope, clockWorks, precisionSupported = precisionSupported)
        val api = FakeLabApi(
            serverNow = { lab.log.deviceNow() + serverAhead },
            script = script,
            scenarioId = scenarioId ?: script.id,
        )
        val uploader = LabUploader(lab.log, api, scope.backgroundScope, intervalMillis = 2_000)
        val follower = LabRunFollower(lab.controller, api, uploader, scope.backgroundScope, appState = { "active" })

        fun state() = assertNotNull(follower.state.value)

        fun ofKind(kind: String): List<JsonObject> = lab.events().filter { it.text(LabFields.K) == kind }

        fun marks(): List<String> = ofKind("mark").mapNotNull { it.text("label") }
    }

    @Test
    fun aPhoneWithUwbPostsItsTokenAndGetsTheRunsTokens() = runTest {
        val rig = Rig(this, precisionSupported = true)
        rig.api.uwbTokens["B"] = "token-b"
        rig.follower.join(FakeLabApi.CODE, "A")
        runCurrent()
        assertEquals(1, rig.lab.precision.prepared, "the radio made ready at the join")
        assertEquals("uwb-token-1", rig.api.uwbTokens["A"])
        assertEquals(mapOf("A" to "uwb-token-1", "B" to "token-b"), rig.lab.controller.uwbPeers.value)

        // A new token (the session started over) goes up again; another device's comes with the next poll.
        rig.lab.precision.token.value = "uwb-token-2"
        runCurrent()
        assertEquals("uwb-token-2", rig.api.uwbTokens["A"])
        rig.api.uwbTokens["C"] = "token-c"
        advanceTimeBy(LabRunFollower.POLL_MILLIS + 1)
        assertEquals("token-c", rig.lab.controller.uwbPeers.value["C"])
        assertEquals(
            listOf(true, true),
            rig.ofKind("net").filter {
                it.text("action") == "uwb"
            }.map { it.text("ok").toBoolean() },
        )

        rig.follower.leave()
        assertEquals(emptyMap(), rig.lab.controller.uwbPeers.value)
    }

    @Test
    fun aPhoneWithoutUwbPostsNoToken() = runTest {
        val rig = Rig(this)
        rig.api.uwbTokens["B"] = "token-b"
        rig.follower.join(FakeLabApi.CODE, "A")
        runCurrent()
        assertEquals(0, rig.lab.precision.prepared)
        assertEquals(setOf("B"), rig.api.uwbTokens.keys)
        assertEquals(mapOf("B" to "token-b"), rig.lab.controller.uwbPeers.value, "it still knows the others'")
        rig.follower.leave()
    }

    @Test
    fun followsTheTimedStepsByTheServersClockAndUploadsTheLog() = runTest {
        val rig = Rig(this)
        val lab = rig.lab
        rig.follower.join("hovanki-lab:abc-234", "A")

        val joined = rig.state()
        assertEquals(FakeLabApi.CODE, joined.code)
        assertEquals(LabRunStatus.CREATED, joined.plan.status)
        assertEquals(FakeLabApi.RADAR_TOKEN, joined.radarToken)
        assertEquals(emptyList(), joined.warnings)
        assertEquals("A", rig.api.joins.single().label)
        assertEquals("Fake 1", rig.api.joins.single().model)
        assertTrue(lab.controller.running.value)
        assertTrue(lab.controller.inGame.value)
        assertEquals(FakeLabApi.RADAR_TOKEN, lab.controller.probeToken.value)
        assertNull(lab.radio.tokens, "nothing on the air before the start")

        // The admin starts the run; the phone learns it with its next poll.
        rig.api.press(LabRunAction.NEXT)
        val startedAt = assertNotNull(rig.api.plan.stepStartedAtMillis)
        advanceTimeBy(LabRunFollower.POLL_MILLIS + 1)
        assertEquals(0, rig.state().plan.stepIndex)
        assertEquals(startedAt + 8_000, rig.state().stepEndsAtMillis)
        assertEquals(FakeLabApi.RADAR_TOKEN, lab.radio.tokens?.value, "a hider with the run's token")
        assertEquals(false, lab.radio.asSeeker)
        assertNull(lab.controller.probe.value)

        // The next steps by the clock alone, between the polls too.
        advanceTimeBy(8_000)
        assertEquals(1, rig.state().plan.stepIndex)
        assertEquals(ProbeMode.Token, lab.controller.probe.value)
        assertEquals(OverflowCode.encode(FakeLabApi.RADAR_TOKEN), lab.air.advertised.last())
        assertEquals(FakeLabApi.RADAR_TOKEN, lab.radio.tokens?.value, "still a hider")
        advanceTimeBy(6_000)
        assertEquals(2, rig.state().plan.stepIndex)
        assertNull(lab.controller.probe.value)

        advanceTimeBy(8_000)
        advanceTimeBy(LabUploader.FLUSH_MILLIS)
        assertTrue(rig.state().finished)
        assertFalse(lab.controller.running.value, "the lab stops recording at the end")
        assertNull(lab.radio.tokens)
        assertEquals(0L, rig.uploader.pending.value, "all of it uploaded")

        val steps = rig.ofKind("step")
        assertEquals(listOf(0, 1, 2), steps.map { it.getValue("index").jsonPrimitive.int })
        assertEquals(listOf("all_hiders", "probe", "all_again"), steps.map { it.text("id") })
        // Every step starts at the plan's server millisecond, within a tick.
        val second = steps[1].getValue(LabFields.T).jsonPrimitive.long
        assertTrue(second - (startedAt + 8_000) in 0..LabRunFollower.TICK_MILLIS, "${second - startedAt}")
        assertEquals(
            listOf("run: joined", "run: all_hiders", "run: probe", "run: all_again", "run: done"),
            rig.marks().filter { it.startsWith("run: ") },
        )
        val probeMark = rig.ofKind("mark").single { it.text("label") == "run: probe" }
        assertEquals(2, probeMark.getValue("step").jsonPrimitive.int)
        assertEquals("hand", probeMark.text("place"))

        // The server has every event since the join's clear, in order, each once; those after the join carry the run.
        val uploaded = rig.api.uploads.flatMap { it.lines }.map { Json.parseToJsonElement(it).jsonObject }
        val seqs = uploaded.map { it.getValue(LabFields.SEQ).jsonPrimitive.long }.distinct()
        assertEquals((seqs.min()..seqs.max()).toList(), seqs)
        assertEquals("session", uploaded.first().text(LabFields.K), "the fresh log's header first")
        assertEquals(lab.log.nextSeq - 1, seqs.max())
        val afterJoin = uploaded.dropWhile { it.text("label") != "run: joined" }
        assertTrue(afterJoin.isNotEmpty())
        assertTrue(afterJoin.all { it.text(LabFields.RUN) == rig.api.runId.value })
    }

    @Test
    fun theButtonsApplyAtOnceAndAPauseKeepsTheStep() = runTest {
        val rig = Rig(this)
        val lab = rig.lab
        rig.follower.join(FakeLabApi.CODE, "B")
        rig.follower.advance(LabRunAction.NEXT)
        runCurrent()
        assertEquals(LabRunStatus.RUNNING, rig.state().plan.status)
        assertEquals(0, rig.state().plan.stepIndex)
        assertEquals(FakeLabApi.RADAR_TOKEN, lab.radio.tokens?.value)

        advanceTimeBy(3_000)
        rig.api.press(LabRunAction.PAUSE)
        advanceTimeBy(LabRunFollower.POLL_MILLIS + 1)
        assertEquals(LabRunStatus.PAUSED, rig.state().plan.status)
        assertNull(rig.state().stepEndsAtMillis)
        advanceTimeBy(20_000)
        assertEquals(0, rig.state().plan.stepIndex, "paused: the timer doesn't run")

        rig.follower.advance(LabRunAction.RESUME)
        runCurrent()
        assertEquals(LabRunStatus.RUNNING, rig.state().plan.status)
        assertEquals(1, rig.ofKind("step").size, "a resume goes on with the same stretch")

        rig.follower.advance(LabRunAction.REPEAT)
        runCurrent()
        assertEquals(listOf(0, 0), rig.ofKind("step").map { it.getValue("index").jsonPrimitive.int })
        val ends = assertNotNull(rig.state().stepEndsAtMillis)
        assertEquals(lab.log.serverNow() + 8_000, ends, "the step again from now")

        advanceTimeBy(8_000 + LabRunFollower.TICK_MILLIS)
        assertEquals(1, rig.state().plan.stepIndex)
        assertTrue(lab.controller.listening.value, "B listens in the probe's step")
    }

    @Test
    fun leavingStopsTheRadioTheUploadsAndThePolls() = runTest {
        val rig = Rig(this)
        val lab = rig.lab
        rig.follower.join(FakeLabApi.CODE, "A")
        rig.api.press(LabRunAction.NEXT)
        advanceTimeBy(LabRunFollower.POLL_MILLIS + 1)
        assertNotNull(lab.radio.tokens)

        rig.follower.leave()
        runCurrent()
        assertTrue(rig.state().left)
        assertFalse(rig.follower.isFollowing)
        assertNull(lab.radio.tokens)
        assertFalse(rig.uploader.isRunning)
        val left = rig.ofKind("mark").single { it.text("label") == "run: left" }
        assertTrue(rig.api.ackedSeq >= left.getValue(LabFields.SEQ).jsonPrimitive.long, "uploaded before leaving")
        assertTrue(lab.controller.running.value, "the lab stays on for the export")

        lab.log.note("after the run")
        assertNull(lab.events().last()[LabFields.RUN])
        val polls = rig.api.polls
        advanceTimeBy(10_000)
        assertEquals(polls, rig.api.polls)
    }

    @Test
    fun theRunsEndIsTheServersToSay() = runTest {
        val rig = Rig(this)
        rig.follower.join(FakeLabApi.CODE, "A")
        rig.api.press(LabRunAction.NEXT)
        val startedAt = assertNotNull(rig.api.plan.stepStartedAtMillis)
        advanceTimeBy(LabRunFollower.POLL_MILLIS + 1)
        assertEquals(0, rig.state().plan.stepIndex)

        // The admin repeats the last step in its last moment; the phone's clock says the run is over, but it asks.
        advanceTimeBy(startedAt + 21_900 - rig.lab.log.serverNow())
        assertEquals(2, rig.state().plan.stepIndex)
        rig.api.press(LabRunAction.REPEAT)
        advanceTimeBy(300)
        assertFalse(rig.state().finished, "the last step goes on")
        assertEquals(2, rig.state().plan.stepIndex)
        assertTrue(rig.lab.controller.running.value)
        assertEquals(listOf(0, 1, 2, 2), rig.ofKind("step").map { it.getValue("index").jsonPrimitive.int })

        // Its end then comes with the server's word, at once.
        advanceTimeBy(8_000)
        advanceTimeBy(LabRunFollower.MIN_POLL_MILLIS + 1)
        assertTrue(rig.state().finished)
        assertEquals(1, rig.marks().count { it == "run: done" })
    }

    @Test
    fun withoutTheServersClockTheStepsChangeWithItsAnswers() = runTest {
        // The server's clock can't be read, and the device's own is 40 s ahead of it.
        val rig = Rig(this, clockWorks = false, serverAhead = -40_000)
        rig.follower.join(FakeLabApi.CODE, "A")
        assertEquals(listOf("no server clock: the steps change only with the server's answers"), rig.state().warnings)
        rig.api.press(LabRunAction.NEXT)
        advanceTimeBy(LabRunFollower.POLL_MILLIS + 1)
        assertEquals(LabRunStatus.RUNNING to 0, rig.state().plan.let { it.status to it.stepIndex })
        // By the device's clock the run would be over by now; the server's answers say step 0 until 8 s.
        advanceTimeBy(5_000)
        assertEquals(LabRunStatus.RUNNING to 0, rig.state().plan.let { it.status to it.stepIndex })
        advanceTimeBy(LabRunFollower.POLL_MILLIS)
        assertEquals(1, rig.state().plan.stepIndex, "the server's answer does")
    }

    @Test
    fun aGameStoppingTheLabEndsFollowing() = runTest {
        val rig = Rig(this)
        val lab = rig.lab
        rig.follower.join(FakeLabApi.CODE, "A")
        rig.api.press(LabRunAction.NEXT)
        advanceTimeBy(LabRunFollower.POLL_MILLIS + 1)
        assertNotNull(lab.radio.tokens)
        assertTrue(lab.tracker.running)

        // A game starts: the lab stops, and the phone leaves the run by itself.
        lab.inAGame.value = true
        runCurrent()
        assertFalse(lab.controller.running.value)
        assertTrue(rig.state().left)
        assertFalse(rig.follower.isFollowing)
        assertFalse(rig.uploader.isRunning)
        assertNull(lab.radio.tokens)
        assertNotNull(rig.follower.error.value)

        // The round's tracker and radio are the game's now: the run going on touches neither.
        lab.tracker.start()
        val polls = rig.api.polls
        rig.api.press(LabRunAction.NEXT)
        advanceTimeBy(30_000)
        assertEquals(polls, rig.api.polls)
        assertTrue(lab.tracker.running)
        assertNull(lab.radio.tokens)
        assertNull(lab.controller.probe.value)
        rig.follower.leave()
        assertTrue(lab.tracker.running)
    }

    @Test
    fun aPhoneThatJoinsInALockedStepAdvertisesAndIsAskedToLock() = runTest {
        val script = LabRunScripts.RADIO
        val rig = Rig(this, script = script)
        val lab = rig.lab
        // The run is in `ibeacon_locked` already: the app restarted there, the phone joins again.
        rig.api.press(LabRunAction.NEXT)
        advanceTimeBy(script.startOf(5) + 5_000)
        rig.follower.join(FakeLabApi.CODE, "A")
        runCurrent()

        assertEquals("ibeacon_locked", rig.state().step?.id)
        assertEquals(ProbeMode.Token, lab.controller.probe.value, "the lock stretch's advertisement")
        assertEquals(FakeLabApi.RADAR_TOKEN, lab.radio.tokens?.value)
        assertEquals(false, lab.radio.asSeeker)
        assertTrue(lab.haptics.notified.any { it.startsWith("Lock the phone now") }, "${lab.haptics.notified}")
        assertTrue(rig.state().warnings.none { "not locked" in it }, "asked to lock, not blamed")

        // The next locked step keeps it, as a locked phone must.
        val advertised = lab.air.advertised.size
        advanceTimeBy(script.startOf(6) - script.startOf(5))
        assertEquals("token_rotates", rig.state().step?.id)
        assertEquals(ProbeMode.Token, lab.controller.probe.value)
        assertEquals(FakeLabApi.RADAR_TOKEN, lab.radio.tokens?.value)
        assertTrue(lab.air.advertised.size >= advertised)
        assertTrue(rig.state().warnings.any { "token_rotates: the phone was not locked" in it })
    }

    @Test
    fun aWrongCodeOrAPlanTheAppDoesntKnowIsRefused() = runTest {
        val rig = Rig(this)
        assertFailsWith<IllegalArgumentException> { rig.follower.join("nope", "A") }
        assertFailsWith<ApiException> { rig.follower.join("ZZZ999", "A") }
        assertEquals("join: 404", rig.follower.error.value)
        assertNull(rig.follower.state.value)

        val newer = Rig(this, scenarioId = "tomorrow")
        assertFailsWith<IllegalStateException> { newer.follower.join(FakeLabApi.CODE, "A") }
        assertNull(newer.follower.state.value)
    }
}

private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content
