package app.hovanki.client.session

import app.hovanki.client.account.AccountCredentials
import app.hovanki.client.device.DeviceInfo
import app.hovanki.client.diagnostics.Diagnostics
import app.hovanki.client.location.LocationProvider
import app.hovanki.client.network.ApiException
import app.hovanki.client.network.ApiResult
import app.hovanki.client.network.ConnectionEvent
import app.hovanki.client.network.GameApi
import app.hovanki.client.network.GameConnection
import app.hovanki.client.network.LocationOutbox
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.SyncExtras
import app.hovanki.client.network.apiResult
import app.hovanki.client.network.defaultSyncIntervalMillis
import app.hovanki.client.radio.NoopPrecisionRadio
import app.hovanki.client.radio.NoopProximityRadio
import app.hovanki.client.radio.PeerRange
import app.hovanki.client.radio.PrecisionRadio
import app.hovanki.client.radio.ProximityRadio
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.storage.SavedSession
import app.hovanki.client.tracking.ActivityMonitor
import app.hovanki.client.tracking.AlertKind
import app.hovanki.client.tracking.AlertRepeats
import app.hovanki.client.tracking.BackgroundTracker
import app.hovanki.client.tracking.CarryMonitor
import app.hovanki.client.tracking.NoopActivityMonitor
import app.hovanki.client.tracking.NoopCarryMonitor
import app.hovanki.client.tracking.NoopPocketPulse
import app.hovanki.client.tracking.PocketPulse
import app.hovanki.client.tracking.hiderAlerts
import app.hovanki.shared.protocol.Activity
import app.hovanki.shared.protocol.Audience
import app.hovanki.shared.protocol.BigGameId
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.Carry
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.CustomQuestRequest
import app.hovanki.shared.protocol.DeviceReport
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.InviteRequest
import app.hovanki.shared.protocol.ItemId
import app.hovanki.shared.protocol.JoinBigGameRequest
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.NearbySighting
import app.hovanki.shared.protocol.PerkKind
import app.hovanki.shared.protocol.PlaceItemRequest
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.QuestId
import app.hovanki.shared.protocol.QuestReviewRequest
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.RolesRequest
import app.hovanki.shared.protocol.SendChatRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.SettingsPreviewRequest
import app.hovanki.shared.protocol.SettingsPreviewResponse
import app.hovanki.shared.protocol.SettingsRequest
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.StreetZoneState
import app.hovanki.shared.protocol.UsePerkRequest
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UwbPeer
import app.hovanki.shared.rules.CheckpointPayload
import app.hovanki.shared.rules.GameSetup
import app.hovanki.shared.rules.OpenBuildings
import app.hovanki.shared.rules.ProximityRules
import app.hovanki.shared.rules.QuestCatalog
import app.hovanki.shared.rules.RadarSmoother
import app.hovanki.shared.rules.RadarToken
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
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * The one place that owns the current game: session credentials, the latest snapshot, the connection to the server
 * and our own location updates. App-scoped (outlives screens), so a round keeps running while the UI changes.
 *
 * The session is also saved to [storage], so a killed app comes back into its game: [resumeSavedGame] at app start.
 * A logged-in player ([account]) creates and joins games with the account token: the game knows them by their
 * nickname, and joining again (a new phone) gives back the same player. The chat of the game is merged from every
 * snapshot into [SessionState.chat].
 *
 * The radar (docs/adr/0012-nearby-radar.md) runs through [radio] while a round with it goes on: the phone advertises
 * its token and reports whom it heard with every sync, along with what it says about itself ([DeviceReport]). The
 * pulse ([pulse], «Пульс») is felt on the phone itself: a hider's phone knows the seekers' tokens
 * ([MyState.seekerTokens]), a seeker's the hiders' ([MyState.hiderTokens]), and smooths what it hears of them with
 * the same rules as the server, so it beats the moment the other team comes near, network or not; the server's band
 * counts too, whichever is warmer. While somebody is near, the phone syncs every second, so the server's bands come
 * fast too ([syncIntervalMillis]).
 * The board and the perks (docs/adr/0013-quests-sparks-and-sensors.md) are commands like the others.
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
    private val serverUrl: ServerUrl,
    private val storage: ClientStorage,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val account: AccountCredentials = AccountCredentials.None,
    private val radio: ProximityRadio = NoopProximityRadio(),
    private val precisionRadio: PrecisionRadio = NoopPrecisionRadio(),
    private val deviceInfo: DeviceInfo = DeviceInfo.Unknown,
    private val activityMonitor: ActivityMonitor = NoopActivityMonitor(),
    private val pocketPulse: PocketPulse = NoopPocketPulse,
    private val carryMonitor: CarryMonitor = NoopCarryMonitor(),
    private val diagnostics: Diagnostics = Diagnostics.Off,
) {
    private val mutableState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private val mutableMyLocation = MutableStateFlow<LocationSample?>(null)

    /** Latest own fix in server time. Snapshots never contain the viewer's own position. */
    val myLocation: StateFlow<LocationSample?> = mutableMyLocation.asStateFlow()

    /** Whether the phone's Bluetooth can take part in the radar right now. */
    val bluetooth: StateFlow<BluetoothState> get() = radio.state

    private val mutableRadarEnabled = MutableStateFlow(storage.radarEnabled)

    /** «The radar on my phone»: the player may switch their own phone out of it ([setRadarEnabled]). */
    val radarEnabled: StateFlow<Boolean> = mutableRadarEnabled.asStateFlow()

    private val mutableRanges = MutableStateFlow<List<PeerRange>>(emptyList())

    /** Metres by UWB to the players the server paired this phone with, while both look at their phones. */
    val ranges: StateFlow<List<PeerRange>> = mutableRanges.asStateFlow()

    /** The radar token this phone advertises, from the secret and the server clock; renewed with every snapshot. */
    private val radarToken = MutableStateFlow<String?>(null)

    /** Whom this phone heard since the last sync, in server time; the sync takes them. */
    private val heard = MutableStateFlow<List<NearbySighting>>(emptyList())
    private val uwbPeers = MutableStateFlow<List<UwbPeer>>(emptyList())
    private var radioJob: Job? = null
    private var activityJob: Job? = null
    private var rangingJob: Job? = null
    private var carryJob: Job? = null
    private var onScreen = true
    private var latestActivity = Activity.UNKNOWN
    private var latestCarry = Carry.UNKNOWN

    /**
     * The other team's tokens the server gave this player (a hider with the sense: the seekers'; a seeker: the
     * hiders'), and what the phone hears of each, smoothed like the server.
     */
    private var rivalTokens: Set<String> = emptySet()
    private val rivalSmoothers = HashMap<String, RadarSmoother>()
    private val mutablePulse = MutableStateFlow(RadarBand.NONE)

    /** Until when (server time) the phone counts somebody as near by its own ears: it syncs faster till then. */
    private val nearUntilMillis = MutableStateFlow(0L)

    /**
     * The pulse's band: a hider's, the nearest seeker; a seeker's sonar, the nearest hider — what this phone hears of
     * them, or the server's band, whichever is warmer. [RadarBand.NONE] outside the search.
     */
    val pulse: StateFlow<RadarBand> = mutablePulse.asStateFlow()

    // One outbox per session: samples of a finished game must never be uploaded to the next one.
    private var outbox = LocationOutbox()
    private var connectionJob: Job? = null
    private var locationJob: Job? = null
    private var isTracking = false

    /** When the hider's alerts vibrate again in the background. */
    private val alertRepeats = AlertRepeats()
    private var resumeAttempted = false
    private var buildingsJob: Job? = null
    private var streetZoneJob: Job? = null
    private var tracksJob: Job? = null

    /**
     * The join that got no answer (no network, or the answer got lost): pressed again with the same code and name, it
     * goes out with the same [JoinGameRequest.requestId], and the server gives back the player it may have created.
     */
    private var unansweredJoin: JoinGameRequest? = null

    /** The same for a big game's lobby ([joinBigGame]). */
    private var unansweredBigGameJoin: Pair<BigGameId, JoinBigGameRequest>? = null

    /** The chat message that got no answer; sent again, it keeps its [SendChatRequest.clientMessageId]. */
    private var unansweredChat: SendChatRequest? = null

    /** New game with the default settings: a shrinking zone around [center] (the host's position). */
    suspend fun create(playerName: String, center: GeoPoint): Boolean = create(playerName, defaultSettings(center))

    /**
     * [playerName] is only used for guests: a logged-in player plays under their nickname. An account plays in one
     * game at a time: the server takes it out of its other lobbies, and out of a round in progress only with
     * [leaveOtherGame] (else [app.hovanki.shared.protocol.ErrorReason.IN_ANOTHER_GAME]).
     */
    suspend fun create(playerName: String, settings: GameSettings, leaveOtherGame: Boolean = false): Boolean {
        val token = account.accountToken
        val request = CreateGameRequest(playerName.trim(), settings, leaveOtherGame)
        return command(token) { begin(api.createGame(request, token)) }
    }

    /** [playerName] and [leaveOtherGame] as in [create]. Also how an invite is accepted: its join code, logged in. */
    suspend fun join(code: String, playerName: String, leaveOtherGame: Boolean = false): Boolean {
        val token = account.accountToken
        val typed = JoinGameRequest(code.trim().uppercase(), playerName.trim(), leaveOtherGame = leaveOtherGame)
        val request = unansweredJoin?.takeIf { it.copy(requestId = null) == typed }
            ?: typed.copy(requestId = newRequestId())
        val joined = command(token) { begin(api.joinGame(request, token)) }
        unansweredJoin = request.takeIf { !joined && mutableState.value.lastError is SessionError.Network }
        return joined
    }

    /**
     * Into the open lobby of big game [id] (docs/adr/0010-big-games.md), or back to the player's round in it: only with
     * an account that signed up. [leaveOtherGame] as in [create]. Pressed again after a lost answer, the same request id
     * goes out, as with [join].
     */
    suspend fun joinBigGame(id: BigGameId, leaveOtherGame: Boolean = false): Boolean {
        val token = account.accountToken
            ?: return fail(SessionError.Rejected(ErrorCode.FORBIDDEN, "Log in first", ErrorReason.ACCOUNT_REQUIRED))
        val typed = JoinBigGameRequest(leaveOtherGame = leaveOtherGame)
        val request = unansweredBigGameJoin?.takeIf { it.first == id && it.second.copy(requestId = null) == typed }
            ?.second ?: typed.copy(requestId = newRequestId())
        val joined = command(token) { begin(api.joinBigGame(id, request, token)) }
        unansweredBigGameJoin =
            (id to request).takeIf { !joined && mutableState.value.lastError is SessionError.Network }
        return joined
    }

    suspend fun start(seekers: List<PlayerId>): Boolean = sessionCommand {
        api.startGame(it, StartGameRequest(seekers))
    }

    /** The host picks the seekers in the lobby; everybody sees them. */
    suspend fun setSeekers(seekers: Collection<PlayerId>): Boolean = sessionCommand {
        api.setRoles(it, RolesRequest(seekers = seekers.toList()))
    }

    /** The host has the server draw [count] seekers at random; every phone rolls the dice. */
    suspend fun drawSeekers(count: Int): Boolean = sessionCommand {
        api.setRoles(it, RolesRequest(randomSeekers = count))
    }

    /**
     * The host changes the setup in the lobby; a new zone loads its buildings and zone by streets again. [setup]: the
     * choices [settings] were made of, remembered for the host's next game once the server took them.
     */
    suspend fun updateSettings(settings: GameSettings, setup: GameSetup? = null): Boolean {
        val updated = sessionCommand { api.updateSettings(it, SettingsRequest(settings)) }
        if (updated && setup != null) storage.saveGameSetup(setup)
        return updated
    }

    /**
     * What the host's [draft] makes before it is saved: its zone by streets
     * (docs/adr/0014-settings-lobby-redesign-open-buildings.md, section 2.3; [DraftZonePreview] asks). A failure is no
     * session error: the map just keeps the circles.
     */
    suspend fun previewSettings(draft: GameSettings): ApiResult<SettingsPreviewResponse> {
        val session = mutableState.value.session ?: return ApiResult.Network("Not in a game")
        return apiResult { api.previewSettings(session, SettingsPreviewRequest(draft)) }
    }

    /**
     * The host taps [tap] on the map of the zone's buildings (docs/adr/0014-settings-lobby-redesign-open-buildings.md,
     * section 4): a forbidden building opens for hiding, an open one closes again. Sent right away, like the board;
     * every phone splits the buildings it has by the new points, nothing is loaded again. False when the tap hit no
     * building, the limit is reached or the server said no.
     */
    suspend fun toggleOpenBuilding(tap: GeoPoint): Boolean {
        val state = mutableState.value
        val snapshot = state.snapshot ?: return false
        val buildings = state.buildings?.takeIf { it.mapRevision == snapshot.mapRevision } ?: return false
        val current = snapshot.settings.openBuildings.orEmpty()
        val next = OpenBuildings.toggle(current, buildings.buildings + buildings.open, tap)
        if (next == current) return false
        return updateSettings(snapshot.settings.copy(openBuildings = next))
    }

    /**
     * The host plays anyway in a zone that fits fewer players than there are, or has few places to hide
     * (docs/adr/0010-big-games.md): the lobby warns no more in this game.
     */
    suspend fun acceptCrowding(): Boolean = sessionCommand { api.acceptCrowding(it) }

    /**
     * What the host's next game starts with: the setup chosen last time on this phone, or the defaults. Never open to
     * spectators: everybody's position is at stake, the host opens each game on purpose (the delay they chose stays).
     */
    fun lastGameSetup(): GameSetup = storage.loadGameSetup()?.coerced()?.copy(openGame = false) ?: GameSetup()

    suspend fun claimCatch(hiderId: PlayerId): Boolean = sessionCommand { api.claimCatch(it, hiderId) }

    /**
     * One scan: the seeker's camera read [hiderId]'s QR code with [code] before any claim; the server opens the claim
     * and checks the code in one step. A server without one scan only opens the claim: the code follows right after.
     */
    suspend fun catchByScan(hiderId: PlayerId, code: String): Boolean {
        val session = mutableState.value.session ?: return false
        return command {
            val snapshot = api.claimCatch(session, hiderId, code.trim())
            applySnapshot(snapshot)
            val open = snapshot.catches.lastOrNull {
                it.seekerId == session.playerId && it.hiderId == hiderId && it.status == CatchStatus.AWAITING_CODE
            }
            if (open != null) applySnapshot(api.confirmCatch(session, open.id, code.trim()))
        }
    }

    suspend fun confirmCatch(catchId: CatchId, code: String): Boolean =
        sessionCommand { api.confirmCatch(it, catchId, code.trim()) }

    suspend fun dispute(catchId: CatchId): Boolean = sessionCommand { api.disputeCatch(it, catchId) }

    suspend fun vote(catchId: CatchId, confirm: Boolean): Boolean = sessionCommand { api.vote(it, catchId, confirm) }

    /** To everyone, or to the player's team only ([team], not in the lobby); the response brings it into the chat. */
    suspend fun sendChat(text: String, team: Boolean = false): Boolean {
        // Sent again after no answer: the same id, so the server keeps the message once.
        val message = unansweredChat?.takeIf { it.text == text && it.team == team }
            ?: SendChatRequest(text, team, clientMessageId = newRequestId())
        val sent = sessionCommand { api.sendChat(it, message.copy(chatAfter = chatCursor())) }
        unansweredChat = message.takeIf { !sent && mutableState.value.lastError is SessionError.Network }
        return sent
    }

    /** Reports another player's chat message [seq] to the moderators. */
    suspend fun reportChat(seq: Long): Boolean = sessionCommand { api.reportChat(it, seq) }

    /** Invites friends ([userIds]) and/or everyone in [groupId] into this game: lobby, logged-in players only. */
    suspend fun invite(userIds: List<UserId> = emptyList(), groupId: GroupId? = null): Boolean =
        sessionCommand { api.invite(it, InviteRequest(userIds, groupId)) }

    // The board and the perks (docs/adr/0013-quests-sparks-and-sensors.md).

    /** The host places an item on the map in the lobby: a quest point, a checkpoint or a perk lying around. */
    suspend fun placeItem(request: PlaceItemRequest): Boolean = sessionCommand { api.placeItem(it, request) }

    suspend fun removeItem(itemId: ItemId): Boolean = sessionCommand { api.removeItem(it, itemId) }

    /** The camera read a checkpoint's QR code ([text], `CheckpointPayload`); one of another game is refused here. */
    suspend fun scanCheckpoint(text: String): Boolean {
        val session = mutableState.value.session ?: return false
        val payload = CheckpointPayload.decode(text)
        if (payload == null || payload.gameId != session.gameId) {
            return fail(SessionError.Rejected(ErrorCode.NOT_FOUND, "Not a checkpoint of this game"))
        }
        return command { applySnapshot(api.scanCheckpoint(session, payload.code)) }
    }

    /** Uses a perk: [targetId] for the ones aimed at a hider, [point] for a decoy. */
    suspend fun usePerk(perk: PerkKind, targetId: PlayerId? = null, point: GeoPoint? = null): Boolean =
        sessionCommand { api.usePerk(it, UsePerkRequest(perk, targetId, point)) }

    /** The host makes up a quest in words for [audience], worth [sparks]. */
    suspend fun addQuest(
        text: String,
        audience: Audience = Audience.ALL,
        sparks: Int = QuestCatalog.CUSTOM_DEFAULT_SPARKS,
    ): Boolean = sessionCommand { api.addQuest(it, CustomQuestRequest(text, audience, sparks)) }

    /** «Done»: the player says they did the host's quest [questId]; the host answers. */
    suspend fun questDone(questId: QuestId): Boolean = sessionCommand { api.questDone(it, questId) }

    /** The host confirms or refuses what [playerId] said about quest [questId]. */
    suspend fun reviewQuest(questId: QuestId, playerId: PlayerId, approved: Boolean): Boolean =
        sessionCommand { api.reviewQuest(it, questId, QuestReviewRequest(playerId, approved)) }

    // The radar (docs/adr/0012-nearby-radar.md).

    /** «The radar on my phone»: off, this phone neither advertises nor scans, and the server hears it is off. */
    fun setRadarEnabled(enabled: Boolean) {
        mutableRadarEnabled.value = enabled
        storage.saveRadarEnabled(enabled)
        mutableState.value.snapshot?.let(::updateRadio)
    }

    /** The app came to the screen or left it: the precision radar works only while both players look. */
    fun onScreenChanged(onScreen: Boolean) {
        this.onScreen = onScreen
        mutableState.value.snapshot?.let(::updateRanging)
    }

    /** The player has seen the chat as it is now: nothing in it counts as unread any more. */
    fun markChatRead() {
        mutableState.update { state ->
            state.copy(chatReadSeq = maxOf(state.chatReadSeq, state.chat.lastOrNull()?.seq ?: 0L))
        }
    }

    /**
     * Comes back into the game saved by an earlier run of the app, if there is one. Call once when the app starts;
     * later calls do nothing. The session is set right away ([SessionState.isResuming], no snapshot yet: a loading
     * screen, not the start screen) and checked with the regular `sync`:
     * - the game is running: phase screen, polling, location and background tracking as before the restart;
     * - the game is over, deleted on the server or the token is refused: the saved session is dropped and the start
     *   screen shows [SessionError.SavedGameFinished] / [SessionError.SavedGameGone];
     * - no connection: keeps retrying like a running game (the player may leave).
     *
     * A game saved for another server (debug builds can switch) is dropped.
     */
    fun resumeSavedGame() {
        if (resumeAttempted) return
        resumeAttempted = true
        if (mutableState.value.session != null) return
        val saved = storage.loadSession() ?: return
        if (ServerUrl.normalize(saved.serverUrl) != serverUrl.value) {
            storage.clearSession()
            return
        }
        startSession(saved.session, snapshot = null)
    }

    /** Drops a saved game without resuming it; for UI automation that must start from the start screen. */
    fun forgetSavedGame() {
        if (mutableState.value.session == null) storage.clearSession()
    }

    /**
     * Leaves the game: on the phone right away, and the server is told in the background (a lobby shows the player
     * gone, a round goes on without them: a hider is out). Without a connection only the phone forgets the game; the
     * server then reveals the silent player like a lost signal. Also how the results screen is closed: polling (for the
     * chat) goes on until then.
     */
    fun leave() {
        val current = mutableState.value
        unansweredChat = null
        stopBackgroundWork()
        storage.clearSession()
        mutableState.value = SessionState()
        val session = current.session ?: return
        if (current.snapshot?.phase == GamePhase.FINISHED) return
        scope.launch {
            try {
                api.leave(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing to do: the phone has left, the server will see the silence.
            }
        }
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
        storage.saveSession(SavedSession(serverUrl.value, response.session))
        startSession(response.session, response.snapshot)
    }

    /** Runs [session]: its first [snapshot] comes from create/join, or from the first poll when resuming. */
    private fun startSession(session: PlayerSession, snapshot: GameSnapshot?) {
        stopBackgroundWork()
        mutableState.value = SessionState(session = session, isResuming = snapshot == null)
        if (snapshot != null) applySnapshot(snapshot)
        val sessionOutbox = LocationOutbox()
        outbox = sessionOutbox
        connectionJob = scope.launch {
            // Off the main thread: JSON of every poll is parsed in the flow.
            connection.connect(
                session,
                sessionOutbox,
                chatAfter = ::chatCursor,
                extras = ::syncExtras,
                intervalMillis = ::syncIntervalMillis,
            )
                .flowOn(Dispatchers.Default)
                .collect { onConnectionEvent(it) }
        }
        startLocationUpdates()
    }

    /** What else goes with the next sync: whom the phone heard since the last one, and what it says about itself. */
    private fun syncExtras(): SyncExtras = SyncExtras(heard.getAndUpdate { emptyList() }, deviceReport())
        .also { diagnostics.onSyncSent(it.nearby.size, it.device) }

    private fun deviceReport(): DeviceReport {
        val features = mutableState.value.snapshot?.settings?.features
        val uwb = deviceInfo.hasUwb && precisionRadio.isSupported
        val bluetooth = radio.state.value.let {
            if (it == BluetoothState.ON && !mutableRadarEnabled.value) BluetoothState.OFF_BY_PLAYER else it
        }
        return DeviceReport(
            platform = deviceInfo.platform,
            bluetooth = bluetooth,
            uwb = uwb,
            onScreen = onScreen,
            uwbToken = precisionRadio.token.value.takeIf { uwb && onScreen && features?.precisionRadar == true },
            activity = if (features?.activity == true) latestActivity else Activity.UNKNOWN,
            activitySensor = deviceInfo.hasActivitySensor,
            carry = carry(),
            // The model only where the radar is, for its readings by model; nothing else asks for it.
            model = deviceInfo.model?.takeIf { features?.hasRadar == true },
        )
    }

    /** Where the phone is: in the hand while the app is on the screen, whatever the sensors say. */
    private fun carry(): Carry = if (onScreen) Carry.IN_HAND else latestCarry

    private fun onConnectionEvent(event: ConnectionEvent) {
        val resuming = mutableState.value.isResuming
        when (event) {
            is ConnectionEvent.Snapshot -> {
                diagnostics.onSynced(event.snapshot.serverTimeMillis, event.transport)
                if (resuming && event.snapshot.phase == GamePhase.FINISHED) {
                    endSession(SessionError.SavedGameFinished)
                    return
                }
                mutableState.update {
                    it.copy(connectionStatus = ConnectionStatus.ONLINE, isResuming = false, transport = event.transport)
                }
                applySnapshot(event.snapshot)
                // Location updates need the game's settings: a resumed session starts them with its first snapshot.
                if (resuming) startLocationUpdates()
            }

            is ConnectionEvent.Problem -> {
                diagnostics.onSyncFailed(event.error, event.retryInMillis)
                mutableState.update { it.copy(connectionStatus = ConnectionStatus.RECONNECTING) }
            }

            is ConnectionEvent.Ended -> when {
                resuming -> endSession(SessionError.SavedGameGone)

                // The results stay until the player leaves; the server has deleted the game, the chat is over.
                mutableState.value.snapshot?.phase == GamePhase.FINISHED -> connectionJob = null

                else -> endSession(SessionError.SessionLost)
            }
        }
    }

    /** The server no longer has this game for us: back to the start screen with [error]. */
    private fun endSession(error: SessionError) {
        stopBackgroundWork()
        storage.clearSession()
        mutableState.value = SessionState(lastError = error)
    }

    private fun applySnapshot(snapshot: GameSnapshot) {
        val current = mutableState.value
        // A late response from a previous game must not leak into the current one.
        if (current.session?.gameId != snapshot.gameId) return
        // Messages are merged by seq, so even an overtaken response may add some.
        if (snapshot.chat.isNotEmpty()) mutableState.update { it.copy(chat = mergeChat(it.chat, snapshot.chat)) }
        // A slow poll can be overtaken by a command's response: keep the newer state.
        val previous = current.snapshot
        if (previous != null && snapshot.serverTimeMillis < previous.serverTimeMillis) return

        clock.onServerTime(snapshot.serverTimeMillis)
        if (previous?.phase != snapshot.phase) diagnostics.note("phase ${snapshot.phase}, ${snapshot.me.role}")
        val serverBand = snapshot.radarBand()
        if (serverBand !=
            previous?.radarBand()
        ) {
            diagnostics.note("server's radar ${previous?.radarBand()} → $serverBand")
        }
        mutableState.update { state ->
            // The host changed the zone: the map data of the old one no longer applies.
            state.copy(
                snapshot = snapshot,
                buildings = state.buildings?.takeIf { it.mapRevision == snapshot.mapRevision },
                streetZone = state.streetZone?.takeIf { it.mapRevision == snapshot.mapRevision },
            )
        }
        if (snapshot.buildings == BuildingsState.READY) loadBuildings()
        if (snapshot.streetZone == StreetZoneState.READY) loadStreetZone()
        // A game with the radar: the phone looks at its Bluetooth from the lobby on, so every sync says whether it can
        // take part (the host can't start a game that requires it otherwise). An iPhone knows nothing of its Bluetooth
        // until the app touches CoreBluetooth, which also asks the player; a game without the radar never asks.
        if (snapshot.phase != GamePhase.FINISHED && snapshot.settings.features.hasRadar) radio.refresh()
        when (snapshot.phase) {
            GamePhase.LOBBY -> Unit

            GamePhase.HIDING, GamePhase.SEEKING -> {
                startTracking()
                updateRadio(snapshot)
                updateCarryMonitor(snapshot)
                updateActivityMonitor(snapshot)
                updateRanging(snapshot)
                rivalTokens = when (snapshot.me.role) {
                    Role.HIDER -> snapshot.me.seekerTokens
                    Role.SEEKER -> snapshot.me.hiderTokens
                }.toSet()
                rivalSmoothers.keys.retainAll(rivalTokens)
                refreshPulse()
                val alerts = alertRepeats.update(snapshot.hiderAlerts(), snapshot.serverTimeMillis)
                alerts.ended.forEach(backgroundTracker::endAlert)
                // «A seeker is near» is the pulse's job, at the band's pace, not a notification every few seconds.
                alerts.buzz.filterNot { it.kind == AlertKind.SEEKER_NEAR }.forEach(backgroundTracker::alert)
            }

            // Results are final: no more location or foreground service, and nothing to resume. Polling goes on for
            // the chat on the results screen, until the player leaves.
            GamePhase.FINISHED -> {
                if (previous?.phase != GamePhase.FINISHED) {
                    stopLocationWork()
                    storage.clearSession()
                }
                loadTracks()
            }
        }
    }

    /** The chat cursor: the newest message seq this game's chat has, 0 for none. */
    private fun chatCursor(): Long = mutableState.value.chat.lastOrNull()?.seq ?: 0L

    /**
     * The radar: advertising and scanning while a round with it goes on, this player plays and keeps their phone in
     * it. The token follows the server clock; what is heard waits for the next sync ([syncExtras]).
     */
    private fun updateRadio(snapshot: GameSnapshot) {
        val secret = snapshot.me.radarSecret
        val inRound = snapshot.phase == GamePhase.HIDING || snapshot.phase == GamePhase.SEEKING
        val playing = snapshot.me.role == Role.SEEKER || snapshot.me.status == PlayerStatus.ACTIVE
        val wanted = secret != null && inRound && snapshot.settings.features.hasRadar && playing &&
            mutableRadarEnabled.value
        if (!wanted || secret == null) {
            stopRadio()
            return
        }
        radarToken.value = RadarToken.at(secret, clock.now())
        diagnostics.onRadio(radarToken.value, asSeeker = snapshot.me.role == Role.SEEKER)
        if (radioJob?.isActive == true) return
        radioJob = scope.launch {
            try {
                radio.run(radarToken, asSeeker = snapshot.me.role == Role.SEEKER).collect { sighting ->
                    val isRival = sighting.token in rivalTokens
                    diagnostics.onSighting(sighting.token, sighting.rssi, sighting.atMillis, isRival, sighting.via)
                    val atMillis = clock.toServerTime(sighting.atMillis)
                    val sample = NearbySighting(sighting.token, sighting.rssi, atMillis)
                    heard.update { kept -> keepRecent(kept + sample) }
                    // Heard from a pocket, the signal is weaker than the distance says: evened out like the server.
                    val offset = if (carry() == Carry.IN_POCKET) ProximityRules.POCKET_OFFSET_DB else 0.0
                    val level = (sighting.rssi + offset).roundToInt()
                    // Any phone of the game this close (a teammate too, whose token this one doesn't know): sync fast.
                    if (level >= ProximityRules.WARM_ENTER_DBM) nearUntilMillis.value = atMillis + NEAR_HOLD_MILLIS
                    if (isRival) {
                        rivalSmoothers.getOrPut(sighting.token) { RadarSmoother() }.add(level, atMillis)
                        refreshPulse()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Bluetooth failed underneath: the round goes on without the radar on this phone.
                diagnostics.note("radio failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    /**
     * Readings spread over time are what the server's smoothing (and the claim's dwell) needs, not a burst of the
     * same second: per phone heard, one reading every [READING_GAP_MILLIS] or so (the latest of a burst), the last
     * [READINGS_PER_TOKEN] of them; the sync takes at most [MAX_SIGHTINGS_PER_SYNC] in all.
     */
    private fun keepRecent(sightings: List<NearbySighting>): List<NearbySighting> = sightings
        .groupBy { it.token }.values
        .flatMap { readings ->
            val kept = ArrayList<NearbySighting>()
            for (reading in readings) {
                val last = kept.lastOrNull()
                if (last == null || reading.atMillis - last.atMillis >= READING_GAP_MILLIS) {
                    kept += reading
                } else {
                    kept[kept.lastIndex] = reading
                }
            }
            kept.takeLast(READINGS_PER_TOKEN)
        }
        .takeLast(MAX_SIGHTINGS_PER_SYNC)

    private fun stopRadio() {
        if (radioJob != null) diagnostics.onRadio(null, asSeeker = false)
        radioJob?.cancel()
        radioJob = null
        radarToken.value = null
        heard.value = emptyList()
        rivalTokens = emptySet()
        rivalSmoothers.clear()
        nearUntilMillis.value = 0L
        refreshPulse()
    }

    /** The pulse's band right now ([pulse]); the phone is told when it changes. */
    private fun refreshPulse() {
        val band = pulseBand(mutableState.value.snapshot)
        if (mutablePulse.value == band) return
        diagnostics.note("pulse ${mutablePulse.value} → $band")
        mutablePulse.value = band
        pocketPulse.set(band)
    }

    private fun pulseBand(snapshot: GameSnapshot?): RadarBand {
        if (snapshot == null || snapshot.phase != GamePhase.SEEKING || radioJob?.isActive != true) return RadarBand.NONE
        val server = snapshot.radarBand() ?: RadarBand.NONE
        val now = clock.now()
        val heard = rivalSmoothers.values.maxOfOrNull { it.bandAt(now) } ?: RadarBand.NONE
        return when (snapshot.me.role) {
            Role.SEEKER -> maxOf(server, heard)

            Role.HIDER -> {
                if (!snapshot.settings.features.hiderSense || snapshot.me.status != PlayerStatus.ACTIVE) {
                    RadarBand.NONE
                } else {
                    maxOf(server, heard)
                }
            }
        }
    }

    /**
     * The pause before the next sync: the game's interval, or [NEAR_SYNC_MILLIS] during the search while somebody is
     * near — by the server's band or by this phone's own ears — so both phones of a pair report sooner and the
     * server's band comes back sooner. Asked by the connection, off the main thread.
     */
    private fun syncIntervalMillis(snapshot: GameSnapshot): Long {
        val base = defaultSyncIntervalMillis(snapshot)
        if (snapshot.phase != GamePhase.SEEKING) return base
        val near = nearUntilMillis.value > clock.now() ||
            (snapshot.radarBand() ?: RadarBand.NONE) >= RadarBand.WARM ||
            mutablePulse.value >= RadarBand.WARM
        return if (near) minOf(base, NEAR_SYNC_MILLIS) else base
    }

    /** Where the phone is, while a round with the radar goes on: for the radar's evening out and the pocket stealth. */
    private fun updateCarryMonitor(snapshot: GameSnapshot) {
        if (!snapshot.settings.features.hasRadar) {
            stopCarryMonitor()
            return
        }
        if (carryJob?.isActive == true) return
        carryJob = scope.launch {
            try {
                carryMonitor.carry().collect { latestCarry = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No sensors after all: unknown, as before.
            }
        }
    }

    private fun stopCarryMonitor() {
        carryJob?.cancel()
        carryJob = null
        latestCarry = Carry.UNKNOWN
    }

    /** The phone's motion sensors, when the game asks whether the player runs. */
    private fun updateActivityMonitor(snapshot: GameSnapshot) {
        val wanted = snapshot.settings.features.activity && deviceInfo.hasActivitySensor
        if (!wanted) {
            stopActivityMonitor()
            return
        }
        if (activityJob?.isActive == true) return
        activityJob = scope.launch {
            try {
                activityMonitor.activity().collect { latestActivity = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No sensors after all: unknown, as before.
            }
        }
    }

    private fun stopActivityMonitor() {
        activityJob?.cancel()
        activityJob = null
        latestActivity = Activity.UNKNOWN
    }

    /** The precision radar with the peers the server paired this phone with, while the app is on the screen. */
    private fun updateRanging(snapshot: GameSnapshot) {
        uwbPeers.value = snapshot.me.uwbPeers
        val wanted = snapshot.settings.features.precisionRadar && precisionRadio.isSupported && onScreen &&
            snapshot.phase == GamePhase.SEEKING
        if (!wanted) {
            stopRanging()
            return
        }
        if (rangingJob?.isActive == true) return
        rangingJob = scope.launch {
            try {
                precisionRadio.range(uwbPeers).collect { range ->
                    mutableRanges.update { ranges -> ranges.filter { it.playerId != range.playerId } + range }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // UWB failed underneath: the arrow just doesn't show.
            }
        }
    }

    private fun stopRanging() {
        rangingJob?.cancel()
        rangingJob = null
        mutableRanges.value = emptyList()
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
                    diagnostics.onFix(fix.accuracyMeters, fix.isMock, fix.timestampMillis)
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

    /** Once per map revision; a failed attempt is retried with a later snapshot. */
    private fun loadBuildings() {
        val current = mutableState.value
        val session = current.session ?: return
        if (current.buildings != null || buildingsJob?.isActive == true) return
        buildingsJob = scope.launch {
            val buildings = try {
                api.buildings(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            mutableState.update { state ->
                val revision = state.snapshot?.mapRevision
                if (state.session == session &&
                    buildings.mapRevision == revision
                ) {
                    state.copy(buildings = buildings)
                } else {
                    state
                }
            }
        }
    }

    /** The zone by streets, once per map revision; a failed attempt is retried with a later snapshot. */
    private fun loadStreetZone() {
        val current = mutableState.value
        val session = current.session ?: return
        if (current.streetZone != null || streetZoneJob?.isActive == true) return
        streetZoneJob = scope.launch {
            val zone = try {
                api.streetZone(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            mutableState.update { state ->
                val revision = state.snapshot?.mapRevision
                val usable =
                    zone.state == StreetZoneState.READY && zone.stages.isNotEmpty() && zone.mapRevision == revision
                if (state.session == session && usable) state.copy(streetZone = zone) else state
            }
        }
    }

    /** The replay on the results screen: once the game is over; a failed attempt is retried with a later snapshot. */
    private fun loadTracks() {
        val current = mutableState.value
        val session = current.session ?: return
        if (current.tracks != null || tracksJob?.isActive == true) return
        tracksJob = scope.launch {
            val tracks = try {
                api.tracks(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            mutableState.update { if (it.session == session) it.copy(tracks = tracks) else it }
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
        buildingsJob?.cancel()
        buildingsJob = null
        streetZoneJob?.cancel()
        streetZoneJob = null
        tracksJob?.cancel()
        tracksJob = null
        stopLocationWork()
    }

    private fun stopLocationWork() {
        locationJob?.cancel()
        locationJob = null
        outbox.clear()
        mutableMyLocation.value = null
        mutableState.update { it.copy(isSharingLocation = false) }
        alertRepeats.clear().forEach(backgroundTracker::endAlert)
        stopRadio()
        stopCarryMonitor()
        stopActivityMonitor()
        stopRanging()
        if (isTracking) {
            isTracking = false
            backgroundTracker.stop()
        }
    }

    private suspend fun sessionCommand(call: suspend (PlayerSession) -> GameSnapshot): Boolean {
        val session = mutableState.value.session ?: return false
        return command { applySnapshot(call(session)) }
    }

    /** [accountToken]: sent with the call; a 401 then means the account session is gone (the player is logged out). */
    private suspend fun command(accountToken: String? = null, block: suspend () -> Unit): Boolean = try {
        block()
        mutableState.update { it.copy(lastError = null) }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (accountToken != null && e.status == UNAUTHORIZED) account.onTokenRejected(accountToken)
        val message = e.error?.message ?: e.message.orEmpty()
        fail(SessionError.Rejected(e.error?.code, message, e.reason, e.retryAfterSeconds, e.error?.untilMillis))
    } catch (e: Exception) {
        fail(SessionError.Network(e.message))
    }

    private fun fail(error: SessionError): Boolean {
        mutableState.update { it.copy(lastError = error) }
        return false
    }

    companion object {
        /**
         * What the app creates unless the host sets it up otherwise: the default setup ([GameSetup]: a shrinking zone
         * around the host at [center], the glow on).
         */
        fun defaultSettings(center: GeoPoint): GameSettings = GameSetup().settings(center)

        private const val FIRST_FIX_INTERVAL_MILLIS = 1_000L
        private const val UNAUTHORIZED = 401
        private const val READINGS_PER_TOKEN = 8
        private const val READING_GAP_MILLIS = 400L

        /** The sync's pace while somebody is near ([syncIntervalMillis]). */
        const val NEAR_SYNC_MILLIS = 1_000L

        /** How long one close reading keeps the fast pace. */
        private const val NEAR_HOLD_MILLIS = 5_000L
        private const val MAX_SIGHTINGS_PER_SYNC = 200

        /** Good enough to center the zone on (the default zone is hundreds of meters wide). */
        private const val GOOD_FIX_ACCURACY_METERS = 50.0
    }
}
