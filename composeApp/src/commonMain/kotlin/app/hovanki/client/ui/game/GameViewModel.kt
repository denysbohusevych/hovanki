package app.hovanki.client.ui.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.session.CatchCode
import app.hovanki.client.session.ConnectionStatus
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.session.ServerClock
import app.hovanki.client.session.SessionError
import app.hovanki.client.session.SessionState
import app.hovanki.client.session.catchCodeToShow
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.CatchView
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.rules.ZoneState
import app.hovanki.shared.rules.stateAt
import app.hovanki.shared.totp.CatchCodePayload
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GameViewModel(private val sessionManager: GameSessionManager, private val clock: ServerClock) : ViewModel() {
    private val isBusy = MutableStateFlow(false)

    /**
     * Server time once per second, aligned to whole seconds so the countdowns and the catch code (which changes
     * on period boundaries) flip on time. Only runs while the screen is visible.
     */
    private val ticks: Flow<Long> = flow {
        while (true) {
            val now = clock.now()
            emit(now)
            delay(TICK_MILLIS - now.mod(TICK_MILLIS))
        }
    }

    val uiState: StateFlow<GameUiState?> =
        combine(sessionManager.state, sessionManager.myLocation, ticks, isBusy) { state, myLocation, now, busy ->
            buildUiState(state, myLocation, now, busy)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            buildUiState(sessionManager.state.value, sessionManager.myLocation.value, clock.now(), busy = false),
        )

    fun claimCatch(hiderId: PlayerId) = runCommand { sessionManager.claimCatch(hiderId) }

    fun confirmCatch(catchId: CatchId, code: String) = runCommand { sessionManager.confirmCatch(catchId, code) }

    /** Text of a scanned QR code; ignored unless it is the code of the hider this claim is about. */
    fun onCodeScanned(claim: ClaimUi, text: String) {
        val payload = CatchCodePayload.decode(text) ?: return
        val gameId = sessionManager.state.value.session?.gameId
        if (payload.gameId == gameId && payload.hiderId == claim.hiderId) confirmCatch(claim.id, payload.code)
    }

    fun dispute(catchId: CatchId) = runCommand { sessionManager.dispute(catchId) }

    fun vote(catchId: CatchId, confirm: Boolean) = runCommand { sessionManager.vote(catchId, confirm) }

    fun leave() = sessionManager.leave()

    fun dismissError() = sessionManager.clearError()

    fun onLocationPermissionGranted() = sessionManager.onLocationPermissionGranted()

    private fun runCommand(command: suspend () -> Boolean) {
        // One request at a time: double taps must not send two claims.
        if (isBusy.value) return
        isBusy.value = true
        viewModelScope.launch {
            try {
                command()
            } finally {
                isBusy.value = false
            }
        }
    }

    private fun buildUiState(state: SessionState, myLocation: LocationSample?, now: Long, busy: Boolean): GameUiState? {
        val snapshot = state.snapshot ?: return null
        val me = snapshot.me
        val rules = snapshot.settings.rules
        val names = snapshot.players.associate { it.id to it.name }
        fun CatchView.toUi() = ClaimUi(
            id = id,
            hiderId = hiderId,
            seekerName = names[seekerId].orEmpty(),
            hiderName = names[hiderId].orEmpty(),
            status = status,
            millisLeft = deadlineMillis?.let { it - now },
            canVote = canVote,
            myVote = myVote,
        )

        // Before SEEKING the zone has not started yet: show its initial circle.
        val zoneStartedAt = snapshot.zoneStartedAtMillis
        val zone = snapshot.settings.zone.stateAt(if (zoneStartedAt == null) 0L else now - zoneStartedAt)
        val openClaims = snapshot.catches.filter { it.status in OPEN_CLAIM_STATUSES }
        val myClaim = openClaims.firstOrNull { it.seekerId == me.playerId }
        val claimAgainstMe = openClaims.firstOrNull { it.hiderId == me.playerId }
        // Computed locally from the secret and server time: works even if the network drops right now.
        val catchCode = snapshot.catchCodeToShow(now)
        val canClaim = me.role == Role.SEEKER && me.status == PlayerStatus.ACTIVE &&
            snapshot.phase == GamePhase.SEEKING && myClaim == null
        val hiders = snapshot.players.filter { it.role == Role.HIDER }

        return GameUiState(
            phase = snapshot.phase,
            myRole = me.role,
            myStatus = me.status,
            phaseMillisLeft = snapshot.phaseEndsAtMillis?.let { it - now },
            zone = zone,
            isZoneRunning = zoneStartedAt != null,
            myLocation = myLocation,
            metersToZoneBorder = myLocation?.let {
                zone.current.radiusMeters - it.point.distanceTo(zone.current.center)
            },
            markers = snapshot.players.mapNotNull { player ->
                player.location?.let { MapMarker(player.name, it.point, it.accuracyMeters, it.exactReason) }
            },
            hidersLeft = hiders.count { it.status == PlayerStatus.ACTIVE },
            hidersTotal = hiders.size,
            huntableHiders = if (canClaim) {
                val claimedHiders = openClaims.map { it.hiderId }.toSet()
                hiders.filter { it.status == PlayerStatus.ACTIVE && it.id !in claimedHiders }
            } else {
                emptyList()
            },
            myClaim = myClaim?.toUi(),
            claimAgainstMe = claimAgainstMe?.toUi(),
            catchCode = catchCode,
            codeDigits = rules.catchCodeDigits,
            votes = openClaims
                .filter { it.status == CatchStatus.DISPUTED && (it.canVote || it.myVote != null) }
                .map { it.toUi() },
            outOfZoneMillisLeft = me.outOfZoneDeadlineMillis?.let { it - now },
            insideBuildingMillisLeft = me.insideBuildingRevealAtMillis?.let { it - now },
            buildings = state.buildings?.takeIf { snapshot.buildings == BuildingsState.READY },
            isBuildingRuleOff = snapshot.buildings == BuildingsState.UNAVAILABLE,
            connectionStatus = state.connectionStatus,
            isSharingLocation = state.isSharingLocation,
            error = state.lastError,
            isBusy = busy,
        )
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
        val OPEN_CLAIM_STATUSES = setOf(CatchStatus.AWAITING_CODE, CatchStatus.DISPUTED)
    }
}

