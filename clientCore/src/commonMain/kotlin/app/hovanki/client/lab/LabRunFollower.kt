package app.hovanki.client.lab

import app.hovanki.shared.lab.DeviceStep
import app.hovanki.shared.lab.LabJoinCode
import app.hovanki.shared.lab.LabPlanState
import app.hovanki.shared.lab.LabRunPlan
import app.hovanki.shared.lab.LabRunScript
import app.hovanki.shared.lab.LabRunScripts
import app.hovanki.shared.lab.PhoneSetup
import app.hovanki.shared.lab.RunPhase
import app.hovanki.shared.lab.RunStep
import app.hovanki.shared.protocol.LabCapabilities
import app.hovanki.shared.protocol.LabJoinRequest
import app.hovanki.shared.protocol.LabRunAction
import app.hovanki.shared.protocol.LabRunId
import app.hovanki.shared.protocol.LabRunStateView
import app.hovanki.shared.protocol.LabRunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A run on the server as this phone follows it, for the screen: the run [runId] joined by [code] as [label], its
 * [script], where its [plan] is (by the server's clock, [serverTimeMillis] of the last answer), when the current step
 * ends ([stepEndsAtMillis]; null: a step for the button, paused, not started or over), the token this phone
 * advertises ([radarToken]), what went wrong on the way ([warnings]) and whether the phone left the run ([left]).
 */
data class LabFollowState(
    val runId: LabRunId,
    val code: String,
    val label: String,
    val script: LabRunScript,
    val plan: LabPlanState,
    val serverTimeMillis: Long,
    val stepEndsAtMillis: Long?,
    val radarToken: String,
    val warnings: List<String> = emptyList(),
    val left: Boolean = false,
) {
    val step: RunStep? get() = script.steps.getOrNull(plan.stepIndex)

    /** This label's part in the current step; null before the first. */
    val device: DeviceStep? get() = step?.let { it.devices[label] ?: DeviceStep() }

    /** What the phone does in the current step; null before the first. */
    val setup: PhoneSetup? get() = step?.let { script.setupOf(label, plan.stepIndex) }

    val finished: Boolean get() = plan.status == LabRunStatus.FINISHED
}

/**
 * Follows a run of the radio lab on the server (docs/adr/0017-radar-techniques-and-big-run.md §5, docs/radar-run.md
 * step 1): the [LabRunner] of a run that an admin's console drives, for any number of phones. The phone joins by the
 * run's code as one of its labels ([join]); the server gives it the run's plan (its id and version: the plan itself is
 * [scripts]'), a radar token to advertise and the run's salt for the log. From then on the phone applies its label's
 * [PhoneSetup] of every step through the [controller], uploads its log ([uploader]) and asks the server for the run's
 * state every [pollMillis]; in between, every [tickMillis], it moves the plan by the server's clock itself
 * ([LabRunPlan.followByTime]), so a timed step changes at the same server millisecond on every phone (the poll's
 * answer wins when it comes). Only with a measured clock: without one the steps change with the server's answers. The
 * run's end is the server's to say: when the last step's time is up the phone asks at once, and a button pressed in
 * that last moment still counts. The buttons (Next, Repeat, Pause, Resume) are the admin's or any phone's
 * ([advance]). When the run is over the lab stops recording and the rest of the log goes up; [leave] ends following.
 * When the lab stops meanwhile (a game started: the lab never shares the radio with a round) the phone leaves the run
 * by itself and touches none of the lab's parts again. Main thread.
 */
class LabRunFollower(
    private val controller: LabController,
    private val api: LabApi,
    private val uploader: LabUploader,
    private val scope: CoroutineScope,
    private val appState: () -> String = { "-" },
    private val capabilities: () -> LabCapabilities = { LabCapabilities() },
    private val scripts: (String) -> LabRunScript? = LabRunScripts::byId,
    private val pollMillis: Long = POLL_MILLIS,
    private val tickMillis: Long = TICK_MILLIS,
    /**
     * The logged-in account's token, sent with the join: a test server lets only staff join a run, the field build
     * has the lab for staff only (docs/adr/0018-field-test-build.md §4.D). Null: none.
     */
    private val accountToken: () -> String? = { null },
) {
    private val log = controller.log

    private val mutableState = MutableStateFlow<LabFollowState?>(null)

    /** The run followed, or the last one (then [LabFollowState.left]); null: never joined one. */
    val state: StateFlow<LabFollowState?> = mutableState.asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)

    /** What the last request to the server failed with; null: it didn't. */
    val error: StateFlow<String?> = mutableError.asStateFlow()

    private var token: String? = null

    /** The poll and the tick. */
    private val loops = ArrayList<Job>()

    /** Watches the lab: it stopping ends following. */
    private var watcher: Job? = null

    /** Asks the poll to ask now: the last step's time is up. */
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var endAsked = false

    /**
     * The first step of the locked stretch whose advertisement this phone set ([RunPhase.LOCK], or where it joined in
     * the middle of one); null: not in one. A locked step keeps the advertisement only when it is this stretch's.
     */
    private var advertisedStretch: Int? = null

    /** The server's time of the latest answer: a step that starts again after it was restarted (a REPEAT). */
    private var lastAnswerAt = Long.MIN_VALUE

    /** In a run and not left, finished or not. */
    val isFollowing: Boolean get() = mutableState.value?.left == false

    /**
     * Joins the run of [code] (typed or scanned: [LabJoinCode.normalize]) as [label], and follows it: the lab starts
     * with a fresh log under [label], the clock is measured, the server is asked, and from its answer on the log
     * carries the run's id and salt, the uploads start and the phone advertises the run's token. Throws what the
     * server answered ([app.hovanki.client.network.ApiException]: 404 a wrong code or the lab off, 400 a label not in
     * the run, 409 the run closed or full) or an [IllegalArgumentException] / [IllegalStateException] (not a code,
     * already in a run, a plan this app doesn't know); [error] says it too.
     */
    suspend fun join(code: String, label: String) {
        check(!isFollowing) { "already in a run: leave it first" }
        mutableError.value = null
        advertisedStretch = null
        endAsked = false
        wake.tryReceive()
        val normalized = LabJoinCode.normalize(code)
        if (normalized == null) {
            mutableError.value = "not a run's code: ${LabJoinCode.LENGTH} letters and digits"
            throw IllegalArgumentException(mutableError.value)
        }
        val name = label.trim().ifEmpty { LabLog.DEFAULT_LABEL }
        controller.start()
        controller.clear()
        controller.setLabel(name)
        val warnings = ArrayList<String>()
        if (withTimeoutOrNull(CLOCK_WAIT_MILLIS) { log.clock.first { it != null } } == null) {
            warnings += "no server clock: the steps change only with the server's answers"
        }
        val about = controller.device
        val request = LabJoinRequest(normalized, name, about.model, about.os, about.build, about.commit, capabilities())
        val startedAt = log.monoNow()
        val response = try {
            api.join(request, accountToken())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = LabUploader.describe(e)
            log.net("join", ok = false, millis = log.monoNow() - startedAt, error = reason)
            mutableError.value = "join: $reason"
            throw e
        }
        log.net("join", ok = true, millis = log.monoNow() - startedAt)
        val script = scripts(response.scenarioId)?.takeIf { it.version == response.scenarioVersion }
        if (script == null) {
            val reason = "this app doesn't know the plan ${response.scenarioId} v${response.scenarioVersion}: update it"
            log.note("run: $reason")
            mutableError.value = reason
            throw IllegalStateException(reason)
        }
        token = response.token
        log.setRun(response.runId.value, response.salt)
        // The header again, now with the run.
        controller.setLabel(name)
        uploader.start(response.runId, response.token)
        // Nothing of before the run goes on the air: the run's token only, and only as its steps say.
        controller.setProbe(null)
        controller.setBenchRadio(null)
        controller.setProbeToken(response.radarToken)
        controller.setInGame(true)
        if (!controller.inGame.value) {
            warnings += "no location permission: without GPS iOS may suspend the app when it is locked"
        }
        warnings.forEach { log.note("run: $it") }
        log.mark("run: joined", by = "run")
        lastAnswerAt = response.state.serverTimeMillis
        mutableState.value = LabFollowState(
            runId = response.runId,
            code = normalized,
            label = name,
            script = script,
            plan = LabPlanState(),
            serverTimeMillis = response.state.serverTimeMillis,
            stepEndsAtMillis = null,
            radarToken = response.radarToken,
            warnings = warnings,
        )
        update(byClock(script, LabRunPlan.fromView(response.state)))
        loops += scope.launch { pollLoop() }
        loops += scope.launch { tickLoop() }
        watcher = scope.launch {
            controller.running.first { !it }
            labStopped()
        }
    }

    /** Presses the run's button: the server's answer applies at once, the other phones see it with their next poll. */
    fun advance(action: LabRunAction) {
        val state = mutableState.value ?: return
        val token = token ?: return
        if (state.left) return
        scope.launch {
            val startedAt = log.monoNow()
            try {
                val view = api.advance(state.runId, token, action)
                log.net("advance", ok = true, millis = log.monoNow() - startedAt)
                onAnswer(view)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = LabUploader.describe(e)
                log.net("advance", ok = false, millis = log.monoNow() - startedAt, error = reason)
                mutableError.value = "${action.name.lowercase()}: $reason"
            }
        }
    }

    /**
     * Stops following: the lab's radio and switches off as at the end of a run, the rest of the log uploaded (at most
     * [LabUploader.FLUSH_MILLIS]), the uploads stopped and the run's id and salt gone from the log. The lab itself
     * stays on, its log kept for the export.
     */
    suspend fun leave() {
        val state = mutableState.value ?: return
        if (state.left) return
        watcher?.cancel()
        watcher = null
        loops.forEach { it.cancel() }
        loops.clear()
        if (!state.finished) {
            stopParts()
            log.mark("run: left", by = "tester")
        }
        uploader.flush(LabUploader.FLUSH_MILLIS)
        uploader.stop()
        log.setRun(null, null)
        token = null
        mutableState.value = mutableState.value?.copy(left = true)
    }

    /**
     * The lab stopped under the run (a game started): this phone leaves it without touching the lab's parts again,
     * the controller switched them off already. The log up to then still goes up.
     */
    private suspend fun labStopped() {
        val state = mutableState.value ?: return
        if (state.left || state.finished) return
        // No more requests of this phone's own: no answer may move the plan now.
        token = null
        loops.forEach { it.cancel() }
        loops.clear()
        advertisedStretch = null
        mutableError.value = "the lab stopped (a game started?): this phone left the run"
        uploader.flush(LabUploader.FLUSH_MILLIS)
        uploader.stop()
        log.setRun(null, null)
        watcher = null
        mutableState.value = mutableState.value?.copy(left = true)
    }

    private suspend fun pollLoop() {
        var lastPollAt = Long.MIN_VALUE
        while (true) {
            // Every pollMillis, or at once when the last step's time is up (not more often than MIN_POLL_MILLIS).
            if (withTimeoutOrNull(pollMillis) { wake.receive() } != null) {
                val wait = lastPollAt + MIN_POLL_MILLIS - log.monoNow()
                if (wait > 0) delay(wait)
            }
            val state = mutableState.value ?: return
            val token = token ?: return
            if (state.left || state.finished) return
            val startedAt = log.monoNow()
            lastPollAt = startedAt
            try {
                onAnswer(api.state(state.runId, token))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The last state stays: the timed steps go on by the clock meanwhile.
                val reason = LabUploader.describe(e)
                log.net("state", ok = false, millis = log.monoNow() - startedAt, error = reason)
                mutableError.value = "state: $reason"
            }
        }
    }

    private suspend fun tickLoop() {
        while (true) {
            val state = mutableState.value ?: return
            if (state.left || state.finished) return
            val plan = byClock(state.script, state.plan)
            if (plan != state.plan) update(plan)
            delay(tickMillis)
        }
    }

    private fun onAnswer(view: LabRunStateView) {
        val state = mutableState.value ?: return
        if (state.left || token == null || view.runId != state.runId) return
        // An answer older than what this phone already has (its own button's, a poll overtaken): nothing new.
        if (view.revision < state.plan.revision) return
        endAsked = false
        val plan = byClock(state.script, LabRunPlan.fromView(view))
        update(plan, view.serverTimeMillis)
        lastAnswerAt = maxOf(lastAnswerAt, view.serverTimeMillis)
    }

    /**
     * [plan] moved on by this phone's idea of the server's clock ([LabRunPlan.followByTime]); as it is while the clock
     * was never measured (the device's own could be off by any amount). When the last step's time is up, the poll asks.
     */
    private fun byClock(script: LabRunScript, plan: LabPlanState): LabPlanState {
        if (log.clock.value == null) return plan
        val now = log.serverNow()
        if (!endAsked && LabRunPlan.endIsDue(script, plan, now)) {
            endAsked = true
            wake.trySend(Unit)
        }
        return LabRunPlan.followByTime(script, plan, now)
    }

    private fun update(plan: LabPlanState, serverTimeMillis: Long? = null) {
        val state = mutableState.value ?: return
        val previous = state.plan
        val script = state.script
        val next = state.copy(
            plan = plan,
            serverTimeMillis = serverTimeMillis ?: state.serverTimeMillis,
            stepEndsAtMillis = LabRunPlan.stepEndsAt(script, plan),
        )
        mutableState.value = next
        when {
            plan.status == LabRunStatus.FINISHED -> if (previous.status != LabRunStatus.FINISHED) finished()
            plan.status == LabRunStatus.CREATED || plan.stepIndex !in script.steps.indices -> Unit
            plan.stepIndex != previous.stepIndex || restarted(previous, plan) -> enter(next)
        }
    }

    /**
     * The same step started again (a REPEAT), rather than resumed after a pause: a REPEAT starts it when it is pressed,
     * after the latest answer this phone had; a RESUME only moves the start later by the pause's length. A pause of a
     * step that ran less than a poll's interval can pass for a REPEAT: the step's setup is then applied again.
     */
    private fun restarted(previous: LabPlanState, plan: LabPlanState): Boolean {
        if (plan.revision == previous.revision || plan.stepStartedAtMillis == previous.stepStartedAtMillis) return false
        val startedAt = plan.stepStartedAtMillis ?: return false
        return startedAt >= lastAnswerAt
    }

    private fun enter(state: LabFollowState) {
        // The lab stopped (a game started): its parts are the round's now.
        if (!controller.running.value) return
        val index = state.plan.stepIndex
        val step = state.script.steps[index]
        val device = step.devices[state.label] ?: DeviceStep()
        val setup = device.setup
        // A locked step keeps the advertisement its stretch's lock step set; a phone that joined (or came back) in
        // the middle of a stretch sets it now, and is asked to lock.
        val stretch = lockStretchOf(state.script, state.label, index)
        val keepAdvertisement = setup.phase == RunPhase.LOCKED && stretch == advertisedStretch
        advertisedStretch = stretch
        log.step(index, step.id, step.title, state.plan.revision)
        log.mark(
            "run: ${step.id}",
            by = "run",
            step = index + 1,
            place = device.place,
            action = device.action,
            distance = step.distances.filterKeys { state.label in it.split('|') }.values.minOrNull(),
        )
        if (keepAdvertisement) {
            if (appState() == "active") warn("${step.id}: the phone was not locked")
        } else {
            // The advertisement changes only while the phone is active; the locked steps keep the lock step's.
            if (controller.probe.value != setup.probe) controller.setProbe(setup.probe)
            val asSeeker = when {
                setup.hider -> false
                setup.seeker -> true
                else -> null
            }
            val radio = controller.bench.radioMode.value
            if (asSeeker == null) {
                if (radio != null) controller.setBenchRadio(null)
            } else if (radio?.asSeeker != asSeeker) {
                controller.setBenchRadio(asSeeker, state.radarToken)
            }
        }
        if (controller.canListen) controller.setListening(setup.listen)
        if (controller.canTurnScreenOff) controller.setScreenOff(setup.screenOff)
        controller.setPulse(if (setup.pulse) LabPulse.HAPTICS else LabPulse.OFF)
        if (setup.inGame != controller.inGame.value) controller.setInGame(setup.inGame)
        if (setup.rotateToken) controller.rotateProbeToken(delayMillis = 0)
        val lock = setup.phase == RunPhase.LOCK || (setup.phase == RunPhase.LOCKED && !keepAdvertisement)
        if (index > 0 || lock) {
            val text = listOfNotNull(
                "Lock the phone now".takeIf { lock },
                step.title,
                device.hint.ifEmpty { step.hint },
            ).filter { it.isNotEmpty() }.distinct().joinToString(". ")
            scope.launch { controller.signal(text) }
        }
    }

    private fun finished() {
        stopParts()
        log.mark("run: done", by = "run")
        // The run's log is complete: the lab stops recording, so the upload catches up with it and stays there.
        controller.stop()
        scope.launch { controller.signal("Radio run done: unlock the phone") }
        scope.launch { uploader.flush(LabUploader.FLUSH_MILLIS) }
    }

    /** The first step of the locked stretch [index] is in for [label] (its LOCK step); null: a screen step. */
    private fun lockStretchOf(script: LabRunScript, label: String, index: Int): Int? {
        if (script.setupOf(label, index).phase == RunPhase.SCREEN) return null
        var first = index
        while (first > 0 && script.setupOf(label, first).phase == RunPhase.LOCKED) first--
        return first
    }

    private fun stopParts() {
        advertisedStretch = null
        if (!controller.running.value) return
        controller.setProbe(null)
        controller.setBenchRadio(null)
        controller.setListening(false)
        controller.setScreenOff(false)
        controller.setPulse(LabPulse.OFF)
        controller.setInGame(false)
    }

    private fun warn(text: String) {
        log.note("run: $text")
        mutableState.value = mutableState.value?.let { it.copy(warnings = it.warnings + text) }
    }

    companion object {
        const val POLL_MILLIS = 2_000L
        const val TICK_MILLIS = 200L
        const val CLOCK_WAIT_MILLIS = 15_000L

        /** The poll asks at once when the last step's time is up, but never more often than this. */
        const val MIN_POLL_MILLIS = 500L
    }
}
