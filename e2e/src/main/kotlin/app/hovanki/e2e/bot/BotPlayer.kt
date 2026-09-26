package app.hovanki.e2e.bot

import app.hovanki.client.network.HttpGameApi
import app.hovanki.client.network.PollingGameConnection
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.createHttpClient
import app.hovanki.client.session.CatchCode
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.session.ServerClock
import app.hovanki.client.session.SessionError
import app.hovanki.client.session.SessionState
import app.hovanki.client.session.catchCodeToShow
import app.hovanki.client.storage.ClientStorage
import app.hovanki.e2e.route.GpsNoise
import app.hovanki.e2e.scenario.Timeline
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.protocol.protocolJson
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A headless player: the app's real client stack ([GameSessionManager], [HttpGameApi] over Ktor/OkHttp,
 * [PollingGameConnection], [ServerClock]) on a simulated phone ([FakeGps], [FakeNetwork], [DeviceClock]).
 *
 * Scenarios steer it like a person: walk, press buttons ([claimCatch], [dispute], [vote]), read another phone's
 * screen ([shownCode]), switch GPS or network off, kill the app. [behavior] covers the reactions a person
 * has without being told (show the code, vote). Every response it receives is checked by [SnapshotAudit].
 * The app keeps its session in [storage], so after [killApp] and [launchApp] it resumes the game, like on a phone.
 */
