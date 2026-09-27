package app.hovanki.client.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.account.AccountManager
import app.hovanki.client.account.AccountState
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.session.SessionState
import app.hovanki.client.social.SocialManager
import app.hovanki.client.ui.common.CommandRunner
import app.hovanki.client.ui.common.FormMessage
import app.hovanki.client.ui.common.PlayerAccount
import app.hovanki.client.ui.common.playerAccount
import app.hovanki.shared.protocol.FriendsResponse
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.TracksResponse
import app.hovanki.shared.protocol.UserId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The results screen: who played with an account, friend requests to them, and the tracks for the replay. The
 * standings and awards come from the final snapshot.
 */
class ResultsViewModel(
    private val sessionManager: GameSessionManager,
    private val social: SocialManager,
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

    /** Everybody's way through the round for the replay; null until loaded. */
    val tracks: StateFlow<TracksResponse?> = sessionManager.state.map { it.tracks }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), sessionManager.state.value.tracks)

    /** A friend request failed. */
    val message: StateFlow<FormMessage?> = commands.message
    val isBusy: StateFlow<Boolean> = commands.isBusy

    fun addFriend(userId: UserId) = commands.execute({ social.sendFriendRequest(userId) })

    fun dismissMessage() = commands.dismiss()

    /** Back to the start: the game is over for this phone (polling for the chat stops too). */
    fun leave() {
        commands.dismiss()
        sessionManager.leave()
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