data class GameUiState(
    val phase: GamePhase,
    val myRole: Role,
    val myStatus: PlayerStatus,
    val phaseMillisLeft: Long?,
    val zone: ZoneState,
    /** False during HIDING: the zone schedule starts with SEEKING. */
    val isZoneRunning: Boolean,
    val myLocation: LocationSample?,
    /** Positive inside the zone, negative outside. */
    val metersToZoneBorder: Double?,
    /** Players the server lets us see right now. */
    val markers: List<MapMarker>,
    val hidersLeft: Int,
    val hidersTotal: Int,
    /** Hiders an active seeker can claim now. */
    val huntableHiders: List<PlayerView>,
    /** Seeker: my open claim. */
    val myClaim: ClaimUi?,
    /** Hider: the open claim against me. */
    val claimAgainstMe: ClaimUi?,
    /** Hider: the code to show while a claim awaits it. */
    val catchCode: CatchCode?,
    val codeDigits: Int,
    /** Disputes of other players I vote (or voted) on. */
    val votes: List<ClaimUi>,
    val outOfZoneMillisLeft: Long?,
    /** Hider inside a building: time until the seekers see them; zero or less once they do. */
    val insideBuildingMillisLeft: Long?,
    /** Where hiding is not allowed, exactly as the server judges; null while not loaded. */
    val buildings: BuildingsResponse?,
    /** The server could not load the buildings: the game runs without that rule. */
    val isBuildingRuleOff: Boolean,
    val connectionStatus: ConnectionStatus,
    val isSharingLocation: Boolean,
    val error: SessionError?,
    val isBusy: Boolean,
)

data class ClaimUi(
    val id: CatchId,
    val hiderId: PlayerId,
    val seekerName: String,
    val hiderName: String,
    val status: CatchStatus,
    val millisLeft: Long?,
    val canVote: Boolean,
    val myVote: Boolean?,
)

/** A player the server lets us see, on the map. */
data class MapMarker(val name: String, val point: GeoPoint, val accuracyMeters: Double, val reason: VisibilityReason)
