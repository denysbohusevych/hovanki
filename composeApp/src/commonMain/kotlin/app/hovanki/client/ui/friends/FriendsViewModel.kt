package app.hovanki.client.ui.friends

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * «Friends»: add a friend by nickname, answer requests, withdraw the player's own, remove or block friends, unblock.
 * Friends and requests from a game (lobby, results) come through the same [SocialManager].
 */
class FriendsViewModel(private val social: SocialManager) : ViewModel() {
    private val commands = CommandRunner(viewModelScope)

    /** Compose state: text fields need synchronous updates. */
    var nickname by mutableStateOf("")
        private set

    /** The friend whose actions (remove, block) are shown; null: none. */
    var expanded by mutableStateOf<UserId?>(null)
        private set

    /** Null until loaded. */
    val friends: StateFlow<FriendsResponse?> = social.friends
    val message: StateFlow<FormMessage?> = commands.message
    val isBusy: StateFlow<Boolean> = commands.isBusy

    /** The tab is shown: requests may have come in or been answered meanwhile. Errors show only as a message. */
    fun refresh() {
        viewModelScope.launch {
            val problem = social.refreshFriends().notice()
            if (problem != null && !commands.isBusy.value) commands.show(problem)
        }
    }

    fun onNicknameChange(value: String) {
        nickname = value.take(AccountRules.NICKNAME_MAX_LENGTH)
    }

    /** A request by exact nickname; if they already asked the player, they are friends right away. */
    fun sendRequest() {
        val name = nickname.trim()
        if (name.isEmpty()) {
            commands.show(Notice.Text(Res.string.friends_nickname_missing))
            return
        }
        commands.execute({ social.sendFriendRequest(name) }) {
            nickname = ""
            val key = AccountRules.nicknameKey(name)
            val isFriend = social.friends.value?.friends.orEmpty().any { AccountRules.nicknameKey(it.nickname) == key }
            val text = if (isFriend) Res.string.friends_now_friends else Res.string.friends_request_sent
            commands.show(Notice.Text(text, listOf(name)), isError = false)
        }
    }

    fun accept(user: UserSummary) = perform { social.acceptFriendRequest(user.id) }

    /** Declines [user]'s request, or withdraws the player's own request to them. */
    fun decline(user: UserSummary) = perform { social.declineFriendRequest(user.id) }

    /** Shows or hides the actions for a friend. */
    fun toggle(user: UserSummary) {
        expanded = if (expanded == user.id) null else user.id
    }

    fun remove(user: UserSummary) = perform { social.removeFriend(user.id) }

    fun block(user: UserSummary) = perform { social.block(user.id) }

    fun unblock(user: UserSummary) = perform { social.unblock(user.id) }

    fun dismissMessage() = commands.dismiss()

    private fun perform(command: suspend () -> ApiResult<Unit>) {
        commands.execute(command) { expanded = null }
    }
}
