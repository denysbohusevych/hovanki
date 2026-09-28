package app.hovanki.client.ui.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.session.CatchCode
import app.hovanki.client.session.ConnectionStatus
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.session.ServerClock
import app.hovanki.client.session.SessionError
import app.hovanki.client.session.SessionState
import app.hovanki.client.session.ZoneMoment
import app.hovanki.client.session.catchCodeToShow
import app.hovanki.client.session.catchQr
import app.hovanki.client.session.catchableScan
import app.hovanki.client.session.momentAt
import app.hovanki.client.session.myCatchCode
import app.hovanki.shared.geo.bearingTo
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

    /**
     * Text of a scanned QR code; ignored unless it is the code of the hider this claim is about. True when it was:
     * the camera can close.
     */
    fun onCodeScanned(claim: ClaimUi, text: String): Boolean {
        val payload = CatchCodePayload.decode(text) ?: return false
        val gameId = sessionManager.state.value.session?.gameId
        if (payload.gameId != gameId || payload.hiderId != claim.hiderId) return false
        confirmCatch(claim.id, payload.code)
        return true
    }

    /**
     * Text the seeker's camera read with no claim open («Found!»): when it is the QR code of a hider still playing, the
     * claim and the code go in one request. True when it was: the camera can close.
     */
    fun onFreeScan(text: String): Boolean {
        val payload = sessionManager.state.value.snapshot?.catchableScan(text) ?: return false
        runCommand { sessionManager.catchByScan(payload.hiderId, payload.code) }
        return true
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
        val zoneMoment = snapshot.settings.zone.momentAt(zoneStartedAt?.let { now - it })
        val openClaims = snapshot.catches.filter { it.status in OPEN_CLAIM_STATUSES }
        val myClaim = openClaims.firstOrNull { it.seekerId == me.playerId }
        val claimAgainstMe = openClaims.firstOrNull { it.hiderId == me.playerId }
        // Computed locally from the secret and server time: works even if the network drops right now.
        val catchCode = snapshot.catchCodeToShow(now)
        val canClaim = me.role == Role.SEEKER && me.status == PlayerStatus.ACTIVE &&
            snapshot.phase == GamePhase.SEEKING && myClaim == null
        val hiders = snapshot.players.filter { it.role == Role.HIDER }
        val myCode = snapshot.myCatchCode(now)?.takeIf { claimAgainstMe == null }
        val metersToBorder = myLocation?.let { zone.current.radiusMeters - it.point.distanceTo(zone.current.center) }
        val isHiding = me.role == Role.HIDER && me.status == PlayerStatus.ACTIVE
        val isOutside = me.outOfZoneDeadlineMillis != null || (metersToBorder ?: 0.0) < 0

        return GameUiState(
            phase = snapshot.phase,
            myRole = me.role,
            myStatus = me.status,
            phaseMillisLeft = snapshot.phaseEndsAtMillis?.let { it - now },
            hidingElapsedMillis = snapshot.phaseEndsAtMillis
                ?.takeIf { snapshot.phase == GamePhase.HIDING }
                ?.let { endsAt -> now - (endsAt - snapshot.settings.hidingSeconds * 1000L) },
            zone = zone,
            zoneMoment = zoneMoment,
            isZoneRunning = zoneStartedAt != null,
            myLocation = myLocation,
            metersToZoneBorder = metersToBorder,
            bearingToZone = myLocation?.point?.takeIf { isHiding && isOutside }?.bearingTo(zone.current.center),
            markers = snapshot.players.mapNotNull { player ->
                player.location?.let { MapMarker(player.id, player.name, it.point, it.accuracyMeters, it.exactReason) }
            },
            hidersLeft = hiders.count { it.status == PlayerStatus.ACTIVE },
            hidersTotal = hiders.size,
            huntableHiders = if (canClaim) {
                val claimedHiders = openClaims.map { it.hiderId }.toSet()
                hiders.filter { it.status == PlayerStatus.ACTIVE && it.id !in claimedHiders }
            } else {
                emptyList()
            },
            canScan = canClaim,
            myClaim = myClaim?.toUi(),
            myConfirmedCatches = snapshot.catches
                .filter { it.seekerId == me.playerId && it.status == CatchStatus.CONFIRMED }
                .map { it.id }
                .toSet(),
            claimAgainstMe = claimAgainstMe?.toUi(),
            catchCode = catchCode,
            catchQr = catchCode?.let(snapshot::catchQr),
            myCode = myCode,
            myQr = myCode?.let(snapshot::catchQr),
            codeDigits = rules.catchCodeDigits,
            codePeriodMillis = rules.catchCodePeriodSeconds * 1000L,
            claimTimeoutMillis = rules.catchCodeTimeoutSeconds * 1000L,
            voteTimeoutMillis = rules.disputeVoteSeconds * 1000L,
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
            joinCode = snapshot.joinCode,
            hasAccount = snapshot.players.any { it.id == me.playerId && it.userId != null },
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
    /** How long ago the hiding phase started (server time); null in other phases. */
    val hidingElapsedMillis: Long?,
    val zone: ZoneState,
    /** What the zone is doing, for the animations. */
    val zoneMoment: ZoneMoment,
    /** False during HIDING: the zone schedule starts with SEEKING. */
    val isZoneRunning: Boolean,
    val myLocation: LocationSample?,
    /** Positive inside the zone, negative outside. */
    val metersToZoneBorder: Double?,
    /**
     * A hider outside the zone (by the own GPS, or the server's alert): the compass bearing of the way back, straight to
     * the zone's center, for the arrow at the edge of the screen. Null inside, or without a position.
     */
    val bearingToZone: Double?,
    /** Players the server lets us see right now. */
    val markers: List<MapMarker>,
    val hidersLeft: Int,
    val hidersTotal: Int,
    /** Hiders an active seeker can claim now. */
    val huntableHiders: List<PlayerView>,
    /** Seeker: can find somebody right now, by scanning their code («Found!») or by picking their name. */
    val canScan: Boolean,
    /** Seeker: my open claim. */
    val myClaim: ClaimUi?,
    /** Seeker: my claims the hider's code (or the vote) confirmed, for the celebration. */
    val myConfirmedCatches: Set<CatchId>,
    /** Hider: the open claim against me. */
    val claimAgainstMe: ClaimUi?,
    /** Hider: the code to show while a claim awaits it. */
    val catchCode: CatchCode?,
    /** Hider: the same code for the seeker's camera, the text of the QR code ([CatchCodePayload]). */
    val catchQr: String?,
    /** Hider still playing while the seekers search, with no claim against them: the code to show without one. */
    val myCode: CatchCode?,
    val myQr: String?,
    val codeDigits: Int,
    /** How long a catch code lasts, and how long a hider has to show it: for the countdown rings. */
    val codePeriodMillis: Long,
    val claimTimeoutMillis: Long,
    val voteTimeoutMillis: Long,
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
    val joinCode: String,
    /** Playing with an account: joining again with [joinCode] gives the player back (e.g. after leaving). */
    val hasAccount: Boolean,
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
data class MapMarker(
    val id: PlayerId,
    val name: String,
    val point: GeoPoint,
    val accuracyMeters: Double,
    val reason: VisibilityReason,
)
