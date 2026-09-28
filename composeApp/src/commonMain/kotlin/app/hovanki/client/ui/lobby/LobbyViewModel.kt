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
import app.hovanki.client.ui.common.CommandRunner
import app.hovanki.client.ui.common.FormMessage
import app.hovanki.client.ui.common.PlayerAccount
import app.hovanki.client.ui.common.playerAccount
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.FriendsResponse
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.GroupsResponse
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.StreetZoneState
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.ZoneShape
import app.hovanki.shared.rules.GameSetup
import app.hovanki.shared.rules.Glow
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

    val uiState: StateFlow<LobbyUiState?> =
        combine(
            sessionManager.state,
            pendingSeekers,
            isStarting,
            social.friends,
            account.state,
        ) { state, pending, starting, friends, accountState ->
            buildUiState(state, pending, starting, friends, accountState)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            buildUiState(
                sessionManager.state.value,
                pendingSeekers.value,
                isStarting.value,
                social.friends.value,
                account.state.value,
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

    fun leave() {
        pendingSeekers.value = null
        closeInvites()
        closeSettings()
        invitesSentIn = null
        sessionManager.leave()
    }

    fun dismissError() = sessionManager.clearError()

    fun onLocationPermissionGranted() = sessionManager.onLocationPermissionGranted()

    /** A friend request to another player of the game (or accepting theirs). */
    fun addFriend(userId: UserId) = commands.execute({ social.sendFriendRequest(userId) })

    fun dismissMessage() = commands.dismiss()

    /** The host opens the game's setup, with the choices it was made of. */
    fun openSettings() {
        val snapshot = sessionManager.state.value.snapshot ?: return
        if (snapshot.hostId != snapshot.me.playerId) return
        setupDraft = GameSetup.of(snapshot.settings).coerced()
        settingsPanelIn = snapshot.gameId
        sessionManager.clearError()
    }

    fun closeSettings() {
        settingsPanelIn = null
    }

    fun editSetup(setup: GameSetup) {
        setupDraft = setup.coerced()
    }

    /**
     * Sends the setup around the zone's center as it is (nothing changed, nothing sent); the phone remembers it for the
     * host's next game.
     */
    fun saveSettings() {
        val snapshot = sessionManager.state.value.snapshot ?: return
        if (isSavingSettings) return
        val current = snapshot.settings
        val setup = setupDraft.coerced()
        val settings = setup.settings(current.zone.initial.center, current.rules)
        if (settings == current || GameSetup.of(current).coerced() == setup) {
            closeSettings()
            return
        }
        isSavingSettings = true
        viewModelScope.launch {
            try {
                if (sessionManager.updateSettings(settings, setup)) closeSettings()
            } finally {
                isSavingSettings = false
            }
        }
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

    private fun buildUiState(
        state: SessionState,
        pending: Set<PlayerId>?,
        starting: Boolean,
        friends: FriendsResponse?,
        accountState: AccountState,
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
            )
        }
        val seekerCount = players.count { it.isSeeker }
        val streetZone = snapshot.streetZone
        val buildings = state.buildings?.takeIf { it.mapRevision == snapshot.mapRevision }
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
        )
    }

    private fun Int.minutesRoundedUp(): Int = (this + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE

    private companion object {
        const val SECONDS_PER_MINUTE = 60

        /** A player whose phone has not asked the server for this long is shown as not connected. */
        const val OFFLINE_AFTER_MILLIS = 20_000L
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
)

data class LobbyPlayer(
    val id: PlayerId,
    val name: String,
    val isMe: Boolean,
    val isHost: Boolean,
    val isSeeker: Boolean,
    /** The player's phone has not been heard from for a while (closed app, no network). */
    val isOffline: Boolean,
    val account: PlayerAccount,
)
