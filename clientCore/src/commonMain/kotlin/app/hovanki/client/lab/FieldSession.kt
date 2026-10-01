package app.hovanki.client.lab

import app.hovanki.client.errors.ErrorReporter
import app.hovanki.client.errors.NoopErrorReporter
import app.hovanki.client.network.ApiException
import app.hovanki.client.network.GameSocketException
import app.hovanki.client.network.Transport
import app.hovanki.client.session.GameTrace
import app.hovanki.client.storage.ClientStorage
import app.hovanki.device.CarryMonitor
import app.hovanki.device.ImpactMonitor
import app.hovanki.device.NoopCarryMonitor
import app.hovanki.device.NoopImpactMonitor
import app.hovanki.device.lab.LabProbes
import app.hovanki.device.lab.NoopLabProbes
import app.hovanki.radar.RadioSighting
import app.hovanki.shared.lab.SyncFields
import app.hovanki.shared.protocol.FieldJoinRequest
import app.hovanki.shared.protocol.FieldJoinResponse
import app.hovanki.shared.protocol.FieldUpload
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.LabCapabilities
import app.hovanki.shared.protocol.LabRunId
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.rules.RadarToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.random.Random

/** Where this phone's field log is ([FieldSession.state]). */
data class FieldState(
    val status: FieldStatus = FieldStatus.OFF,
    /** The game the log is about (or was, or is being joined). */
    val gameId: GameId? = null,
    /** The game's run on the server, once joined. */
    val runId: LabRunId? = null,
    /** Why the last join or upload failed; null: it didn't. */
    val error: String? = null,
)

enum class FieldStatus {
    /** Nothing written: not the field build, no consent, no round yet, or the join failed (tried again). */
    OFF,

    /** Asking the server for the game's run. */
    JOINING,

    /** In the game's run: the log is written and uploaded. */
    ON,

    /** The server has no field log for this game (FIELD_LOG off, or it refused): not asked again in this game. */
    REFUSED,

    /** Out of the game's run (the game is over for the phone): the rest of the log went up. */
    LEFT,
}

/**
 * The field log of this phone (docs/adr/0018-field-test-build.md §3, docs/field-test.md step 2): the lab's [log] in a
 * real game, uploaded to the game's run on the server. Only in the field build ([isFieldBuild]: the app passes
 * `BuildInfo.channel == "preview"`, never `isDebug`) and only after the tester agreed ([giveConsent], kept in
 * [storage]): otherwise nothing of it happens, whatever the server says.
 *
 * It is the game's [GameTrace]: from the lobby on ([JOIN_PHASES], docs/field-test.md step 5) it joins the game's
 * run with the player's game token ([LabApi.fieldJoin]; the server answers 404 while it has FIELD_LOG off, and the
 * phone doesn't ask again in this game), turns the log on ([LabLog.startField]: the run's salt, the player as the
 * label, the coordinates allowed, the radio thinned) and uploads it every `uploadIntervalMillis` ([LabUploader]),
 * only when something new was written (a lobby with nothing going on sends nothing). In the lobby and on the
 * results of a game with the radar it shows the touch card ([touchCard], [touched]): the radio runs then with a
 * token of the log's own ([touchRadioToken]) and the accelerometer's jolts are written as the touch's candidates.
 * It writes what the game tells it — GPS fixes with their coordinates (at most one per `gpsEveryMillis`), the radio's
 * readings (thinned), every sync (how long, by which transport, the refusals' codes, the phase changing), errors —
 * and the clock, the app's life and the battery ([probes]); from the round on also a tick a second and the pocket's
 * classifiers in the shadow ([CarryShadow]: `carry.v1` from [carryMonitor], `carry.v2`). The UI adds
 * the screens and taps ([ui]), the permissions ([permissions]), the exceptions it caught ([exception], with the Sentry
 * event's id), «Something is wrong» ([somethingWrong]) and the three
 * questions after the game ([survey]). When the phone leaves the game ([GameTrace.onSessionEnded], or another game) the
 * log stops and the rest goes up. The results screen still counts: the survey is answered there.
 *
 * Main thread, except [onSyncSent].
 */
