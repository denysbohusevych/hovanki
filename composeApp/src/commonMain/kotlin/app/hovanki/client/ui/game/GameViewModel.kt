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
import app.hovanki.radar.PeerRange
import app.hovanki.shared.geo.bearingTo
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.BoardItem
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.CatchView
import app.hovanki.shared.protocol.DistanceBand
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.HintKind
import app.hovanki.shared.protocol.ItemId
import app.hovanki.shared.protocol.ItemKind
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PerkKind
import app.hovanki.shared.protocol.PerkView
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.QuestView
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.StreetZoneResponse
import app.hovanki.shared.protocol.StreetZoneState
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.rules.CheckpointPayload
import app.hovanki.shared.rules.Glow
import app.hovanki.shared.rules.PerkCatalog
import app.hovanki.shared.rules.StreetZone
import app.hovanki.shared.rules.ZoneArea
import app.hovanki.shared.rules.ZoneState
import app.hovanki.shared.rules.areaAt
import app.hovanki.shared.rules.stateAt
import app.hovanki.shared.rules.withOpenBuildings
import app.hovanki.shared.totp.CatchCodePayload
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The round. One state for the whole screen, [uiState], and one way in, [onEvent] (docs/architecture.md, «Состояние
 * экрана»): the round as the server sent it, rebuilt every second, with what this phone has open over the map
 * ([GameLocal]).
 */
class GameViewModel(private val sessionManager: GameSessionManager, private val clock: ServerClock) : ViewModel() {
    private val local = MutableStateFlow(GameLocal())

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

    /** What the phone itself knows of the radar: the pulse's band and the UWB ranges. */
    private val phone: Flow<Phone> = combine(sessionManager.pulse, sessionManager.ranges) { pulse, ranges ->
        Phone(pulse, ranges)
    }

    val uiState: StateFlow<GameUiState?> =
        combine(sessionManager.state, sessionManager.myLocation, ticks, local, phone) {
                state,
                myLocation,
                now,
                local,
                phone,
            ->
            withLocal(buildUiState(state, myLocation, now, phone), local)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            withLocal(
                buildUiState(
                    sessionManager.state.value,
                    sessionManager.myLocation.value,
                    clock.now(),
                    phone = Phone(sessionManager.pulse.value, sessionManager.ranges.value),
                ),
                local.value,
            ),
        )

    /** The round with what is open over it; what the round took away is forgotten for good. */
    private fun withLocal(state: GameUiState?, current: GameLocal): GameUiState? {
        if (state == null) return null
        val reconciled = current.reconciled(state)
        if (reconciled != current) local.compareAndSet(current, reconciled)
        return reconciled.applyTo(state)
    }

    fun onEvent(event: GameEvent) {
        when (event) {
            is GameEvent.SetPaused -> runCommand { sessionManager.setPaused(event.paused) }

            GameEvent.CallSos -> {
                update { it.copy(dialog = null) }
                runCommand { sessionManager.callSos() }
            }

            is GameEvent.EndSos -> runCommand { sessionManager.endSos(event.playerId) }

            is GameEvent.ClaimCatch -> runCommand { sessionManager.claimCatch(event.hiderId) }

            is GameEvent.ConfirmCatch -> confirmCatch(event.catchId, event.code)

            is GameEvent.Dispute -> runCommand { sessionManager.dispute(event.catchId) }

            is GameEvent.Vote -> runCommand { sessionManager.vote(event.catchId, event.confirm) }

            is GameEvent.QuestDone -> runCommand { sessionManager.questDone(event.questId) }

            is GameEvent.ReviewQuest -> runCommand {
                sessionManager.reviewQuest(event.questId, event.playerId, event.approved)
            }

            GameEvent.Leave -> sessionManager.leave()

            GameEvent.DismissError -> sessionManager.clearError()

            GameEvent.LocationPermissionGranted -> sessionManager.onLocationPermissionGranted()

            is GameEvent.OpenDialog -> update { it.copy(dialog = event.dialog) }

            GameEvent.CloseDialog -> update { it.copy(dialog = null) }

            is GameEvent.OpenPanel -> update { it.copy(panel = event.panel, perkTargeting = null) }

            GameEvent.ClosePanel -> update { it.copy(panel = null, perkTargeting = null) }

            is GameEvent.UsePerk -> usePerk(event.perk, event.targetId)

            is GameEvent.PickDecoy -> update { it.copy(decoy = it.decoy?.copy(pick = event.point)) }

            GameEvent.PutDecoy -> {
                local.value.decoy?.pick?.let { point ->
                    runCommand { sessionManager.usePerk(PerkKind.DECOY, null, point) }
                }
                update { it.copy(decoy = null) }
            }

            GameEvent.CancelDecoy -> update { it.copy(decoy = null) }

            is GameEvent.ShowMyCode -> update { it.copy(showingMyCode = event.shown) }

            is GameEvent.OpenScanner -> update {
                when (event.scanner) {
                    GameScanner.CLAIM -> it.copy(scanningClaim = uiState.value?.myClaim?.id)
                    GameScanner.FREE -> it.copy(scanningFree = true)
                    GameScanner.CHECKPOINT -> it.copy(scanningCheckpoint = true)
                }
            }

            is GameEvent.CloseScanner -> closeScanner(event.scanner)

            is GameEvent.Scanned -> if (scanned(event.scanner, event.text)) closeScanner(event.scanner)
        }
    }

