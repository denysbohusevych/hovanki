package app.hovanki.client.ui.lobby

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.account.AccountManager
import app.hovanki.client.account.AccountState
import app.hovanki.client.session.ConnectionStatus
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.session.SessionError
import app.hovanki.client.session.SessionState
import app.hovanki.client.social.SocialManager
import app.hovanki.client.social.UserRelation
import app.hovanki.client.ui.common.CommandRunner
import app.hovanki.client.ui.common.FormMessage
import app.hovanki.client.ui.common.PlayerAccount
import app.hovanki.client.ui.common.playerAccount
import app.hovanki.client.ui.game.ZoneTimeline
import app.hovanki.client.ui.settings.ChangedPart
import app.hovanki.client.ui.settings.Explainer
import app.hovanki.client.ui.settings.SettingsChange
import app.hovanki.client.ui.settings.SettingsPreview
import app.hovanki.client.ui.settings.SettingsTab
import app.hovanki.client.ui.settings.changedParts
import app.hovanki.client.ui.settings.settingsChanges
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.geo.offsetFrom
import app.hovanki.shared.protocol.Audience
import app.hovanki.shared.protocol.BigGameInfo
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.BoardItem
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.Capabilities
import app.hovanki.shared.protocol.CapacityState
import app.hovanki.shared.protocol.FriendsResponse
import app.hovanki.shared.protocol.GameFeatures
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.GroupsResponse
import app.hovanki.shared.protocol.ItemId
import app.hovanki.shared.protocol.ItemKind
import app.hovanki.shared.protocol.PerkKind
import app.hovanki.shared.protocol.PlaceItemRequest
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.QuestKind
import app.hovanki.shared.protocol.QuestView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.StreetZoneState
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.protocol.ZoneShape
import app.hovanki.shared.rules.Capacity
import app.hovanki.shared.rules.GameSetup
import app.hovanki.shared.rules.Glow
import app.hovanki.shared.rules.SettingsLimits
import app.hovanki.shared.rules.StreetZone
import app.hovanki.shared.rules.contains
import app.hovanki.shared.rules.withOpenBuildings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/**
 * The lobby (docs/adr/0009-game-setup-glow-streets.md): the roles live on the server, so every phone shows who seeks;
 * the host picks them, draws them at random or changes the game's setup.
 */
