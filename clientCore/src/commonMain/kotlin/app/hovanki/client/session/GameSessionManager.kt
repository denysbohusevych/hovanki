package app.hovanki.client.session

import app.hovanki.client.location.LocationProvider
import app.hovanki.client.network.ApiException
import app.hovanki.client.network.ConnectionEvent
import app.hovanki.client.network.GameApi
import app.hovanki.client.network.GameConnection
import app.hovanki.client.network.LocationOutbox
import app.hovanki.client.tracking.BackgroundTracker
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.rules.shrinkingZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one place that owns the current game: session credentials, the latest snapshot, the connection to the server
 * and our own location updates. App-scoped (outlives screens), so a round keeps running while the UI changes.
 *
 * Commands return true on success; on failure they return false and put the reason into [SessionState.lastError].
 * Every command applies the snapshot from its response immediately. Runs on the main thread.
 */
class GameSessionManager(
    private val api: GameApi,
    private val connection: GameConnection,
    private val clock: ServerClock,
    private val locationProvider: LocationProvider,
    private val backgroundTracker: BackgroundTracker,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val mutableState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private val mutableMyLocation = MutableStateFlow<LocationSample?>(null)

    /** Latest own fix in server time. Snapshots never contain the viewer's own position. */
    val myLocation: StateFlow<LocationSample?> = mutableMyLocation.asStateFlow()

    // One outbox per session: samples of a finished game must never be uploaded to the next one.
    private var outbox = LocationOutbox()
    private var connectionJob: Job? = null
    private var locationJob: Job? = null
    private var isTracking = false

    /** New game with the default settings: a shrinking zone around [center] (the host's position). */
    suspend fun create(playerName: String, center: GeoPoint): Boolean =
        create(playerName, GameSettings(zone = shrinkingZone(center)))

    suspend fun create(playerName: String, settings: GameSettings): Boolean = command {
        begin(api.createGame(CreateGameRequest(playerName.trim(), settings)))
    }

    suspend fun join(code: String, playerName: String): Boolean = command {
        begin(api.joinGame(JoinGameRequest(code.trim().uppercase(), playerName.trim())))
    }

    suspend fun start(seekers: List<PlayerId>): Boolean = sessionCommand {
        api.startGame(it, StartGameRequest(seekers))
    }

    suspend fun claimCatch(hiderId: PlayerId): Boolean = sessionCommand { api.claimCatch(it, hiderId) }

    suspend fun confirmCatch(catchId: CatchId, code: String): Boolean =
        sessionCommand { api.confirmCatch(it, catchId, code.trim()) }

    suspend fun dispute(catchId: CatchId): Boolean = sessionCommand { api.disputeCatch(it, catchId) }

    suspend fun vote(catchId: CatchId, confirm: Boolean): Boolean = sessionCommand { api.vote(it, catchId, confirm) }

    /** Leaves the game locally (the server has no "leave": silence reveals the player like a lost signal). */
    fun leave() {
        stopBackgroundWork()
        mutableState.value = SessionState()
    }

    fun clearError() {
        mutableState.update { it.copy(lastError = null) }
    }

    /** Call after the location permission was granted while a game is running. */
    fun onLocationPermissionGranted() {
        startLocationUpdates()
        // The tracker may have refused to start without the permission.
        if (isTracking) backgroundTracker.start()
    }

    /** First fix good enough to center a new game on, or null (no permission, no fix within [timeoutMillis]). */
    suspend fun currentLocation(timeoutMillis: Long): LocationSample? {
        if (!locationProvider.hasPermission()) return null
        return try {
            withTimeoutOrNull(timeoutMillis) {
                locationProvider.locationUpdates(FIRST_FIX_INTERVAL_MILLIS)
                    .firstOrNull { it.accuracyMeters <= GOOD_FIX_ACCURACY_METERS }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun begin(response: SessionResponse) {
        stopBackgroundWork()
        val session = response.session
        mutableState.value = SessionState(session = session)
        applySnapshot(response.snapshot)
        val sessionOutbox = LocationOutbox()
        outbox = sessionOutbox
        connectionJob = scope.launch {
            // Off the main thread: JSON of every poll is parsed in the flow.
            connection.connect(session, sessionOutbox)
                .flowOn(Dispatchers.Default)
                .collect { onConnectionEvent(it) }
        }
        startLocationUpdates()
    }

    private fun onConnectionEvent(event: ConnectionEvent) {
        when (event) {
            is ConnectionEvent.Snapshot -> {
                mutableState.update { it.copy(connectionStatus = ConnectionStatus.ONLINE) }
                applySnapshot(event.snapshot)
            }

            is ConnectionEvent.Problem ->
                mutableState.update { it.copy(connectionStatus = ConnectionStatus.RECONNECTING) }

            is ConnectionEvent.Ended -> {
                stopBackgroundWork()
                mutableState.value = SessionState(lastError = SessionError.SessionLost)
            }
        }
    }

    private fun applySnapshot(snapshot: GameSnapshot) {
        val current = mutableState.value
        // A late response from a previous game must not leak into the current one.
        if (current.session?.gameId != snapshot.gameId) return
        // A slow poll can be overtaken by a command's response: keep the newer state.
        val previous = current.snapshot
        if (previous != null && snapshot.serverTimeMillis < previous.serverTimeMillis) return

        clock.onServerTime(snapshot.serverTimeMillis)
        mutableState.update { it.copy(snapshot = snapshot) }
        when (snapshot.phase) {
            GamePhase.LOBBY -> Unit

            GamePhase.HIDING, GamePhase.SEEKING -> startTracking()

            // Results are final: no more polling, location or foreground service.
            GamePhase.FINISHED -> stopBackgroundWork()
        }
    }

    private fun startLocationUpdates() {
        val snapshot = mutableState.value.snapshot ?: return
        if (snapshot.phase == GamePhase.FINISHED) return
        if (locationJob?.isActive == true || !locationProvider.hasPermission()) return

        val intervalMillis = snapshot.settings.rules.syncIntervalSeconds.coerceAtLeast(1) * 1000L
        val sessionOutbox = outbox
        mutableState.update { it.copy(isSharingLocation = true) }
        val job = scope.launch {
            try {
                locationProvider.locationUpdates(intervalMillis).collect { fix ->
                    val sample = fix.copy(timestampMillis = clock.toServerTime(fix.timestampMillis))
                    mutableMyLocation.value = sample
                    sessionOutbox.add(sample)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Permission revoked or location switched off: the UI offers to turn it back on.
            }
        }
        locationJob = job
        job.invokeOnCompletion {
            // Only if no newer job took over meanwhile.
            if (locationJob === job) mutableState.update { it.copy(isSharingLocation = false) }
        }
    }

    private fun startTracking() {
        if (isTracking) return
        isTracking = true
        backgroundTracker.start()
    }

    private fun stopBackgroundWork() {
        connectionJob?.cancel()
        connectionJob = null
        locationJob?.cancel()
        locationJob = null
        outbox.clear()
        mutableMyLocation.value = null
        mutableState.update { it.copy(isSharingLocation = false) }
        if (isTracking) {
            isTracking = false
            backgroundTracker.stop()
        }
    }

    private suspend fun sessionCommand(call: suspend (PlayerSession) -> GameSnapshot): Boolean {
        val session = mutableState.value.session ?: return false
        return command { applySnapshot(call(session)) }
    }

    private suspend fun command(block: suspend () -> Unit): Boolean = try {
        block()
        mutableState.update { it.copy(lastError = null) }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        fail(SessionError.Rejected(e.error?.code, e.error?.message ?: e.message.orEmpty()))
    } catch (e: Exception) {
        fail(SessionError.Network(e.message))
    }

    private fun fail(error: SessionError): Boolean {
        mutableState.update { it.copy(lastError = error) }
        return false
    }

    private companion object {
        const val FIRST_FIX_INTERVAL_MILLIS = 1_000L

        /** Good enough to center the zone on (the default zone is hundreds of meters wide). */
        const val GOOD_FIX_ACCURACY_METERS = 50.0
    }
}