class BotPlayer(
    val name: String,
    start: GeoPoint,
    noise: GpsNoise,
    @Volatile var behavior: BotBehavior,
    private val serverUrl: String,
    private val timeline: Timeline,
    private val metrics: SyncMetrics? = null,
    /** Log observed state changes to the timeline; off for crowds (load tests). */
    private val logChanges: Boolean = true,
) {
    val clock = DeviceClock()
    val gps = FakeGps(start, noise, clock)
    val network = FakeNetwork(::onExchange)
    val backgroundTracker = FakeBackgroundTracker { running ->
        if (logChanges) log(if (running) "background tracking started" else "background tracking stopped")
    }
    val storage = PhoneStorage()

    private val violations = CopyOnWriteArrayList<String>()

    /** Privacy rule violations in anything this bot received (see [SnapshotAudit]). */
    val privacyViolations: List<String> get() = violations.toList()

    private val reveals = Collections.synchronizedSet(LinkedHashSet<Pair<PlayerId, VisibilityReason>>())

    /** Every (player, reason) this bot was ever shown a position for. */
    val revealsSeen: Set<Pair<PlayerId, VisibilityReason>> get() = synchronized(reveals) { reveals.toSet() }

    @Volatile private var app: App? = App()

    @Volatile var playerId: PlayerId? = null
        private set

    val id: PlayerId get() = checkNotNull(playerId) { "$name has not joined a game" }

    val isAppRunning: Boolean get() = app != null

    val state: SessionState get() = app?.session?.state?.value ?: SessionState()

    val snapshot: GameSnapshot? get() = state.snapshot

    /** "Now" as the app believes the server clock is; null while the app is not running. */
    fun serverNow(): Long? = app?.serverClock?.now()

    suspend fun createGame(settings: GameSettings): CommandResult =
        command("creates a game") { it.create(name, settings) }.also { rememberPlayer() }

    suspend fun join(joinCode: String): CommandResult =
        command("joins with code $joinCode") { it.join(joinCode, name) }.also { rememberPlayer() }

    suspend fun startGame(seekers: Collection<BotPlayer>): CommandResult =
        command("starts the game, seekers: ${seekers.joinToString { it.name }}") { session ->
            session.start(seekers.map { it.id })
        }

    suspend fun claimCatch(hider: BotPlayer): CommandResult = claimCatch(hider.id, hider.name)

    /** Claim on any player, e.g. one playing on a device. */
    suspend fun claimCatch(hiderId: PlayerId, hiderName: String = hiderId.value): CommandResult =
        command("claims a catch on $hiderName") { it.claimCatch(hiderId) }

    /** Types [code] into the open claim of this seeker. */
    suspend fun confirmCatch(code: String): CommandResult {
        val claim = snapshot?.catches?.lastOrNull { it.seekerId == playerId && it.status == CatchStatus.AWAITING_CODE }
            ?: return CommandResult.Rejected(null, "$name has no claim awaiting a code")
        return command("enters code $code") { it.confirmCatch(claim.id, code) }
    }

    /** Presses "Dispute" on the claim against this hider. */
    suspend fun dispute(): CommandResult {
        val claim = snapshot?.catches?.lastOrNull { it.hiderId == playerId && it.status == CatchStatus.AWAITING_CODE }
            ?: return CommandResult.Rejected(null, "$name has no claim to dispute")
        return command("disputes the claim") { it.dispute(claim.id) }
    }

    suspend fun vote(claimId: CatchId, confirm: Boolean): CommandResult =
        command(if (confirm) "votes to confirm" else "votes to reject") { it.vote(claimId, confirm) }

    /** What this hider's screen shows right now, if they decided to show it (see [ClaimReaction.ShowCode]). */
    fun shownCode(): CatchCode? {
        val running = app ?: return null
        val snapshot = running.session.state.value.snapshot ?: return null
        val claim = snapshot.catches.lastOrNull { it.hiderId == playerId && it.status == CatchStatus.AWAITING_CODE }
        if (claim == null || claim.id != running.showingCodeFor) return null
        return snapshot.catchCodeToShow(running.serverClock.now())
    }

    /** Swipes the app away: the process with its outbox and connection is gone; GPS, network, clock and storage stay. */
    fun killApp() {
        val running = app ?: return
        app = null
        running.close()
        log("app killed")
    }

    /** Starts the app again, like tapping its icon: a fresh process that resumes the game saved in [storage]. */
    fun launchApp() {
        if (app != null) return
        app = App()
        log("app launched")
    }

    fun log(text: String) = timeline.log(name, text)

    fun close() {
        app?.close()
        app = null
    }

    private fun rememberPlayer() {
        state.session?.let { playerId = it.playerId }
    }

    private suspend fun command(description: String, call: suspend (GameSessionManager) -> Boolean): CommandResult {
        val running =
            app ?: return CommandResult.Failed("the app is not running").also { log("$description: app not running") }
        val result = withContext(running.mainThread) {
            if (call(running.session)) {
                CommandResult.Ok
            } else {
                when (val error = running.session.state.value.lastError) {
                    is SessionError.Rejected -> CommandResult.Rejected(error.code, error.message)
                    is SessionError.Network -> CommandResult.Failed(error.details)
                    SessionError.SessionLost -> CommandResult.Failed("session lost")
                    SessionError.SavedGameFinished -> CommandResult.Failed("saved game finished")
                    SessionError.SavedGameGone -> CommandResult.Failed("saved game gone")
                    null -> CommandResult.Failed("unknown")
                }
            }
        }
        log(if (result == CommandResult.Ok) description else "$description: $result")
        return result
    }

    private fun onExchange(exchange: Exchange) {
        metrics?.record(exchange)
        val body = exchange.body ?: return
        if (exchange.status != 200 || !exchange.path.startsWith(ApiRoutes.GAMES)) return
        val snapshot = try {
            if (exchange.path == ApiRoutes.GAMES || exchange.path == ApiRoutes.JOIN) {
                protocolJson.decodeFromString<SessionResponse>(body).snapshot
            } else {
                protocolJson.decodeFromString<GameSnapshot>(body)
            }
        } catch (e: SerializationException) {
            violations += "$name: unreadable response from ${exchange.path}: ${e.message}"
            return
        }
        SnapshotAudit.check(snapshot, body).forEach { violations += "$name: $it" }
        snapshot.players.forEach { player -> player.location?.let { reveals += player.id to it.exactReason } }
    }

    /** One run of the app process. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private inner class App {
        /** The app's "main thread": GameSessionManager is confined to it, as on the phone. */
        val mainThread = Dispatchers.Default.limitedParallelism(1)
        val scope = CoroutineScope(SupervisorJob() + mainThread)
        private val httpClient = createHttpClient(OkHttp.create { addInterceptor(network) }, logRequests = false)
        val serverClock = ServerClock(clock::now)
        private val url = ServerUrl(serverUrl)
        private val api = HttpGameApi(httpClient, url)
        val session = GameSessionManager(
            api,
            PollingGameConnection(api),
            serverClock,
            gps,
            backgroundTracker,
            url,
            ClientStorage(storage),
            scope,
        )

        @Volatile var showingCodeFor: CatchId? = null
        private val handledClaims = HashSet<CatchId>()
        private val handledVotes = HashSet<CatchId>()
        private var previous = SessionState()

        init {
            scope.launch {
                session.state.collect { state ->
                    if (logChanges) logChanges(previous, state)
                    react(state)
                    previous = state
                }
            }
            // What the app does at start (MainActivity / mainViewController): back into a saved game.
            scope.launch { session.resumeSavedGame() }
        }

        fun close() {
            scope.cancel()
            httpClient.close()
        }

        private fun react(state: SessionState) {
            val snapshot = state.snapshot ?: return
            val me = snapshot.me.playerId
            val claim = snapshot.catches.lastOrNull { it.hiderId == me && it.status == CatchStatus.AWAITING_CODE }
            if (claim != null && handledClaims.add(claim.id)) {
                when (val reaction = behavior.onClaim) {
                    is ClaimReaction.ShowCode -> scope.launch {
                        delay(reaction.after)
                        showingCodeFor = claim.id
                        log("shows the catch code")
                    }

                    is ClaimReaction.Dispute -> scope.launch {
                        delay(reaction.after)
                        command("disputes the claim") { it.dispute(claim.id) }
                    }

                    ClaimReaction.Ignore -> log("ignores the claim")
                }
            }
            for (dispute in snapshot.catches.filter { it.canVote }) {
                val reaction = behavior.onDispute as? VoteReaction.Vote ?: continue
                if (!handledVotes.add(dispute.id)) continue
                scope.launch {
                    delay(reaction.after)
                    vote(dispute.id, reaction.confirm)
                }
            }
        }

        private fun logChanges(before: SessionState, after: SessionState) {
            if (!before.isResuming && after.isResuming) log("resumes the saved game")
            if (before.isResuming && !after.isResuming && after.session != null) log("is back in the game")
            if (before.connectionStatus != after.connectionStatus) log("connection ${after.connectionStatus}")
            val error = after.lastError
            if (error != null && error != before.lastError) log("error: $error")
            val old = before.snapshot
            val new = after.snapshot ?: return
            val names = new.players.associate { it.id to it.name }
            if (old?.phase != new.phase) log("sees phase ${new.phase}")
            if (old != null && old.me.status != new.me.status) log("is ${new.me.status}")
            val deadline = new.me.outOfZoneDeadlineMillis
            val buildingReveal = new.me.insideBuildingRevealAtMillis
            if (old?.me?.insideBuildingRevealAtMillis == null && buildingReveal != null) {
                log("warned: inside a building, seen in ${(buildingReveal - new.serverTimeMillis) / 1000} s")
            } else if (old?.me?.insideBuildingRevealAtMillis != null && buildingReveal == null) {
                log("building warning lifted")
            }
            if (old?.me?.outOfZoneDeadlineMillis == null && deadline != null) {
                log("warned: outside the zone, ${(deadline - new.serverTimeMillis) / 1000} s to return")
            } else if (old?.me?.outOfZoneDeadlineMillis != null && deadline == null) {
                log("out-of-zone warning lifted")
            }
            val oldCatches = old?.catches.orEmpty().associate { it.id to it.status }
            for (claim in new.catches) {
                if (oldCatches[claim.id] != claim.status) {
                    log("sees claim ${names[claim.seekerId]} → ${names[claim.hiderId]}: ${claim.status}")
                }
            }
            val oldVisible = old?.players.orEmpty().mapNotNull { p ->
                p.location?.let { p.id to it.exactReason }
            }.toMap()
            val newVisible = new.players.mapNotNull { p -> p.location?.let { p.id to it.exactReason } }.toMap()
            for ((id, reason) in newVisible) if (oldVisible[id] != reason) log("sees ${names[id]} ($reason)")
            for (id in oldVisible.keys - newVisible.keys) log("no longer sees ${names[id]}")
        }
    }
}

sealed interface CommandResult {
    data object Ok : CommandResult

    /** The server refused; [code] is its [ErrorCode]. */
    data class Rejected(val code: ErrorCode?, val message: String) : CommandResult

    /** Network or local failure. */
    data class Failed(val details: String?) : CommandResult
}