class FieldSession(
    private val log: LabLog,
    private val api: LabApi,
    private val storage: ClientStorage,
    private val scope: CoroutineScope,
    /** The field build (`preview`): the only build with the field log. */
    val isFieldBuild: Boolean,
    private val about: () -> LabAbout = { LabAbout(null, null, null, null) },
    private val capabilities: () -> LabCapabilities = { LabCapabilities() },
    private val probes: LabProbes = NoopLabProbes(),
    /**
     * Measures the clock against the server's soon after the join and every [LabClockSync.EVERY_MILLIS], each phone at
     * its own moment within [clockJitterMillis] ([random]); null: the join's answer only.
     */
    private val clockSync: LabClockSync? = null,
    /** The app's permissions now, by name (`app.hovanki.shared.lab.PermFields`): written at the join. */
    private val permissions: () -> Map<String, String> = { emptyMap() },
    /**
     * Where the phone's own failures go beyond the log (Sentry in the field build, [NoopErrorReporter] elsewhere):
     * the radio's and the location's ([onError]); its event id goes into the log's `err`.
     */
    private val errorReporter: ErrorReporter = NoopErrorReporter,
    private val retryMillis: Long = RETRY_MILLIS,
    private val clockJitterMillis: Long = CLOCK_JITTER_MILLIS,
    private val random: Random = Random.Default,
    /** The game's pocket monitor, for `carry.v1` in the shadow beside `carry.v2` ([CarryShadow]) in the round. */
    private val carryMonitor: CarryMonitor = NoopCarryMonitor(),
    /** The accelerometer's lone jolts while the touch card is up: the touch's candidates. */
    private val impacts: ImpactMonitor = NoopImpactMonitor(),
) : GameTrace {
    private val mutableState = MutableStateFlow(FieldState())
    val state: StateFlow<FieldState> = mutableState.asStateFlow()

    private val mutableConsentAt = MutableStateFlow(if (isFieldBuild) storage.fieldConsentAt else null)

    /** When the tester agreed (server time as the phone knew it); null: not yet, or not the field build. */
    val consentAt: StateFlow<Long?> = mutableConsentAt.asStateFlow()

    /** The field build without the tester's consent: the app shows the consent screen and plays nothing until then. */
    val needsConsent: Boolean get() = isFieldBuild && mutableConsentAt.value == null

    /** In a game's run: the log is written; the UI shows «Something is wrong» then. */
    val isActive: Boolean get() = mutableState.value.status == FieldStatus.ON

    private val mutableTouchCard = MutableStateFlow(false)

    /**
     * The card «Touch phones with a neighbour» is shown: the field log is on in a game with the radar, in the lobby or
     * on the results (docs/adr/0018-field-test-build.md §5). The player picks a neighbour, they touch phones, and both
     * press «We touched» ([touched]).
     */
    val touchCard: StateFlow<Boolean> = mutableTouchCard.asStateFlow()

    /** The token this phone advertises for the touches: random for every game's run, nobody's radar token. */
    private var touchToken: String? = null
    private var lastSnapshot: GameSnapshot? = null
    private var roundStarted = false
    private var impactJob: Job? = null

    private var uploader: LabUploader? = null
    private var thinning: FieldThinning? = null
    private val jobs = ArrayList<Job>()

    /** The last game's rest going up ([flushingUploader], stopped when it is done or cancelled). */
    private var flushing: Job? = null
    private var flushingUploader: LabUploader? = null

    /** When the join may be tried again after a failure (monotonic). */
    private var retryAt = Long.MIN_VALUE

    @Volatile
    private var syncSentAt: Long? = null
    private var lastTransport: Transport? = null
    private var lastPhase: GamePhase? = null
    private var ticks = 0L

    /**
     * The tester agrees at [atMillis] (server time as the phone knows it, `ServerClock.now()`): kept on the phone. The
     * consent screen comes before the app ever asked the server's clock: the first snapshot stamps it again by the
     * server's when the phone's clock was ahead or far behind ([onSnapshot]).
     */
    fun giveConsent(atMillis: Long) {
        if (!isFieldBuild) return
        storage.saveFieldConsent(atMillis)
        mutableConsentAt.value = atMillis
    }

    /**
     * The tester takes it back: the log stops now, nothing more is written, and what has not gone up yet never does
     * (it is dropped from the phone).
     */
    fun withdrawConsent() {
        storage.clearFieldConsent()
        mutableConsentAt.value = null
        stop(sendRest = false)
    }

    // The game (GameTrace)

    override fun onSnapshot(session: PlayerSession, snapshot: GameSnapshot) {
        if (!isFieldBuild) return
        stampConsentAgain(snapshot.serverTimeMillis)
        val current = mutableState.value
        if (current.gameId != null && current.gameId != snapshot.gameId) {
            leave()
            mutableState.value = FieldState()
        }
        lastSnapshot = snapshot
        joinIfDue(session, snapshot)
        updateRound(snapshot)
        updateTouch(snapshot)
    }

    /**
     * Joins the game's run from the lobby on (docs/field-test.md step 5: the touches before the round are logged),
     * once per game; a join that failed on the way is tried again after [retryMillis]. Not on the results: a game
     * nobody logged stays so.
     */
    private fun joinIfDue(session: PlayerSession, snapshot: GameSnapshot) {
        if (snapshot.phase !in JOIN_PHASES) return
        val consent = mutableConsentAt.value ?: return
        val state = mutableState.value
        if (state.gameId == snapshot.gameId && state.status != FieldStatus.OFF) return
        if (state.gameId == snapshot.gameId && log.monoNow() < retryAt) return
        join(session, consent)
    }

    /** The round started: the ticks and the pocket's classifiers in the shadow, until the phone leaves the game. */
    private fun updateRound(snapshot: GameSnapshot) {
        if (!isActive || roundStarted) return
        if (snapshot.phase != GamePhase.HIDING && snapshot.phase != GamePhase.SEEKING) return
        roundStarted = true
        jobs += scope.launch { tickLoop() }
        jobs += scope.launch { quietly { CarryShadow(log).run(probes, carryMonitor) } }
    }

    /**
     * «Touch phones with a neighbour» (ADR 0018 §5): in the lobby and on the results of a game with the radar while
     * the log is on. Then the radio runs outside the round ([touchRadioToken]) and the accelerometer's jolts are the
     * touch's candidates.
     */
    private fun updateTouch(snapshot: GameSnapshot?) {
        val show = snapshot != null && isActive && snapshot.settings.features.hasRadar &&
            (snapshot.phase == GamePhase.LOBBY || snapshot.phase == GamePhase.FINISHED)
        mutableTouchCard.value = show
        if (show && impactJob == null) {
            impactJob = scope.launch { quietly { impacts.impacts().collect { log.touchImpact(it.g, it.atMillis) } } }
        } else if (!show) {
            impactJob?.cancel()
            impactJob = null
        }
    }

    override fun touchRadioToken(snapshot: GameSnapshot): String? = touchToken.takeIf { mutableTouchCard.value }

    /**
     * «We touched» with [partner] (both players press it): the truth the touch detector is checked against, sent at
     * once. False: no touch card now (no log, no radar, or the round is on).
     */
    fun touched(partner: PlayerId): Boolean {
        if (!mutableTouchCard.value) return false
        log.touchPressed(partner.value)
        uploader?.let { scope.launch { it.flush() } }
        return true
    }

    override fun onSessionEnded() = leave()

    override fun onFix(fix: LocationSample) {
        val thinning = thinning ?: return
        if (!isActive || !thinning.allowGps(fix.timestampMillis)) return
        log.fix(
            lat = fix.point.lat,
            lon = fix.point.lon,
            accuracyMeters = fix.accuracyMeters,
            ageMillis = (log.deviceNow() - fix.timestampMillis).coerceAtLeast(0),
            mock = fix.isMock,
        )
    }

    override fun onSighting(sighting: RadioSighting) {
        if (!isActive) return
        log.rx(
            sighting.token,
            sighting.rssi,
            sighting.api,
            sighting.via,
            sighting.peer,
            sighting.atMillis,
            sighting.tech,
        )
    }

    override fun onSyncSent() {
        syncSentAt = log.monoNow()
    }

    override fun onSynced(transport: Transport, snapshot: GameSnapshot) {
        lastTransport = transport
        val previous = lastPhase
        lastPhase = snapshot.phase
        if (!isActive) return
        val changed = previous != null && previous != snapshot.phase
        log.sync(
            transport = transport.key,
            ok = true,
            millis = sinceSent(),
            phase = snapshot.phase.name.takeIf { changed || previous == null },
            from = previous?.name?.takeIf { changed },
        )
    }

    override fun onSyncFailed(error: Throwable) {
        if (!isActive) return
        val code = when (error) {
            is ApiException -> error.status
            is GameSocketException -> error.code
            else -> null
        }
        log.sync(
            transport = lastTransport?.key ?: SyncFields.POLL,
            ok = false,
            millis = sinceSent(),
            code = code,
            error = error::class.simpleName,
        )
    }

    /**
     * The game's caught errors. The phone's own failures (the radio's, the location's: their texts come from the OS)
     * also go to [errorReporter], and the log's `err` carries the event's id. A failed command is the network's or the
     * server's answer, whose text may quote the server's JSON (nicknames): only the log has it.
     */
    override fun onError(where: String, error: Throwable) {
        // Only what the tester agreed to: nothing goes out before the consent or after it was taken back.
        val sentryId = if (where in REPORTED && mutableConsentAt.value != null) errorReporter.capture(error) else null
        exception(where, error, sentryId)
    }

    // The UI

    /** A [screen] opened or closed ([what]: `open`, `close`, `tap`), a tap meaning [action]: never a text. */
    fun ui(screen: String, what: String, action: String? = null) {
        if (isActive) log.ui(screen, what, action)
    }

    /** An exception the app caught [where], with the Sentry event's id if it went there too. */
    fun exception(where: String, caught: Throwable, sentryId: String? = null) {
        if (isActive) log.err(where, caught::class.simpleName ?: "Throwable", caught.message, sentryId)
    }

    /** The app's permissions now, by name (`app.hovanki.shared.lab.PermFields`), when they change. */
    fun permissions(states: Map<String, String>) {
        if (isActive && states.isNotEmpty()) log.perm(states)
    }

    /** «Something is wrong»: shaken or from the game's menu, with the player's few words if any. False: no log now. */
    fun somethingWrong(text: String? = null): Boolean {
        if (!isActive) return false
        log.playerMark(text)
        return true
    }

    /**
     * The three questions after the game (ADR 0018 §5): [rating] 1–5, what [broken] (from the list) and in words
     * ([text]), where the phone was ([carry]: `hand`, `pocket`, `bag`, `mixed`). Sent at once. False: no log now.
     */
    fun survey(rating: Int?, broken: List<String> = emptyList(), text: String? = null, carry: String? = null): Boolean {
        if (!isActive) return false
        log.survey(rating?.coerceIn(1, 5), broken, text, carry)
        val uploader = uploader ?: return true
        scope.launch { uploader.flush() }
        return true
    }

    /** Out of the game's run now: the log stops, the rest of it goes up in the background. */
    fun leave() = stop(sendRest = true)

    /** Out of the game's run; [sendRest]: the log's rest goes up (else it is dropped, the consent taken back). */
    private fun stop(sendRest: Boolean) {
        val state = mutableState.value
        jobs.forEach { it.cancel() }
        jobs.clear()
        roundStarted = false
        touchToken = null
        impactJob?.cancel()
        impactJob = null
        mutableTouchCard.value = false
        if (!sendRest) {
            // A last game's rest still going up stops too, its uploader's own loop with it: left running, it would
            // send the next game's log to the last game's run.
            flushing?.cancel()
            flushing = null
            flushingUploader?.stop()
            flushingUploader = null
        }
        when (state.status) {
            FieldStatus.ON -> {
                log.stopField()
                val uploader = uploader
                this.uploader = null
                thinning = null
                if (sendRest) {
                    // On the app's scope: the last upload goes on whatever the screen does. Stopped whatever happens
                    // to it, cancelled too (and above, should it be cancelled before it ran).
                    flushingUploader = uploader
                    flushing = scope.launch {
                        try {
                            uploader?.flush()
                        } finally {
                            uploader?.stop()
                            if (flushingUploader === uploader) flushingUploader = null
                        }
                    }
                } else {
                    uploader?.stop()
                    log.clear()
                }
                mutableState.value = state.copy(status = FieldStatus.LEFT)
            }

            FieldStatus.JOINING -> mutableState.value = state.copy(status = FieldStatus.LEFT)

            else -> if (!sendRest && state.status == FieldStatus.LEFT) log.clear()
        }
    }

    /**
     * The consent's time, kept as the phone's clock said, is stamped again by the server's [serverNow] when it is ahead
     * of it or before the field build existed (a phone's clock off): the tester agreed by now at the latest, and the
     * server refuses a consent from before 2026.
     */
    private fun stampConsentAgain(serverNow: Long) {
        val at = mutableConsentAt.value ?: return
        // A server's time from before the field build says nothing (only tests' snapshots have one).
        if (serverNow < FieldUpload.EARLIEST_CONSENT_MILLIS) return
        if (at in FieldUpload.EARLIEST_CONSENT_MILLIS..serverNow) return
        storage.saveFieldConsent(serverNow)
        mutableConsentAt.value = serverNow
    }

    private fun join(session: PlayerSession, consent: Long) {
        mutableState.value = FieldState(FieldStatus.JOINING, gameId = session.gameId)
        scope.launch {
            // The last game's log goes up before this one clears it.
            flushing?.join()
            val about = about()
            val request = FieldJoinRequest(about.model, about.os, about.build, about.commit, capabilities(), consent)
            val response = try {
                api.fieldJoin(session.gameId, session.token, request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(session.gameId, e)
                return@launch
            }
            // The phone left the game while the answer was on its way.
            val now = mutableState.value
            if (now.status != FieldStatus.JOINING || now.gameId != session.gameId) return@launch
            start(session.gameId, response)
        }
    }

    private fun failed(gameId: GameId, e: Exception) {
        val state = mutableState.value
        if (state.status != FieldStatus.JOINING || state.gameId != gameId) return
        val reason = LabUploader.describe(e)
        // A refusal that won't pass by itself (FIELD_LOG off: 404, no consent, closed or full): not again in this game.
        val final = e is ApiException && e.status in 400..499 && e.status != TOO_MANY_REQUESTS
        retryAt = log.monoNow() + retryMillis
        mutableState.value = state.copy(status = if (final) FieldStatus.REFUSED else FieldStatus.OFF, error = reason)
    }

    private fun start(gameId: GameId, response: FieldJoinResponse) {
        val thinning = FieldThinning(response.rxEveryMillis, response.frameEveryMillis, response.gpsEveryMillis)
        this.thinning = thinning
        log.appState = probes::appState
        log.startField(response.runId.value, response.salt, response.label, thinning)
        // The server's clock: the join's answer (half its way unknown) until it is measured (clockLoop).
        log.setClock(ClockEstimate(response.serverTimeMillis - log.deviceNow(), 0, 0, log.monoNow()))
        val about = about()
        log.session(about.model, about.os, about.build, about.commit, mode = MODE)
        permissions().takeIf { it.isNotEmpty() }?.let(log::perm)
        lastPhase = null
        val uploader = LabUploader(
            log,
            api,
            scope,
            intervalMillis = response.uploadIntervalMillis,
            maxEvents = response.maxEvents,
            maxBytes = response.maxBodyBytes,
        )
        this.uploader = uploader
        uploader.start(response.runId, response.token)
        touchToken = random.nextBytes(RadarToken.LENGTH / 2).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        mutableState.value = FieldState(FieldStatus.ON, gameId, response.runId)
        // The ticks and the shadow's classifiers from the round on; the touch card in the lobby and on the results.
        lastSnapshot?.takeIf { it.gameId == gameId }?.let {
            updateRound(it)
            updateTouch(it)
        }
        clockSync?.let { sync -> jobs += scope.launch { clockLoop(sync) } }
        jobs += scope.launch { quietly { probes.lifecycle().collect { log.life(it) } } }
        jobs += scope.launch { quietly { probes.battery().collect { log.battery(it.level, it.state, it.lowPower) } } }
        jobs += scope.launch {
            // The server refused for good (the run closed or full): nothing more is written.
            uploader.closed.first { it }
            val current = mutableState.value
            if (current.runId == response.runId && current.status == FieldStatus.ON) {
                mutableState.value = current.copy(error = uploader.lastError.value)
                leave()
            }
        }
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            log.tick(ticks++)
            delay(TICK_MILLIS)
        }
    }

    /**
     * The server's clock, measured soon after the join and every [LabClockSync.EVERY_MILLIS] after that. The phones of a
     * game start their round together, often from behind one address (the venue's Wi-Fi, a carrier's NAT): each asks
     * at its own moment within [clockJitterMillis], never all in one burst nor in step afterwards.
     */
    private suspend fun clockLoop(sync: LabClockSync) {
        var wait = jitter()
        while (currentCoroutineContext().isActive) {
            delay(wait)
            val estimate = withTimeoutOrNull(CLOCK_TIMEOUT_MILLIS) { sync.measure() }
            if (estimate == null) log.clockEvent(failed = true) else log.setClock(estimate)
            wait = LabClockSync.EVERY_MILLIS + jitter()
        }
    }

    private fun jitter(): Long = if (clockJitterMillis > 0) random.nextLong(clockJitterMillis) else 0L

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A probe that fails: the log goes on without it.
        }
    }

    private fun sinceSent(): Long? = syncSentAt?.let { log.monoNow() - it }?.takeIf { it >= 0 }

    private val Transport.key: String
        get() = when (this) {
            Transport.POLLING -> SyncFields.POLL
            Transport.SOCKET -> SyncFields.SOCKET
        }

    companion object {
        /** The phases the phone joins the game's run in: from the lobby on, so the touches before the round count. */
        val JOIN_PHASES = setOf(GamePhase.LOBBY, GamePhase.HIDING, GamePhase.SEEKING)

        /** The places of [onError] whose errors go to the [ErrorReporter] too. */
        val REPORTED = setOf("radio", "location")

        /** The `session` event's mode in a game's run. */
        const val MODE = "field"
        const val TICK_MILLIS = 1_000L

        /** A join that failed on the way (no network) is tried again with a snapshot after this. */
        const val RETRY_MILLIS = 15_000L

        /** The window in which a phone measures the server's clock after the join, and the spread of the later ones. */
        const val CLOCK_JITTER_MILLIS = 20_000L
        private const val CLOCK_TIMEOUT_MILLIS = 10_000L
        private const val TOO_MANY_REQUESTS = 429
    }
}
