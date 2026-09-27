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
import app.hovanki.shared.protocol.UserId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class LobbyViewModel(
    private val sessionManager: GameSessionManager,
    private val social: SocialManager,
    private val account: AccountManager,
) : ViewModel() {
    /** Seekers picked by the host; only sent to the server on start. */
    private val selectedSeekers = MutableStateFlow<Set<PlayerId>>(emptySet())
    private val isStarting = MutableStateFlow(false)
    private val commands = CommandRunner(viewModelScope)

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

    val uiState: StateFlow<LobbyUiState?> =
        combine(
            sessionManager.state,
            selectedSeekers,
            isStarting,
            social.friends,
            account.state,
        ) { state, seekers, starting, friends, accountState ->
            buildUiState(state, seekers, starting, friends, accountState)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            buildUiState(
                sessionManager.state.value,
                selectedSeekers.value,
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

    fun toggleSeeker(playerId: PlayerId) {
        selectedSeekers.update { if (playerId in it) it - playerId else it + playerId }
    }

    /** Picks as many seekers as are picked now (at least one) at random; the others hide. */
    fun shuffleSeekers() {
        val players = uiState.value?.players ?: return
        if (players.size < 2) return
        val count = players.count { it.isSeeker }.coerceIn(1, players.size - 1)
        selectedSeekers.value = players.map { it.id }.shuffled().take(count).toSet()
    }

    fun start() {
        val state = uiState.value ?: return
        if (!state.canStart || isStarting.value) return
        isStarting.value = true
        viewModelScope.launch {
            try {
                sessionManager.start(state.players.filter { it.isSeeker }.map { it.id })
            } finally {
                isStarting.value = false
            }
        }
    }

    fun leave() {
        selectedSeekers.value = emptySet()
        closeInvites()
        invitesSentIn = null
        sessionManager.leave()
    }

    fun dismissError() = sessionManager.clearError()

    fun onLocationPermissionGranted() = sessionManager.onLocationPermissionGranted()

    /** A friend request to another player of the game (or accepting theirs). */
    fun addFriend(userId: UserId) = commands.execute({ social.sendFriendRequest(userId) })

    fun dismissMessage() = commands.dismiss()

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
        seekers: Set<PlayerId>,
        starting: Boolean,
        friends: FriendsResponse?,
        accountState: AccountState,
    ): LobbyUiState? {
        val snapshot = state.snapshot ?: return null
        val me = snapshot.me.playerId
        val players = snapshot.players.map { player ->
            LobbyPlayer(
                id = player.id,
                name = player.name,
                isMe = player.id == me,
                isHost = player.id == snapshot.hostId,
                // Selections of an earlier game don't match any id here.
                isSeeker = player.id in seekers,
                account = playerAccount(player, me, accountState, friends),
            )
        }
        val seekerCount = players.count { it.isSeeker }
        return LobbyUiState(
            gameId = snapshot.gameId,
            joinCode = snapshot.joinCode,
            players = players,
            isHost = snapshot.hostId == me,
            // At least one seeker and at least one hider.
            canStart = seekerCount in 1 until players.size,
            isStarting = starting,
            connectionStatus = state.connectionStatus,
            isSharingLocation = state.isSharingLocation,
            error = state.lastError,
            isBuildingRuleOff = snapshot.buildings == BuildingsState.UNAVAILABLE,
            // The server takes invitations from players who play with an account (logged in on this phone).
            canInvite = accountState.isLoggedIn && snapshot.players.any { it.id == me && it.userId != null },
            userIdsInGame = snapshot.players.mapNotNull { it.userId }.toSet(),
            zoneRadiusMeters = snapshot.settings.zone.initial.radiusMeters.roundToInt(),
            hidingMinutes = snapshot.settings.hidingSeconds.minutesRoundedUp(),
            seekingMinutes = snapshot.settings.seekingSeconds.minutesRoundedUp(),
        )
    }

    private fun Int.minutesRoundedUp(): Int = (this + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE

    private companion object {
        const val SECONDS_PER_MINUTE = 60
    }
}

data class LobbyUiState(
    val gameId: GameId,
    val joinCode: String,
    val players: List<LobbyPlayer>,
    val isHost: Boolean,
    val canStart: Boolean,
    val isStarting: Boolean,
    val connectionStatus: ConnectionStatus,
    val isSharingLocation: Boolean,
    val error: SessionError?,
    /** The server could not load the zone's buildings: the game will run without that rule. */
    val isBuildingRuleOff: Boolean,
    /** Playing with an account: friends and groups can be invited. */
    val canInvite: Boolean,
    /** Accounts already in the game: no need to invite them. */
    val userIdsInGame: Set<UserId>,
    /** The settings, shown as chips: the zone at the start, the hiding and seeking times. */
    val zoneRadiusMeters: Int,
    val hidingMinutes: Int,
    val seekingMinutes: Int,
)

data class LobbyPlayer(
    val id: PlayerId,
    val name: String,
    val isMe: Boolean,
    val isHost: Boolean,
    val isSeeker: Boolean,
    val account: PlayerAccount,
)