    private fun update(change: (GameLocal) -> GameLocal) = local.update(change)

    private fun closeScanner(scanner: GameScanner) = update {
        when (scanner) {
            GameScanner.CLAIM -> it.copy(scanningClaim = null)
            GameScanner.FREE -> it.copy(scanningFree = false)
            GameScanner.CHECKPOINT -> it.copy(scanningCheckpoint = false)
        }
    }

    /** True when the camera read a code it takes: it closes. */
    private fun scanned(scanner: GameScanner, text: String): Boolean = when (scanner) {
        GameScanner.CLAIM -> uiState.value?.scannedClaim?.let { onCodeScanned(it, text) } == true
        GameScanner.FREE -> onFreeScan(text)
        GameScanner.CHECKPOINT -> onCheckpointScanned(text)
    }

    /** A perk aimed at a hider asks which one first; the decoy goes to the map; the others are used at once. */
    private fun usePerk(perk: PerkKind, targetId: PlayerId?) {
        val spec = PerkCatalog.spec(perk)
        when {
            targetId != null -> {
                runCommand { sessionManager.usePerk(perk, targetId, null) }
                update { it.copy(panel = null, perkTargeting = null) }
            }

            spec.needsTarget -> update { it.copy(perkTargeting = perk) }

            spec.needsPoint -> update { it.copy(panel = null, perkTargeting = null, decoy = DecoyUi()) }

            else -> {
                runCommand { sessionManager.usePerk(perk, null, null) }
                update { it.copy(panel = null) }
            }
        }
    }

    /**
     * The round's time now, for what moves on its own between the once-a-second states (the map's zone): the server's,
     * standing still at the pause's start while the round is on pause (docs/adr/0019-pause-and-sos.md).
     */
    fun serverNow(): Long = roundTime(sessionManager.state.value.snapshot?.pause?.sinceMillis, clock.now())

    private fun confirmCatch(catchId: CatchId, code: String) = runCommand { sessionManager.confirmCatch(catchId, code) }