class LobbyViewModel(
    private val sessionManager: GameSessionManager,
    private val social: SocialManager,
    private val account: AccountManager,
) : ViewModel() {
    /** The seekers the host just picked, shown until the server has answered: a tap shows at once. */
    private val pendingSeekers = MutableStateFlow<Set<PlayerId>?>(null)
    private val isStarting = MutableStateFlow(false)
    private val commands = CommandRunner(viewModelScope)

    /** Role changes and the start go to the server one after another, in the order the host tapped. */
    private val rolesLock = Mutex()

    /**
     * The game whose invite panel is open; null: closed. Per game, like [invitesSentIn]: this view model outlives
     * the lobby (the round starts, the next game's lobby).
     */
    var invitePanelIn by mutableStateOf<GameId?>(null)
        private set

    /** Friends and groups picked in the invite panel. */
    var pickedFriends by mutableStateOf<Set<UserId>>(emptySet())
        private set
    var pickedGroups by mutableStateOf<Set<GroupId>>(emptySet())
        private set
    var isSendingInvites by mutableStateOf(false)
        private set

    /** The game the last invitations went out for: its lobby says so until dismissed. */
    var invitesSentIn by mutableStateOf<GameId?>(null)
        private set

    /** The game whose settings panel the host has open; null: closed. */
    var settingsPanelIn by mutableStateOf<GameId?>(null)
        private set

    /** What the host is choosing in the settings panel; sent on «Save». */
    var setupDraft by mutableStateOf(GameSetup())
        private set
    var isSavingSettings by mutableStateOf(false)
        private set

    /** Where the host moved the zone on the settings' map; null: where it is. */
    var draftCenter by mutableStateOf<GeoPoint?>(null)
        private set
    var settingsTab by mutableStateOf(SettingsTab.ZONE)
        private set

    /** The «?» open over the settings; null: none. */
    var helpFor by mutableStateOf<Explainer?>(null)
        private set

    /** The extra shown above the «More» tab. */
    var focusedExtra by mutableStateOf(Explainer.OPEN_GAME)
        private set

    /** What plays on the settings' map; null: nothing, the draft stands still. */
    var preview by mutableStateOf<SettingsPreview?>(null)
        private set

    /** The host moves the zone's center: the map pans under a pin. */
    var isMovingCenter by mutableStateOf(false)
        private set

    /** «What changes», asked before a setup that touches the map is sent; null: not asked. */
    var pendingChanges by mutableStateOf<List<SettingsChange>?>(null)
        private set

    /**
     * Where each game was made, as far as this phone knows: the zone moves at most so far from it
     * ([SettingsLimits.MAX_CENTER_MOVE_METERS]). The first center seen; the server checks the real one.
     */
    private val zoneOrigins = mutableMapOf<GameId, GeoPoint>()

    /** The game whose map of buildings the host has open, to open some for hiding; null: closed. */
    var buildingsPanelIn by mutableStateOf<GameId?>(null)
        private set

    /** Where the host tapped on the map of buildings: the building there is the one they look at. */
    var buildingTap by mutableStateOf<GeoPoint?>(null)
        private set
    var isTogglingBuilding by mutableStateOf(false)
        private set

    /** The game whose zone map is open full screen in the lobby; null: closed. */
    var mapPanelIn by mutableStateOf<GameId?>(null)
        private set

    /** The game whose board panel the host has open; null: closed. */
    var boardPanelIn by mutableStateOf<GameId?>(null)
        private set

    /** What the host is about to place on the board: where they tapped, and what. */
    var boardPick by mutableStateOf<GeoPoint?>(null)
        private set
    var boardKind by mutableStateOf(ItemKind.QUEST_POINT)
        private set
    var boardAudience by mutableStateOf(Audience.ALL)
        private set
    var boardName by mutableStateOf("")
        private set
    var boardSparks by mutableStateOf<Int?>(null)
        private set
    var boardPerk by mutableStateOf(PerkKind.SENSE)
        private set
    var isPlacing by mutableStateOf(false)
        private set

    /** The host's own quest being written. */
    var questText by mutableStateOf("")
        private set
    var questAudience by mutableStateOf(Audience.ALL)
        private set
    var isAddingQuest by mutableStateOf(false)
        private set

    private val phone = combine(sessionManager.bluetooth, sessionManager.radarEnabled) { bluetooth, radarEnabled ->
        bluetooth to radarEnabled
    }

    val uiState: StateFlow<LobbyUiState?> =
        combine(
            combine(sessionManager.state, pendingSeekers, isStarting) { state, pending, starting ->
                Triple(state, pending, starting)
            },
            social.friends,
            account.state,
            phone,
        ) { (state, pending, starting), friends, accountState, (bluetooth, radarEnabled) ->
            buildUiState(state, pending, starting, friends, accountState, bluetooth, radarEnabled)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            buildUiState(
                sessionManager.state.value,
                pendingSeekers.value,
                isStarting.value,
                social.friends.value,
                account.state.value,
                sessionManager.bluetooth.value,
                sessionManager.radarEnabled.value,
            ),
        )

    /** For the invite panel; null until loaded. */
    val friends: StateFlow<FriendsResponse?> = social.friends
    val groups: StateFlow<GroupsResponse?> = social.groups

    /** A friend request from the lobby failed. */
    val message: StateFlow<FormMessage?> = commands.message
    val isBusy: StateFlow<Boolean> = commands.isBusy

    /** The host switches [playerId] between seeking and hiding; everybody sees it once the server has it. */
    fun toggleSeeker(playerId: PlayerId) {
        val state = uiState.value ?: return
        if (!state.isHost) return
        val seekers = state.players.filter { it.isSeeker }.map { it.id }.toSet()
        val next = if (playerId in seekers) seekers - playerId else seekers + playerId
        pendingSeekers.value = next
        viewModelScope.launch {
            rolesLock.withLock {
                try {
                    sessionManager.setSeekers(next)
                } finally {
                    // A later tap still on its way keeps its pick shown.
                    pendingSeekers.compareAndSet(next, null)
                }
            }
        }
    }

    /**
     * The server draws as many seekers as are picked now (at least one) among the players, the others hide; every
     * phone rolls the dice for it ([LobbyUiState.rolesDrawnAtMillis]).
     */
    fun drawSeekers() {
        val state = uiState.value ?: return
        if (!state.isHost || state.players.size < 2) return
        val count = state.players.count { it.isSeeker }.coerceIn(1, state.players.size - 1)
        viewModelScope.launch {
            rolesLock.withLock {
                pendingSeekers.value = null
                sessionManager.drawSeekers(count)
            }
        }
    }

    fun start() {
        val state = uiState.value ?: return
        if (!state.canStart || isStarting.value) return
        isStarting.value = true
        viewModelScope.launch {
            try {
                // The roles as the host sees them, after the changes still on their way.
                val seekers = state.players.filter { it.isSeeker }.map { it.id }
                rolesLock.withLock { sessionManager.start(seekers) }
            } finally {
                isStarting.value = false
            }
        }
    }

    /** The host plays anyway in a crowded zone, or one with few places to hide: no more warning in this game. */
    fun playAnyway() {
        if (uiState.value?.isHost != true) return
        viewModelScope.launch { sessionManager.acceptCrowding() }
    }

    fun leave() {
        pendingSeekers.value = null
        closeInvites()
        closeSettings()
        closeBoard()
        closeBuildings()
        closeMap()
        invitesSentIn = null
        sessionManager.leave()
    }

    // The radar (docs/adr/0012-nearby-radar.md).

    /** «The radar on my phone». */
    fun setRadarEnabled(enabled: Boolean) = sessionManager.setRadarEnabled(enabled)

    // The board (docs/adr/0013-quests-sparks-and-sensors.md): the host places items and makes up quests.

    fun openBoard() {
        val snapshot = sessionManager.state.value.snapshot ?: return
        if (snapshot.hostId != snapshot.me.playerId) return
        boardPanelIn = snapshot.gameId
        boardPick = null
        val allowed = allowedKinds(snapshot.settings.features)
        if (boardKind !in allowed) boardKind = allowed.firstOrNull() ?: ItemKind.QUEST_POINT
        sessionManager.clearError()
    }

    fun closeBoard() {
        boardPanelIn = null
        boardPick = null
    }

    fun pickBoardPoint(point: GeoPoint) {
        boardPick = point
    }

    fun pickBoardKind(kind: ItemKind) {
        boardKind = kind
    }

    fun pickBoardAudience(audience: Audience) {
        boardAudience = audience
    }

    fun editBoardName(name: String) {
        boardName = name.take(BOARD_NAME_MAX)
    }

    /** Null: the kind's default. */
    fun editBoardSparks(sparks: Int?) {
        boardSparks = sparks
    }

    fun pickBoardPerk(perk: PerkKind) {
        boardPerk = perk
    }

    /** Places what is picked where the host tapped. */
    fun placeItem() {
        val point = boardPick ?: return
        if (isPlacing) return
        isPlacing = true
        val request = PlaceItemRequest(
            kind = boardKind,
            point = point,
            name = boardName.trim(),
            audience = boardAudience,
            sparks = boardSparks,
            perk = boardPerk.takeIf { boardKind == ItemKind.PICKUP },
        )
        viewModelScope.launch {
            try {
                if (sessionManager.placeItem(request)) {
                    boardPick = null
                    boardName = ""
                }
            } finally {
                isPlacing = false
            }
        }
    }

    fun removeItem(itemId: ItemId) {
        viewModelScope.launch { sessionManager.removeItem(itemId) }
    }

    fun editQuestText(text: String) {
        questText = text.take(QUEST_TEXT_MAX)
    }

    fun pickQuestAudience(audience: Audience) {
        questAudience = audience
    }

    fun addQuest() {
        val text = questText.trim()
        if (text.isEmpty() || isAddingQuest) return
        isAddingQuest = true
        viewModelScope.launch {
            try {
                if (sessionManager.addQuest(text, questAudience)) questText = ""
            } finally {
                isAddingQuest = false
            }
        }
    }

    fun dismissError() = sessionManager.clearError()

    fun onLocationPermissionGranted() = sessionManager.onLocationPermissionGranted()

    /** A friend request to another player of the game (or accepting theirs). */
    fun addFriend(userId: UserId) = commands.execute({ social.sendFriendRequest(userId) })

    fun dismissMessage() = commands.dismiss()

    /** The host opens the game's setup, with the choices it was made of. */
    fun openSettings(tab: SettingsTab = SettingsTab.ZONE) {
        val snapshot = sessionManager.state.value.snapshot ?: return
        if (snapshot.hostId != snapshot.me.playerId) return
        setupDraft = GameSetup.of(snapshot.settings).coerced()
        draftCenter = null
        zoneOrigins.getOrPut(snapshot.gameId) { snapshot.settings.zone.initial.center }
        settingsTab = tab
        helpFor = null
        preview = null
        isMovingCenter = false
        pendingChanges = null
        settingsPanelIn = snapshot.gameId
        sessionManager.clearError()
    }

    fun closeSettings() {
        settingsPanelIn = null
        helpFor = null
        preview = null
        isMovingCenter = false
        pendingChanges = null
    }

    fun editSetup(setup: GameSetup) {
        setupDraft = setup.coerced()
    }

    fun pickSettingsTab(tab: SettingsTab) {
        settingsTab = tab
        preview = null
        isMovingCenter = false
    }

    fun showHelp(explainer: Explainer) {
        helpFor = explainer
    }

    fun closeHelp() {
        helpFor = null
    }

    fun focusExtra(explainer: Explainer) {
        focusedExtra = explainer
    }

    /** Plays [kind] on the settings' map, or stops it when it plays. */
    fun togglePreview(kind: SettingsPreview) {
        preview = if (preview == kind) null else kind
        isMovingCenter = false
    }

    fun stopPreview() {
        preview = null
    }

    fun toggleMovingCenter() {
        isMovingCenter = !isMovingCenter
        preview = null
    }

    /** The map stopped under the pin at [point]: the draft's zone goes there, as far from the origin as allowed. */
    fun moveDraftCenter(point: GeoPoint) {
        if (!isMovingCenter) return
        val snapshot = sessionManager.state.value.snapshot ?: return
        val origin = zoneOrigin(snapshot.gameId) ?: return
        val reach = SettingsLimits.MAX_CENTER_MOVE_METERS - CENTER_MARGIN_METERS
        val distance = point.distanceTo(origin)
        draftCenter = if (distance <= reach) {
            point
        } else {
            val offset = point.offsetFrom(origin)
            origin.moveBy(offset.eastMeters * reach / distance, offset.northMeters * reach / distance)
        }
    }

    /** Where this phone first saw [gameId]'s zone: the pin moves around it. */
    fun zoneOrigin(gameId: GameId): GeoPoint? = zoneOrigins[gameId]

    /** The draft as the game's settings: around [draftCenter] (or the zone's center), with the game's thresholds. */
    fun draftSettings(): GameSettings? {
        val current = sessionManager.state.value.snapshot?.settings ?: return null
        return setupDraft.coerced().settings(draftCenter ?: current.zone.initial.center, current.rules)
    }

    /** What differs from the game's setup so far. */
    fun changedParts(): List<ChangedPart> {
        val current = sessionManager.state.value.snapshot?.settings ?: return emptyList()
        val center = current.zone.initial.center
        return changedParts(GameSetup.of(current).coerced(), center, setupDraft.coerced(), draftCenter ?: center)
    }

    /**
     * Sends the draft (nothing changed, nothing sent); the phone remembers the setup for the host's next game, never the
     * place. A draft that touches the map first shows «What changes» ([pendingChanges]); [confirmSave] sends it then.
     */
    fun saveSettings() {
        val snapshot = sessionManager.state.value.snapshot ?: return
        if (isSavingSettings) return
        val settings = draftSettings() ?: return
        if (changedParts().isEmpty()) {
            closeSettings()
            return
        }
        val changes = settingsChanges(snapshot.settings, settings, snapshot.items, ::allowedKinds)
        if (changes.isNotEmpty()) {
            pendingChanges = changes
            return
        }
        send(settings)
    }

    fun confirmSave() {
        pendingChanges = null
        draftSettings()?.let(::send)
    }

    fun cancelSave() {
        pendingChanges = null
    }

    private fun send(settings: GameSettings) {
        if (isSavingSettings) return
        val setup = setupDraft.coerced()
        isSavingSettings = true
        preview = null
        isMovingCenter = false
        viewModelScope.launch {
            try {
                if (sessionManager.updateSettings(settings, setup)) closeSettings()
            } finally {
                isSavingSettings = false
            }
        }
    }

    // Open buildings (docs/adr/0014-settings-lobby-redesign-open-buildings.md, section 4).

    fun openBuildings() {
        val snapshot = sessionManager.state.value.snapshot ?: return
        if (snapshot.hostId != snapshot.me.playerId) return
        buildingsPanelIn = snapshot.gameId
        buildingTap = null
        sessionManager.clearError()
    }

    fun closeBuildings() {
        buildingsPanelIn = null
        buildingTap = null
    }

    /** The host tapped [point] on the map of buildings: a building there is picked, elsewhere nothing is. */
    fun pickBuilding(point: GeoPoint) {
        val buildings = uiState.value?.buildings ?: return
        buildingTap = point.takeIf { tap -> (buildings.buildings + buildings.open).any { it.contains(tap) } }
    }

    /** Opens the picked building for hiding, or closes it again; right away, like the board. */
    fun toggleBuilding() {
        val tap = buildingTap ?: return
        if (isTogglingBuilding) return
        isTogglingBuilding = true
        viewModelScope.launch {
            try {
                sessionManager.toggleOpenBuilding(tap)
            } finally {
                isTogglingBuilding = false
            }
        }
    }

    fun openMap() {
        mapPanelIn = sessionManager.state.value.snapshot?.gameId
    }

    fun closeMap() {
        mapPanelIn = null
    }

    fun openInvites(gameId: GameId) {
        invitePanelIn = gameId
        pickedFriends = emptySet()
        pickedGroups = emptySet()
        sessionManager.clearError()
        // Groups load on demand; friends may have changed since the login.
        viewModelScope.launch { social.refreshGroups() }
        viewModelScope.launch { social.refreshFriends() }
    }

    fun closeInvites() {
        invitePanelIn = null
        pickedFriends = emptySet()
        pickedGroups = emptySet()
    }

    fun toggleFriend(userId: UserId) {
        pickedFriends = if (userId in pickedFriends) pickedFriends - userId else pickedFriends + userId
    }

    fun toggleGroup(groupId: GroupId) {
        pickedGroups = if (groupId in pickedGroups) pickedGroups - groupId else pickedGroups + groupId
    }

    /** One request per group (the first together with the friends); stops at the first refusal, which is shown. */
    fun sendInvites() {
        val gameId = invitePanelIn ?: return
        val friends = pickedFriends.toList()
        val groups = pickedGroups.toList()
        if (isSendingInvites || (friends.isEmpty() && groups.isEmpty())) return
        isSendingInvites = true
        viewModelScope.launch {
            try {
                var sent = sessionManager.invite(userIds = friends, groupId = groups.firstOrNull())
                for (groupId in groups.drop(1)) {
                    if (!sent) break
                    sent = sessionManager.invite(groupId = groupId)
                }
                if (sent) {
                    closeInvites()
                    invitesSentIn = gameId
                }
            } finally {
                isSendingInvites = false
            }
        }
    }

    fun dismissInvitesSent() {
        invitesSentIn = null
    }

    /** The last zone by streets made from the server's polygons: the same object while they stay the same. */
    private var streetZoneCache: Pair<List<ZonePolygon>, StreetZone>? = null

    private fun streetZoneOf(stages: List<ZonePolygon>): StreetZone {
        streetZoneCache?.let { (cached, zone) -> if (cached == stages) return zone }
        return StreetZone(stages).also { streetZoneCache = stages to it }
    }

    private fun buildUiState(
        state: SessionState,
        pending: Set<PlayerId>?,
        starting: Boolean,
        friends: FriendsResponse?,
        accountState: AccountState,
        bluetooth: BluetoothState,
        radarEnabled: Boolean,
    ): LobbyUiState? {
        val snapshot = state.snapshot ?: return null
        val me = snapshot.me.playerId
        val settings = snapshot.settings
        val players = snapshot.players.map { player ->
            val silentFor = player.lastSeenMillis?.let { snapshot.serverTimeMillis - it }
            LobbyPlayer(
                id = player.id,
                name = player.name,
                isMe = player.id == me,
                isHost = player.id == snapshot.hostId,
                // A pick of an earlier game doesn't match any id here.
                isSeeker = pending?.let { player.id in it } ?: (player.role == Role.SEEKER),
                isOffline = silentFor != null && silentFor > OFFLINE_AFTER_MILLIS,
                account = playerAccount(player, me, accountState, friends),
                capabilities = player.capabilities,
            )
        }
        val seekerCount = players.count { it.isSeeker }
        val capacity = snapshot.capacity?.takeIf { it.state == CapacityState.READY }
        val streetZone = snapshot.streetZone
        val buildings = state.buildings
            ?.takeIf { it.mapRevision == snapshot.mapRevision }
            ?.withOpenBuildings(settings.openBuildings)
        val streets = state.streetZone
            ?.takeIf { it.mapRevision == snapshot.mapRevision && it.stages.size == settings.zone.stages.size + 1 }
            ?.takeIf { zone -> zone.stages.all { it.outline.size >= MIN_OUTLINE_POINTS } }
            ?.let { streetZoneOf(it.stages) }
        return LobbyUiState(
            gameId = snapshot.gameId,
            joinCode = snapshot.joinCode,
            players = players,
            isHost = snapshot.hostId == me,
            amSeeker = players.firstOrNull { it.isMe }?.isSeeker == true,
            // At least one seeker and at least one hider, and the zone by streets built (or given up on).
            canStart = seekerCount in 1 until players.size && streetZone != StreetZoneState.LOADING,
            isStarting = starting,
            connectionStatus = state.connectionStatus,
            isSharingLocation = state.isSharingLocation,
            error = state.lastError,
            buildingsState = snapshot.buildings,
            buildingCount = buildings?.buildings?.size.takeIf { snapshot.buildings == BuildingsState.READY },
            isBuildingRuleOff = snapshot.buildings == BuildingsState.UNAVAILABLE,
            isBuildingStreetZone = streetZone == StreetZoneState.LOADING,
            isStreetZoneOff = streetZone == StreetZoneState.UNAVAILABLE,
            // The server takes invitations from players who play with an account (logged in on this phone).
            canInvite = accountState.isLoggedIn && snapshot.players.any { it.id == me && it.userId != null },
            userIdsInGame = snapshot.players.mapNotNull { it.userId }.toSet(),
            zoneRadiusMeters = settings.zone.initial.radiusMeters.roundToInt(),
            zoneShape = settings.zoneShape,
            hidingMinutes = settings.hidingSeconds.minutesRoundedUp(),
            seekingMinutes = settings.seekingSeconds.minutesRoundedUp(),
            glowEveryMinutes = settings.glowEverySeconds.minutesRoundedUp().takeIf { Glow.isOn(settings) },
            rolesDrawnAtMillis = snapshot.rolesDrawnAtMillis,
            enabledFeatures = snapshot.enabledFeatures.mapNotNull { name ->
                ServerFeature.entries.firstOrNull { it.name == name }
            }.toSet(),
            features = settings.features,
            questKinds = settings.quests,
            items = snapshot.items,
            quests = snapshot.quests,
            zoneCenter = settings.zone.initial.center,
            zone = ZoneTimeline(settings.zone, startedAtMillis = null, streets = streets),
            streets = streets,
            buildings = buildings?.takeIf { snapshot.buildings == BuildingsState.READY },
            bluetooth = bluetooth,
            radarEnabled = radarEnabled,
            capacity = capacity?.players,
            bigGame = snapshot.bigGame,
            friendsHere = players.filter { it.account.relation == UserRelation.FRIEND },
            // A big game's poll lists only the player and their friends; the server counts everybody.
            playerCount = snapshot.counts?.players ?: players.size,
            crowding = capacity?.takeIf { snapshot.hostId == me && Capacity.needsWarning(it, players.size) }?.let {
                Crowding(
                    capacity = it.players ?: 0,
                    players = players.size,
                    isCrowded = Capacity.isCrowded(it, players.size),
                    fewCovers = it.fewCovers,
                )
            },
            openGame = settings.openGame,
            spectatorDelaySeconds = settings.spectatorDelaySeconds,
            spectators = snapshot.spectators,
        )
    }

    /** The kinds of items the game's features allow on the board. */
    fun allowedKinds(features: GameFeatures): List<ItemKind> = buildList {
        if (features.quests) add(ItemKind.QUEST_POINT)
        if (features.checkpoints) {
            add(ItemKind.CHECKPOINT_GEO)
            add(ItemKind.CHECKPOINT_SCAN)
        }
        if (features.pickups) add(ItemKind.PICKUP)
    }

    private fun Int.minutesRoundedUp(): Int = (this + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE

    private companion object {
        const val SECONDS_PER_MINUTE = 60

        /** A zone polygon needs a closed ring. */
        const val MIN_OUTLINE_POINTS = 4

        /** The pin stays this far inside the reach the server allows. */
        const val CENTER_MARGIN_METERS = 100.0

        /** A player whose phone has not asked the server for this long is shown as not connected. */
        const val OFFLINE_AFTER_MILLIS = 20_000L
        const val BOARD_NAME_MAX = 30
        const val QUEST_TEXT_MAX = 120
    }
}

data class LobbyUiState(
    val gameId: GameId,
    val joinCode: String,
    val players: List<LobbyPlayer>,
    val isHost: Boolean,
    /** The role the host gave this player so far: everybody sees the roles before the start. */
    val amSeeker: Boolean,
    val canStart: Boolean,
    val isStarting: Boolean,
    val connectionStatus: ConnectionStatus,
    val isSharingLocation: Boolean,
    val error: SessionError?,
    /** The zone's buildings: loading, ready ([buildingCount] once this phone has them) or unavailable. */
    val buildingsState: BuildingsState?,
    val buildingCount: Int?,
    /** The server could not load the zone's buildings: the game will run without that rule. */
    val isBuildingRuleOff: Boolean,
    /** The zone by streets is being built: the game can start once it is there (or given up on). */
    val isBuildingStreetZone: Boolean,
    /** No zone by streets could be built here: the game uses the circles. */
    val isStreetZoneOff: Boolean,
    /** Playing with an account: friends and groups can be invited. */
    val canInvite: Boolean,
    /** Accounts already in the game: no need to invite them. */
    val userIdsInGame: Set<UserId>,
    /** The settings, shown as chips: the zone at the start and its shape, the hiding and seeking times, the glow. */
    val zoneRadiusMeters: Int,
    val zoneShape: ZoneShape,
    val hidingMinutes: Int,
    val seekingMinutes: Int,
    /** Null: no glow in this game. */
    val glowEveryMinutes: Int?,
    /** When the host last drew the roles at random: every phone rolls the dice once for each new value. */
    val rolesDrawnAtMillis: Long?,
    /** The server features the operator has on: what the host may turn on (docs/adr/0012-nearby-radar.md). */
    val enabledFeatures: Set<ServerFeature>,
    /** What the host turned on for this game. */
    val features: GameFeatures,
    /** The catalog quests the host picked. */
    val questKinds: List<QuestKind>,
    /** The board as this player sees it (the host with the codes). */
    val items: List<BoardItem>,
    /** The host's own quests so far. */
    val quests: List<QuestView>,
    val zoneCenter: GeoPoint,
    /** The zone as the search starts (not started), for the maps of the lobby, the settings and the board. */
    val zone: ZoneTimeline,
    /** The zone by streets once this phone has it; null: circles, or not yet. */
    val streets: StreetZone? = null,
    /**
     * The zone's buildings once this phone has them, split into forbidden and open by the host's points of now
     * (docs/adr/0014-settings-lobby-redesign-open-buildings.md).
     */
    val buildings: BuildingsResponse? = null,
    /** This phone's Bluetooth, for the radar. */
    val bluetooth: BluetoothState,
    /** «The radar on my phone». */
    val radarEnabled: Boolean,
    /** About how many players the zone fits (docs/adr/0010-big-games.md); null until the server knows. */
    val capacity: Int? = null,
    /** The host's warning: too many players for the zone, or few places to hide; null: none (or played anyway). */
    val crowding: Crowding? = null,
    /**
     * A big game's lobby (docs/adr/0010-big-games.md): hosted by the server, it starts at [BigGameInfo.startsAtMillis];
     * no join code, no host, the list shows only [friendsHere].
     */
    val bigGame: BigGameInfo? = null,
    val friendsHere: List<LobbyPlayer> = emptyList(),
    /** Everybody in the lobby, also those a big game's [players] leaves out. */
    val playerCount: Int = players.size,
    /** Open to spectators (docs/adr/0011-spectators-and-recordings.md), [spectatorDelaySeconds] behind. */
    val openGame: Boolean = false,
    val spectatorDelaySeconds: Int = 0,
    /** How many watch right now. */
    val spectators: Int = 0,
)

/** Too many players for the zone ([isCrowded]: [players] where it fits [capacity]), or few places to hide. */
data class Crowding(val capacity: Int, val players: Int, val isCrowded: Boolean, val fewCovers: Boolean)

data class LobbyPlayer(
    val id: PlayerId,
    val name: String,
    val isMe: Boolean,
    val isHost: Boolean,
    val isSeeker: Boolean,
    /** The player's phone has not been heard from for a while (closed app, no network). */
    val isOffline: Boolean,
    val account: PlayerAccount,
    /** What the player's phone can do (the radar); null: it never said. */
    val capabilities: Capabilities?,
)
