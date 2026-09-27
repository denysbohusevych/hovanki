package app.hovanki.client.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.account.AccountManager
import app.hovanki.client.account.AccountState
import app.hovanki.client.history.HistoryManager
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.session.SessionState
import app.hovanki.client.social.SocialManager
import app.hovanki.client.ui.common.CommandRunner
import app.hovanki.client.ui.common.FormMessage
import app.hovanki.client.ui.common.PlayerAccount
import app.hovanki.client.ui.common.playerAccount
import app.hovanki.shared.protocol.FriendsResponse
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.UserId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The results screen: who played with an account, friend requests to them, and keeping the player's route. The
 * standings need no state.
 */
class ResultsViewModel(
    private val sessionManager: GameSessionManager,
    private val social: SocialManager,
    private val history: HistoryManager,
    account: AccountManager,
) : ViewModel() {
    private val commands = CommandRunner(viewModelScope)

    /** The players of the finished game as far as friends go, by player. */
    val accounts: StateFlow<Map<PlayerId, PlayerAccount>> =
        combine(sessionManager.state, social.friends, account.state) { state, friends, accountState ->
            playerAccounts(state, friends, accountState)
        }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                playerAccounts(sessionManager.state.value, social.friends.value, account.state.value),
            )

    /**
     * Whether the player keeps their routes; null: this game has no history of theirs (played as a guest, or logged in
     * as someone else since).
     */
    val saveRoutes: StateFlow<Boolean?> =
        combine(sessionManager.state, account.state) { state, accountState -> saveRoutes(state, accountState) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                saveRoutes(sessionManager.state.value, account.state.value),
            )

    /** A friend request failed. */
    val message: StateFlow<FormMessage?> = commands.message
    val isBusy: StateFlow<Boolean> = commands.isBusy

    fun addFriend(userId: UserId) = commands.execute({ social.sendFriendRequest(userId) })

    /** «Save my routes» on: from now on, and this game's route too (the server still has the game). */
    fun turnOnSaveRoutes() = commands.execute({ history.setSaveRoutes(true) })

    fun dismissMessage() = commands.dismiss()

    /** Back to the start: the game is over for this phone (polling for the chat stops too). */
    fun leave() {
        commands.dismiss()
        sessionManager.leave()
    }

    private fun saveRoutes(state: SessionState, accountState: AccountState): Boolean? {
        val user = accountState.user ?: return null
        val snapshot = state.snapshot ?: return null
        val me = snapshot.players.firstOrNull { it.id == snapshot.me.playerId }
        return user.saveRoutes.takeIf { me?.userId == user.id }
    }

    private fun playerAccounts(
        state: SessionState,
        friends: FriendsResponse?,
        accountState: AccountState,
    ): Map<PlayerId, PlayerAccount> {
        val snapshot = state.snapshot ?: return emptyMap()
        return snapshot.players.associate { it.id to playerAccount(it, snapshot.me.playerId, accountState, friends) }
    }
}