    /**
     * Text of a scanned QR code; ignored unless it is the code of the hider this claim is about. True when it was:
     * the camera can close.
     */
    private fun onCodeScanned(claim: ClaimUi, text: String): Boolean {
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
    private fun onFreeScan(text: String): Boolean {
        val payload = sessionManager.state.value.snapshot?.catchableScan(text) ?: return false
        runCommand { sessionManager.catchByScan(payload.hiderId, payload.code) }
        return true
    }

    /**
     * Text the camera read at a checkpoint: when it is a checkpoint's code, it goes to the server (which may still
     * refuse it). True when it was: the camera can close.
     */
    private fun onCheckpointScanned(text: String): Boolean {
        if (CheckpointPayload.decode(text) == null) return false
        runCommand { sessionManager.scanCheckpoint(text) }
        return true
    }

    private fun runCommand(command: suspend () -> Boolean) {
        // One request at a time: double taps must not send two claims.
        if (local.value.isBusy) return
        update { it.copy(isBusy = true) }
        viewModelScope.launch {
            try {
                command()
            } finally {
                update { it.copy(isBusy = false) }
            }
        }
    }

    /** The last zone by streets made from the server's polygons: the same object while they stay the same. */
    private var streetZoneCache: Pair<List<ZonePolygon>, StreetZone>? = null

    private fun streetZoneOf(response: StreetZoneResponse): StreetZone {
        streetZoneCache?.let { (stages, zone) -> if (stages == response.stages) return zone }
        return StreetZone(response.stages).also { streetZoneCache = response.stages to it }
    }

    private fun buildUiState(
        state: SessionState,
        myLocation: LocationSample?,
        realNow: Long,
        phone: Phone,
    ): GameUiState? {
        val snapshot = state.snapshot ?: return null
        val me = snapshot.me
        // The catch code goes by the real time; everything the round counts stands still on pause.
        val now = roundTime(snapshot.pause?.sinceMillis, realNow)
        val rules = snapshot.settings.rules
        val features = snapshot.settings.features
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
        // The zone by streets, once loaded for this map revision: the rules check it instead of the circles.
        val streets = state.streetZone
            ?.takeIf {
                it.mapRevision == snapshot.mapRevision &&
                    it.stages.size == snapshot.settings.zone.stages.size + 1
            }
            ?.takeIf { zoneByStreets -> zoneByStreets.stages.all { it.outline.size >= MIN_OUTLINE_POINTS } }
            ?.let(::streetZoneOf)
        val zoneArea = snapshot.settings.zone.areaAt(if (zoneStartedAt == null) 0L else now - zoneStartedAt, streets)
        val glow = glowAt(snapshot.settings, snapshot.phase, zoneStartedAt, now)
        val openClaims = snapshot.catches.filter { it.status in OPEN_CLAIM_STATUSES }
        val myClaim = openClaims.firstOrNull { it.seekerId == me.playerId }
        val claimAgainstMe = openClaims.firstOrNull { it.hiderId == me.playerId }
        // Computed locally from the secret and server time: works even if the network drops right now.
        val catchCode = snapshot.catchCodeToShow(realNow)
        val canClaim = me.role == Role.SEEKER && me.status == PlayerStatus.ACTIVE &&
            snapshot.phase == GamePhase.SEEKING && myClaim == null && snapshot.pause == null
        val hiders = snapshot.players.filter { it.role == Role.HIDER }
        val myCode = snapshot.myCatchCode(realNow)?.takeIf { claimAgainstMe == null }
        val metersToBorder = myLocation?.let { -zoneArea.signedDistanceMeters(it.point) }
        val isHiding = me.role == Role.HIDER && me.status == PlayerStatus.ACTIVE
        val isOutside = me.outOfZoneDeadlineMillis != null || (metersToBorder ?: 0.0) < 0
        val playing = me.role == Role.SEEKER || me.status == PlayerStatus.ACTIVE
        val items = snapshot.items.map { it.toMapItem(me.playerId) }
        val checkpoints = snapshot.items.filter {
            it.kind == ItemKind.CHECKPOINT_GEO || it.kind == ItemKind.CHECKPOINT_SCAN
        }
        val inRound = snapshot.phase == GamePhase.HIDING || snapshot.phase == GamePhase.SEEKING
        val sos = snapshot.sos.map { call ->
            SosUi(
                playerId = call.playerId,
                name = call.name,
                isMe = call.playerId == me.playerId,
                point = call.location?.point,
                metersAway = call.location?.point?.let { there -> myLocation?.point?.distanceTo(there) }
                    ?.takeIf { call.playerId != me.playerId },
            )
        }
        val sosMarkers = sos.mapNotNull { call ->
            val point = call.point?.takeIf { !call.isMe } ?: return@mapNotNull null
            val accuracy = snapshot.sos.first { it.playerId == call.playerId }.location?.accuracyMeters ?: 0.0
            MapMarker(call.playerId, call.name, point, accuracy, VisibilityReason.TEAMMATE, isSos = true)
        }

        return GameUiState(
            now = now,
            phase = snapshot.phase,
            myRole = me.role,
            myStatus = me.status,
            isHost = snapshot.hostId == me.playerId,
            names = names,
            phaseMillisLeft = snapshot.phaseEndsAtMillis?.let { it - now },
            hidingElapsedMillis = snapshot.phaseEndsAtMillis
                ?.takeIf { snapshot.phase == GamePhase.HIDING }
                ?.let { endsAt -> now - (endsAt - snapshot.settings.hidingSeconds * 1000L) },
            zone = zone,
            zoneMoment = zoneMoment,
            isZoneRunning = zoneStartedAt != null,
            myLocation = myLocation,
            metersToZoneBorder = metersToBorder,
            bearingToZone = myLocation?.point?.takeIf { isHiding && isOutside }?.let { point ->
                // Outside by the own GPS: the shortest way in, to the nearest point of the border. Inside by the own
                // GPS but not by the server's: towards the middle.
                val target = if ((metersToBorder ?: 0.0) <
                    0
                ) {
                    zoneArea.nearestBorderPoint(point)
                } else {
                    zone.current.center
                }
                point.bearingTo(target)
            },
            zoneTimeline = ZoneTimeline(snapshot.settings.zone, zoneStartedAt, streets),
            isStreetZoneOff = snapshot.streetZone == StreetZoneState.UNAVAILABLE,
            glow = glow,
            spectators = if (snapshot.settings.openGame) snapshot.spectators else 0,
            markers = sosMarkers + snapshot.players.filter { player -> sosMarkers.none { it.id == player.id } }
                .mapNotNull { player ->
                    player.location?.let {
                        val reason = it.exactReason
                        MapMarker(
                            id = player.id,
                            name = player.name,
                            point = it.point,
                            accuracyMeters = it.accuracyMeters,
                            reason = reason,
                            // Between glows the seekers see where the last one left the hider: a spot, not the hider.
                            markAgeMillis = if (reason == VisibilityReason.GLOW && glow?.isGlowing != true) {
                                (now - it.atMillis).coerceAtLeast(0)
                            } else {
                                null
                            },
                        )
                    }
                },
            // A big game lists only some players: how many there are in all comes with the snapshot.
            hidersLeft = snapshot.counts?.hidersActive ?: hiders.count { it.status == PlayerStatus.ACTIVE },
            hidersTotal = snapshot.counts?.let { it.players - it.seekers } ?: hiders.size,
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
            buildings = state.buildings
                ?.takeIf { snapshot.buildings == BuildingsState.READY }
                ?.withOpenBuildings(snapshot.settings.openBuildings),
            isBuildingRuleOff = snapshot.buildings == BuildingsState.UNAVAILABLE,
            connectionStatus = state.connectionStatus,
            isSharingLocation = state.isSharingLocation,
            error = state.lastError,
            joinCode = snapshot.joinCode,
            hasAccount = snapshot.players.any { it.id == me.playerId && it.userId != null },
            activeHiders = hiders.filter { it.status == PlayerStatus.ACTIVE && !it.left },
            hasRadar = features.hasRadar,
            radar = me.radar?.contacts?.mapNotNull { contact -> contact.playerId?.let { it to contact.band } }
                ?.toMap().orEmpty(),
            pulse = phone.pulse,
            ranges = phone.ranges,
            bluetoothMillisLeft = me.bluetoothDeadlineMillis?.let { it - now },
            sparks = me.sparks.takeIf { features.hasSparks },
            hint = me.hint?.let { HintUi(it.kind, it.sector, it.band, it.untilMillis - now) }?.takeIf {
                it.millisLeft >
                    0
            },
            quests = snapshot.quests,
            perks = me.perks,
            items = items,
            canScanCheckpoint = features.checkpoints && playing && snapshot.phase == GamePhase.SEEKING &&
                snapshot.pause == null &&
                checkpoints.any { it.kind == ItemKind.CHECKPOINT_SCAN && me.playerId !in it.takenBy },
            checkpointsTaken = checkpoints.count { me.playerId in it.takenBy },
            pendingReviews = if (snapshot.hostId == me.playerId) snapshot.quests.sumOf { it.pending.size } else 0,
            pause = snapshot.pause?.let { PauseUi(bySos = it.sos) },
            sos = sos,
            // A big game's host is the server: only an SOS stops its round.
            canPause = inRound && snapshot.hostId == me.playerId && snapshot.bigGame == null,
            canSos = inRound && snapshot.players.none { it.id == me.playerId && it.left },
        )
    }

    /** The glow as the HUD counts it down (docs/adr/0009-game-setup-glow-streets.md): only while the seekers search. */
    private fun glowAt(settings: GameSettings, phase: GamePhase, seekingStartedAt: Long?, now: Long): GlowUi? {
        if (phase != GamePhase.SEEKING || seekingStartedAt == null) return null
        Glow.openAt(settings, seekingStartedAt, now)?.let {
            return GlowUi(
                isGlowing = true,
                millisLeft =
                it.endMillis - now,
            )
        }
        val next = Glow.next(settings, seekingStartedAt, now) ?: return null
        return GlowUi(isGlowing = false, millisLeft = next.startMillis - now)
    }

    private companion object {
        const val TICK_MILLIS = 1_000L

        /** The round's time at [now]: it stands at [pausedAt] while the round is on pause. */
        fun roundTime(pausedAt: Long?, now: Long): Long = pausedAt?.let { minOf(it, now) } ?: now

        /** A closed ring of a triangle at least; the server never sends less. */
        const val MIN_OUTLINE_POINTS = 4
        val OPEN_CLAIM_STATUSES = setOf(CatchStatus.AWAITING_CODE, CatchStatus.DISPUTED)
    }
}

/** What the phone itself knows of the radar (docs/adr/0012-nearby-radar.md). */
private data class Phone(val pulse: RadarBand, val ranges: List<PeerRange>)

data class GameUiState(
    /** Server time this state was built at. */
    val now: Long,
    val phase: GamePhase,
    val myRole: Role,
    val myStatus: PlayerStatus,
    val isHost: Boolean,
    /** Every player's name, for the quests and the perks. */
    val names: Map<PlayerId, String>,
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
     * A hider outside the zone (by the own GPS, or the server's alert): the compass bearing of the way back, to the
     * nearest point of the border (to the zone's middle while the own GPS says inside), for the arrow at the edge of
     * the screen. Null inside, or without a position.
     */
    val bearingToZone: Double?,
    /** The zone's schedule, start and zone by streets: the map draws the zone from it by the moment. */
    val zoneTimeline: ZoneTimeline,
    /** The game was set up with a zone by streets, but the server could not build one: it uses the circles. */
    val isStreetZoneOff: Boolean,
    /** The glow while the seekers search; null in a game without it, and after the last one. */
    val glow: GlowUi?,
    /** How many watch this open game right now (docs/adr/0011-spectators-and-recordings.md); 0 in a closed one. */
    val spectators: Int = 0,
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
    val isBusy: Boolean = false,
    val joinCode: String,
    /** Playing with an account: joining again with [joinCode] gives the player back (e.g. after leaving). */
    val hasAccount: Boolean,
    /** Hiders still in the game: the targets of a seeker's perks. */
    val activeHiders: List<PlayerView> = emptyList(),
    /** The game has the radar (docs/adr/0012-nearby-radar.md), whether or not this phone takes part. */
    val hasRadar: Boolean = false,
    /** A seeker's radar: the band per hider the phones hear; empty without the radar or a signal. */
    val radar: Map<PlayerId, RadarBand> = emptyMap(),
    /** The pulse: a hider's nearest seeker, a seeker's nearest hider; [RadarBand.NONE] when quiet. */
    val pulse: RadarBand = RadarBand.NONE,
    /** Metres by UWB to the players this phone ranges with, while both look at their phones. */
    val ranges: List<PeerRange> = emptyList(),
    /** The radar is required and this phone has Bluetooth off: the seekers see the hider in so long (≤ 0: now). */
    val bluetoothMillisLeft: Long? = null,
    /** The viewer's sparks; null in a game without them (docs/adr/0013-quests-sparks-and-sensors.md). */
    val sparks: Int? = null,
    /** A hint a perk bought, while it lasts. */
    val hint: HintUi? = null,
    val quests: List<QuestView> = emptyList(),
    val perks: List<PerkView> = emptyList(),
    /** The board on the map. */
    val items: List<MapItem> = emptyList(),
    /** There is a checkpoint by code this player has not scanned yet. */
    val canScanCheckpoint: Boolean = false,
    /** Checkpoints this player has reached, for the «taken» moment. */
    val checkpointsTaken: Int = 0,
    /** The host: players waiting for the host's answer on the host's quests. */
    val pendingReviews: Int = 0,
    /** The round stands still (docs/adr/0019-pause-and-sos.md); null: it goes on. */
    val pause: PauseUi? = null,
    /** Who calls for help right now, with where they are. */
    val sos: List<SosUi> = emptyList(),
    /** The host of an ordinary game, in the round: may put it on pause and let it go on. */
    val canPause: Boolean = false,
    /** In the round: may call for help. */
    val canSos: Boolean = false,
    /** What this phone has open over the round (docs/architecture.md, «Состояние экрана»). */
    val dialog: GameDialog? = null,
    val panel: GamePanel? = null,
    /** The perk on the perks panel waiting for which hider it is aimed at. */
    val perkTargeting: PerkKind? = null,
    /** The decoy being placed on the map; null: not placing one. */
    val decoy: DecoyUi? = null,
    /** The claim whose hider's code the seeker's camera looks for, while it waits for the code. */
    val scannedClaim: ClaimUi? = null,
    /** «Found!»: the camera with no claim yet, while the seeker can still find somebody. */
    val isFreeScanOpen: Boolean = false,
    /** The camera at a checkpoint's code, while there is one left to scan. */
    val isCheckpointScanOpen: Boolean = false,
    /** «My code»: the hider shows the code without a claim. */
    val isMyCodeOpen: Boolean = false,
)

/** The round on pause; [bySos]: an SOS stopped it (else the host). */
data class PauseUi(val bySos: Boolean)

/** A player who calls for help: where they are ([point], null: unknown), [metersAway] from this phone. */
data class SosUi(
    val playerId: PlayerId,
    val name: String,
    val isMe: Boolean,
    val point: GeoPoint?,
    val metersAway: Double?,
)

/** A hint a perk bought ([HintKind]): a compass sector, a distance band, or both, for [millisLeft] more. */
data class HintUi(val kind: HintKind, val sector: Int?, val band: DistanceBand?, val millisLeft: Long)

/** An item of the board on the map (docs/adr/0013-quests-sparks-and-sensors.md). */
data class MapItem(
    val id: ItemId,
    val kind: ItemKind,
    val name: String,
    val point: GeoPoint,
    /** Nothing left in it for the viewer: a pickup somebody took, a checkpoint or a quest point the viewer reached. */
    val isTaken: Boolean,
    val perk: PerkKind? = null,
)

fun BoardItem.toMapItem(viewer: PlayerId?): MapItem = MapItem(
    id = id,
    kind = kind,
    name = name,
    point = point,
    isTaken = if (kind == ItemKind.PICKUP) takenBy.isNotEmpty() else viewer != null && viewer in takenBy,
    perk = perk,
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
    /** A spot the last glow left ([VisibilityReason.GLOW] between glows): how old it is. Null: the player right now. */
    val markAgeMillis: Long? = null,
    /** The player calls for help (docs/adr/0019-pause-and-sos.md): shown to everybody, wherever they are. */
    val isSos: Boolean = false,
)

/** The glow at the moment: on ([isGlowing]) until [millisLeft] from now, or the next one in [millisLeft]. */
data class GlowUi(val isGlowing: Boolean, val millisLeft: Long)
