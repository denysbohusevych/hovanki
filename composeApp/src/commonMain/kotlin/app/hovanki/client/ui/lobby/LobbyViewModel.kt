package app.hovanki.client.ui.lobby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.account.AccountManager
import app.hovanki.client.session.DraftZonePreview
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.social.SocialManager
import app.hovanki.client.ui.common.CommandRunner
import app.hovanki.client.ui.settings.settingsChanges
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.geo.offsetFrom
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ItemKind
import app.hovanki.shared.protocol.PlaceItemRequest
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.rules.GameSetup
import app.hovanki.shared.rules.SettingsLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The lobby (docs/adr/0009-game-setup-glow-streets.md): the roles live on the server, so every phone shows who seeks;
 * the host picks them, draws them at random or changes the game's setup.
 *
 * One state for the whole screen, [uiState], and one way in, [onEvent] (docs/architecture.md, «Состояние экрана»).
 * This phone's own choices ([LobbyLocal]) change the state at once, on the caller's thread: a text field's edit is
 * there before the next frame.
 */
class LobbyViewModel(
    private val sessionManager: GameSessionManager,
    private val social: SocialManager,
    private val account: AccountManager,
) : ViewModel() {
    private val commands = CommandRunner(viewModelScope)

    /** Role changes and the start go to the server one after another, in the order the host tapped. */
    private val rolesLock = Mutex()

    /**
     * The zone by streets of the draft, built by the server before «Save»
     * (docs/adr/0014-settings-lobby-redesign-open-buildings.md, section 2.3): a tap on «By streets» shows the blocks.
     */
    private val draftZonePreview = DraftZonePreview(viewModelScope, sessionManager::previewSettings)

    /**
     * Where each game was made, as far as this phone knows: the zone moves at most so far from it
     * ([SettingsLimits.MAX_CENTER_MOVE_METERS]). The first center seen; the server checks the real one.
     */
    private val zoneOrigins = mutableMapOf<GameId, GeoPoint>()

    private val builder = LobbyStateBuilder()
    private var local = LobbyLocal()
    private var inputs = currentInputs()
    private val mutableUiState = MutableStateFlow(build())
    val uiState: StateFlow<LobbyUiState?> = mutableUiState.asStateFlow()

    init {
        val phone = combine(sessionManager.bluetooth, sessionManager.radarEnabled) { bluetooth, radarEnabled ->
            bluetooth to radarEnabled
        }
        val people = combine(social.friends, social.groups, account.state) { friends, groups, accountState ->
            Triple(friends, groups, accountState)
        }
        val commandState = combine(commands.message, commands.isBusy) { message, busy -> message to busy }
        viewModelScope.launch {
            combine(
                sessionManager.state,
                people,
                phone,
                draftZonePreview.zone,
                commandState,
            ) { session, (friends, groups, accountState), (bluetooth, radarEnabled), draftZone, (message, busy) ->
                LobbyInputs(session, friends, groups, accountState, bluetooth, radarEnabled, draftZone, message, busy)
            }.collect {
                inputs = it
                publish()
            }
        }
    }

    fun onEvent(event: LobbyEvent) {
        when (event) {
            is LobbyEvent.ToggleSeeker -> toggleSeeker(event.playerId)
            LobbyEvent.DrawSeekers -> drawSeekers()
            LobbyEvent.Start -> start()
            LobbyEvent.PlayAnyway -> playAnyway()
            LobbyEvent.Leave -> leave()
            is LobbyEvent.SetRadarEnabled -> sessionManager.setRadarEnabled(event.enabled)
            LobbyEvent.DismissError -> sessionManager.clearError()
            LobbyEvent.LocationPermissionGranted -> sessionManager.onLocationPermissionGranted()
            is LobbyEvent.AddFriend -> commands.execute({ social.sendFriendRequest(event.userId) })
            LobbyEvent.DismissMessage -> commands.dismiss()
            LobbyEvent.OpenMap -> update { it.copy(mapIn = sessionManager.state.value.snapshot?.gameId) }
            LobbyEvent.CloseMap -> update { it.copy(mapIn = null) }
            LobbyEvent.DismissInvitesSent -> update { it.copy(invitesSentIn = null) }
            is LobbyEvent.Invite -> onInvite(event)
            is LobbyEvent.Settings -> onSettings(event)
            is LobbyEvent.Buildings -> onBuildings(event)
            is LobbyEvent.Board -> onBoard(event)
        }
    }

    private fun currentInputs() = LobbyInputs(
        session = sessionManager.state.value,
        friends = social.friends.value,
        groups = social.groups.value,
        account = account.state.value,
        bluetooth = sessionManager.bluetooth.value,
        radarEnabled = sessionManager.radarEnabled.value,
        draftZone = draftZonePreview.zone.value,
        message = commands.message.value,
        isBusy = commands.isBusy.value,
    )

    private fun build(): LobbyUiState? = builder.build(inputs, local, zoneOrigins::get)

    private fun publish() {
        mutableUiState.value = build()
    }

    /** Changes this phone's own part of the lobby; the state follows at once. */
    private fun update(change: (LobbyLocal) -> LobbyLocal) {
        local = change(local)
        publish()
    }

    /** The game, when this phone hosts it: the host's panels and commands need it. */
    private fun hostedSnapshot() = sessionManager.state.value.snapshot?.takeIf { it.amHost }

    // The roles and the start.

    /** The host switches [playerId] between seeking and hiding; everybody sees it once the server has it. */
    private fun toggleSeeker(playerId: PlayerId) {
        val state = uiState.value ?: return
        if (!state.isHost) return
        val seekers = state.players.filter { it.isSeeker }.map { it.id }.toSet()
        val next = if (playerId in seekers) seekers - playerId else seekers + playerId
        update { it.copy(pendingSeekers = next) }
        viewModelScope.launch {
            rolesLock.withLock {
                try {
                    sessionManager.setSeekers(next)
                } finally {
                    // A later tap still on its way keeps its pick shown.
                    update { if (it.pendingSeekers == next) it.copy(pendingSeekers = null) else it }
                }
            }
        }
    }

    /**
     * The server draws as many seekers as are picked now (at least one) among the players, the others hide; every
     * phone rolls the dice for it ([LobbyUiState.rolesDrawnAtMillis]).
     */
    private fun drawSeekers() {
        val state = uiState.value ?: return
        if (!state.isHost || state.players.size < 2) return
        val count = state.players.count { it.isSeeker }.coerceIn(1, state.players.size - 1)
        viewModelScope.launch {
            rolesLock.withLock {
                update { it.copy(pendingSeekers = null) }
                sessionManager.drawSeekers(count)
            }
        }
    }

    private fun start() {
        val state = uiState.value ?: return
        if (!state.canStart || local.isStarting) return
        update { it.copy(isStarting = true) }
        viewModelScope.launch {
            try {
                // The roles as the host sees them, after the changes still on their way.
                val seekers = state.players.filter { it.isSeeker }.map { it.id }
                rolesLock.withLock { sessionManager.start(seekers) }
            } finally {
                update { it.copy(isStarting = false) }
            }
        }
    }

    /** The host plays anyway in a crowded zone, or one with few places to hide: no more warning in this game. */
    private fun playAnyway() {
        if (uiState.value?.isHost != true) return
        viewModelScope.launch { sessionManager.acceptCrowding() }
    }

    private fun leave() {
        update {
            it.copy(
                pendingSeekers = null,
                invite = null,
                invitesSentIn = null,
                settings = null,
                buildings = null,
                mapIn = null,
                boardIn = null,
                board = it.board.copy(pick = null),
            )
        }
        previewDraftZone()
        sessionManager.leave()
    }

    // The invitations.

    private fun onInvite(event: LobbyEvent.Invite) {
        when (event) {
            LobbyEvent.Invite.Open -> {
                val gameId = sessionManager.state.value.snapshot?.gameId ?: return
                update { it.copy(invite = InviteDraft(gameId)) }
                sessionManager.clearError()
                // Groups load on demand; friends may have changed since the login.
                viewModelScope.launch { social.refreshGroups() }
                viewModelScope.launch { social.refreshFriends() }
            }

            LobbyEvent.Invite.Close -> update { it.copy(invite = null) }

            is LobbyEvent.Invite.ToggleFriend -> updateInvite { invite ->
                val picked = invite.pickedFriends
                invite.copy(
                    pickedFriends = if (event.userId in
                        picked
                    ) {
                        picked - event.userId
                    } else {
                        picked + event.userId
                    },
                )
            }

            is LobbyEvent.Invite.ToggleGroup -> updateInvite { invite ->
                val picked = invite.pickedGroups
                invite.copy(
                    pickedGroups = if (event.groupId in
                        picked
                    ) {
                        picked - event.groupId
                    } else {
                        picked + event.groupId
                    },
                )
            }

            LobbyEvent.Invite.Send -> sendInvites()
        }
    }

    private fun updateInvite(change: (InviteDraft) -> InviteDraft) = update { it.copy(invite = it.invite?.let(change)) }

    /** One request per group (the first together with the friends); stops at the first refusal, which is shown. */
    private fun sendInvites() {
        val invite = local.invite ?: return
        val friends = invite.pickedFriends.toList()
        val groups = invite.pickedGroups.toList()
        if (invite.isSending || (friends.isEmpty() && groups.isEmpty())) return
        updateInvite { it.copy(isSending = true) }
        viewModelScope.launch {
            var sent = false
            try {
                sent = sessionManager.invite(userIds = friends, groupId = groups.firstOrNull())
                for (groupId in groups.drop(1)) {
                    if (!sent) break
                    sent = sessionManager.invite(groupId = groupId)
                }
            } finally {
                update { current ->
                    if (sent) {
                        current.copy(invite = null, invitesSentIn = invite.gameId)
                    } else {
                        current.copy(invite = current.invite?.copy(isSending = false))
                    }
                }
            }
        }
    }

    // The settings (docs/adr/0014-settings-lobby-redesign-open-buildings.md, section 2).

    private fun onSettings(event: LobbyEvent.Settings) {
        when (event) {
            is LobbyEvent.Settings.Open -> openSettings(event)

            LobbyEvent.Settings.Close -> closeSettings()

            is LobbyEvent.Settings.Edit -> {
                // The draft stands still while it is being sent.
                if (local.settings?.isSaving != false) return
                updateSettings { it.copy(setup = event.setup.coerced()) }
                previewDraftZone()
            }

            is LobbyEvent.Settings.PickTab -> updateSettings {
                it.copy(tab = event.tab, preview = null, isMovingCenter = false)
            }

            is LobbyEvent.Settings.ShowHelp -> updateSettings { it.copy(helpFor = event.explainer) }

            LobbyEvent.Settings.CloseHelp -> updateSettings { it.copy(helpFor = null) }

            is LobbyEvent.Settings.FocusExtra -> update { it.copy(focusedExtra = event.explainer) }

            is LobbyEvent.Settings.TogglePreview -> updateSettings {
                it.copy(preview = if (it.preview == event.kind) null else event.kind, isMovingCenter = false)
            }

            LobbyEvent.Settings.StopPreview -> updateSettings { it.copy(preview = null) }

            LobbyEvent.Settings.ToggleMovingCenter -> updateSettings {
                it.copy(isMovingCenter = !it.isMovingCenter, preview = null)
            }

            is LobbyEvent.Settings.MoveCenter -> moveDraftCenter(event.point)

            LobbyEvent.Settings.Save -> saveSettings()

            LobbyEvent.Settings.ConfirmSave -> {
                updateSettings { it.copy(pendingChanges = null) }
                draftSettings()?.let(::send)
            }

            LobbyEvent.Settings.CancelSave -> updateSettings { it.copy(pendingChanges = null) }
        }
    }

    private fun updateSettings(change: (SettingsDraft) -> SettingsDraft) =
        update { it.copy(settings = it.settings?.let(change)) }

    /** The host opens the game's setup, with the choices it was made of. */
    private fun openSettings(event: LobbyEvent.Settings.Open) {
        val snapshot = hostedSnapshot() ?: return
        zoneOrigins.getOrPut(snapshot.gameId) { snapshot.settings.zone.initial.center }
        update {
            it.copy(
                settings = SettingsDraft(
                    gameId = snapshot.gameId,
                    setup = GameSetup.of(snapshot.settings).coerced(),
                    tab = event.tab,
                ),
            )
        }
        sessionManager.clearError()
        previewDraftZone()
    }

    private fun closeSettings() {
        update { it.copy(settings = null) }
        previewDraftZone()
    }

    /** The map stopped under the pin at [point]: the draft's zone goes there, as far from the origin as allowed. */
    private fun moveDraftCenter(point: GeoPoint) {
        if (local.settings?.isMovingCenter != true) return
        val snapshot = sessionManager.state.value.snapshot ?: return
        val origin = zoneOrigins[snapshot.gameId] ?: return
        val reach = SettingsLimits.MAX_CENTER_MOVE_METERS - CENTER_MARGIN_METERS
        val distance = point.distanceTo(origin)
        val center = if (distance <= reach) {
            point
        } else {
            val offset = point.offsetFrom(origin)
            origin.moveBy(offset.eastMeters * reach / distance, offset.northMeters * reach / distance)
        }
        updateSettings { it.copy(center = center) }
        previewDraftZone()
    }

    /** The draft's zone by streets is asked for while the settings are open and the game has another zone. */
    private fun previewDraftZone() {
        val saved = sessionManager.state.value.snapshot?.settings
        draftZonePreview.show(draftSettings(), saved)
    }

    /** The open draft as the game's settings; null: the settings are closed. */
    private fun draftSettings(): GameSettings? {
        val current = sessionManager.state.value.snapshot?.settings ?: return null
        return local.settings?.settings(current)
    }

    /**
     * Sends the draft (nothing changed, nothing sent); the phone remembers the setup for the host's next game, never the
     * place. A draft that touches the map first shows «What changes» ([SettingsPanelState.pendingChanges]);
     * [LobbyEvent.Settings.ConfirmSave] sends it then.
     */
    private fun saveSettings() {
        val snapshot = sessionManager.state.value.snapshot ?: return
        val draft = local.settings ?: return
        if (draft.isSaving) return
        val settings = draft.settings(snapshot.settings)
        if (draft.changedParts(snapshot.settings).isEmpty()) {
            closeSettings()
            return
        }
        val changes = settingsChanges(snapshot.settings, settings, snapshot.items, ::allowedKinds)
        if (changes.isNotEmpty()) {
            updateSettings { it.copy(pendingChanges = changes) }
            return
        }
        send(settings)
    }

    private fun send(settings: GameSettings) {
        val draft = local.settings ?: return
        if (draft.isSaving) return
        val setup = draft.setup.coerced()
        updateSettings { it.copy(isSaving = true, preview = null, isMovingCenter = false) }
        viewModelScope.launch {
            var saved = false
            try {
                saved = sessionManager.updateSettings(settings, setup)
            } finally {
                updateSettings { it.copy(isSaving = false) }
            }
            if (saved) closeSettings()
        }
    }

    // Open buildings (docs/adr/0014-settings-lobby-redesign-open-buildings.md, section 4).

    private fun onBuildings(event: LobbyEvent.Buildings) {
        when (event) {
            LobbyEvent.Buildings.Open -> {
                val snapshot = hostedSnapshot() ?: return
                update { it.copy(buildings = BuildingsDraft(snapshot.gameId)) }
                sessionManager.clearError()
            }

            LobbyEvent.Buildings.Close -> update { it.copy(buildings = null) }

            is LobbyEvent.Buildings.Pick -> {
                val buildings = uiState.value?.buildings ?: return
                val tap = event.point.takeIf { buildings.pickedAt(it) != null }
                update { it.copy(buildings = it.buildings?.copy(tap = tap)) }
            }

            LobbyEvent.Buildings.Toggle -> toggleBuilding()
        }
    }

    /** Opens the picked building for hiding, or closes it again; right away, like the board. */
    private fun toggleBuilding() {
        val picker = local.buildings ?: return
        val tap = picker.tap ?: return
        if (picker.isToggling) return
        update { it.copy(buildings = it.buildings?.copy(isToggling = true)) }
        viewModelScope.launch {
            try {
                sessionManager.toggleOpenBuilding(tap)
            } finally {
                update { it.copy(buildings = it.buildings?.copy(isToggling = false)) }
            }
        }
    }

    // The board (docs/adr/0013-quests-sparks-and-sensors.md): the host places items and makes up quests.

    private fun onBoard(event: LobbyEvent.Board) {
        when (event) {
            LobbyEvent.Board.Open -> openBoard()
            LobbyEvent.Board.Close -> update { it.copy(boardIn = null, board = it.board.copy(pick = null)) }
            is LobbyEvent.Board.PickPoint -> updateBoard { it.copy(pick = event.point) }
            is LobbyEvent.Board.PickKind -> updateBoard { it.copy(kind = event.kind) }
            is LobbyEvent.Board.PickAudience -> updateBoard { it.copy(audience = event.audience) }
            is LobbyEvent.Board.EditName -> updateBoard { it.copy(name = event.name.take(BOARD_NAME_MAX)) }
            is LobbyEvent.Board.EditSparks -> updateBoard { it.copy(sparks = event.sparks) }
            is LobbyEvent.Board.PickPerk -> updateBoard { it.copy(perk = event.perk) }
            LobbyEvent.Board.Place -> placeItem()
            is LobbyEvent.Board.Remove -> viewModelScope.launch { sessionManager.removeItem(event.itemId) }
            is LobbyEvent.Board.EditQuestText -> updateBoard { it.copy(questText = event.text.take(QUEST_TEXT_MAX)) }
            is LobbyEvent.Board.PickQuestAudience -> updateBoard { it.copy(questAudience = event.audience) }
            LobbyEvent.Board.AddQuest -> addQuest()
        }
    }

    private fun updateBoard(change: (BoardDraft) -> BoardDraft) = update { it.copy(board = change(it.board)) }

    private fun openBoard() {
        val snapshot = hostedSnapshot() ?: return
        val allowed = allowedKinds(snapshot.settings.features)
        update {
            val kind = it.board.kind.takeIf { kind -> kind in allowed } ?: allowed.firstOrNull() ?: ItemKind.QUEST_POINT
            it.copy(boardIn = snapshot.gameId, board = it.board.copy(pick = null, kind = kind))
        }
        sessionManager.clearError()
    }

    private fun placeItem() {
        val board = local.board
        val point = board.pick ?: return
        if (board.isPlacing) return
        updateBoard { it.copy(isPlacing = true) }
        val request = PlaceItemRequest(
            kind = board.kind,
            point = point,
            name = board.name.trim(),
            audience = board.audience,
            sparks = board.sparks,
            perk = board.perk.takeIf { board.kind == ItemKind.PICKUP },
        )
        viewModelScope.launch {
            var placed = false
            try {
                placed = sessionManager.placeItem(request)
            } finally {
                updateBoard {
                    if (placed) it.copy(isPlacing = false, pick = null, name = "") else it.copy(isPlacing = false)
                }
            }
        }
    }

    private fun addQuest() {
        val board = local.board
        val text = board.questText.trim()
        if (text.isEmpty() || board.isAddingQuest) return
        updateBoard { it.copy(isAddingQuest = true) }
        viewModelScope.launch {
            var added = false
            try {
                added = sessionManager.addQuest(text, board.questAudience)
            } finally {
                updateBoard {
                    if (added) it.copy(isAddingQuest = false, questText = "") else it.copy(isAddingQuest = false)
                }
            }
        }
    }

    private companion object {
        /** The pin stays this far inside the reach the server allows. */
        const val CENTER_MARGIN_METERS = 100.0
        const val BOARD_NAME_MAX = 30
        const val QUEST_TEXT_MAX = 120
    }
}
