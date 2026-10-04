package app.hovanki.client.ui.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.network.ApiResult
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.friends_nickname_missing
import app.hovanki.client.resources.friends_now_friends
import app.hovanki.client.resources.friends_request_sent
import app.hovanki.client.social.SocialManager
import app.hovanki.client.ui.common.CommandRunner
import app.hovanki.client.ui.common.FormMessage
import app.hovanki.client.ui.common.Notice
import app.hovanki.client.ui.common.notice
import app.hovanki.shared.protocol.FriendsResponse
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserSummary
import app.hovanki.shared.rules.AccountRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * «Friends»: add a friend by nickname, answer requests, withdraw the player's own, remove or block friends, unblock.
 * Friends and requests from a game (lobby, results) come through the same [SocialManager].
 *
 * One state for the whole screen, [uiState], and one way in, [onEvent] (docs/architecture.md, «Состояние экрана»).
 * The nickname changes the state at once, on the caller's thread: a text field's edit is there before the next frame.
 */
class FriendsViewModel(private val social: SocialManager) : ViewModel() {
    private val commands = CommandRunner(viewModelScope)

    private val mutableUiState = MutableStateFlow(
        FriendsUiState(
            friends = social.friends.value,
            message = commands.message.value,
            isBusy = commands.isBusy.value,
        ),
    )
    val uiState: StateFlow<FriendsUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(social.friends, commands.message, commands.isBusy) { friends, message, busy ->
                Triple(friends, message, busy)
            }.collect { (friends, message, busy) ->
                mutableUiState.update { it.copy(friends = friends, message = message, isBusy = busy) }
            }
        }
    }

    fun onEvent(event: FriendsEvent) {
        when (event) {
            FriendsEvent.Refresh -> refresh()

            is FriendsEvent.NicknameChanged -> mutableUiState.update {
                it.copy(nickname = event.value.take(AccountRules.NICKNAME_MAX_LENGTH))
            }

            FriendsEvent.SendRequest -> sendRequest()

            is FriendsEvent.Accept -> perform { social.acceptFriendRequest(event.user.id) }

            is FriendsEvent.Decline -> perform { social.declineFriendRequest(event.user.id) }

            is FriendsEvent.Toggle -> mutableUiState.update {
                it.copy(expanded = if (it.expanded == event.user.id) null else event.user.id)
            }

            is FriendsEvent.Remove -> perform { social.removeFriend(event.user.id) }

            is FriendsEvent.Block -> perform { social.block(event.user.id) }

            is FriendsEvent.Unblock -> perform { social.unblock(event.user.id) }

            FriendsEvent.DismissMessage -> commands.dismiss()
        }
    }

    /** The tab is shown: requests may have come in or been answered meanwhile. Errors show only as a message. */
    private fun refresh() {
        viewModelScope.launch {
            val problem = social.refreshFriends().notice()
            if (problem != null && !commands.isBusy.value) commands.show(problem)
        }
    }

    /** A request by exact nickname; if they already asked the player, they are friends right away. */
    private fun sendRequest() {
        val name = uiState.value.nickname.trim()
        if (name.isEmpty()) {
            commands.show(Notice.Text(Res.string.friends_nickname_missing))
            return
        }
        commands.execute({ social.sendFriendRequest(name) }) {
            mutableUiState.update { it.copy(nickname = "") }
            val key = AccountRules.nicknameKey(name)
            val isFriend = social.friends.value?.friends.orEmpty().any { AccountRules.nicknameKey(it.nickname) == key }
            val text = if (isFriend) Res.string.friends_now_friends else Res.string.friends_request_sent
            commands.show(Notice.Text(text, listOf(name)), isError = false)
        }
    }

    private fun perform(command: suspend () -> ApiResult<Unit>) {
        commands.execute(command) { mutableUiState.update { it.copy(expanded = null) } }
    }
}

/** What «Friends» shows. */
data class FriendsUiState(
    /** Null until loaded. */
    val friends: FriendsResponse? = null,
    val nickname: String = "",
    /** The friend whose actions (remove, block) are shown; null: none. */
    val expanded: UserId? = null,
    val message: FormMessage? = null,
    val isBusy: Boolean = false,
)

sealed interface FriendsEvent {
    /** The tab is shown. */
    data object Refresh : FriendsEvent

    data class NicknameChanged(val value: String) : FriendsEvent

    data object SendRequest : FriendsEvent

    data class Accept(val user: UserSummary) : FriendsEvent

    /** Declines [user]'s request, or withdraws the player's own request to them. */
    data class Decline(val user: UserSummary) : FriendsEvent

    /** Shows or hides the actions for a friend. */
    data class Toggle(val user: UserSummary) : FriendsEvent

    data class Remove(val user: UserSummary) : FriendsEvent

    data class Block(val user: UserSummary) : FriendsEvent

    data class Unblock(val user: UserSummary) : FriendsEvent

    data object DismissMessage : FriendsEvent
}
