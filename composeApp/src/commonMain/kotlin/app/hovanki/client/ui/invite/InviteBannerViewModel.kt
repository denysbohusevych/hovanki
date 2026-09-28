package app.hovanki.client.ui.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.account.AccountManager
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.social.SocialManager
import app.hovanki.client.ui.common.CommandRunner
import app.hovanki.shared.protocol.GameInvite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * An invitation into another game while the player is in a lobby or looks at the results: shown over the screen, so
 * it is not missed (the start screen has its own list). Going there joins that game; the server takes the account out
 * of the lobby it is in (one game at a time).
 */
class InviteBannerViewModel(
    private val sessionManager: GameSessionManager,
    private val social: SocialManager,
    private val account: AccountManager,
) : ViewModel() {
    private val commands = CommandRunner(viewModelScope)
    private val isJoining = MutableStateFlow(false)

    /** The newest invitation into a game other than this one; null: none. Collecting it polls the inbox. */
    val invite: StateFlow<GameInvite?> =
        combine(social.inbox, sessionManager.state) { inbox, state ->
            val here = state.snapshot?.gameId
            inbox.invites.filter { it.gameId != here }.maxByOrNull { it.createdAtMillis }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isBusy: StateFlow<Boolean> =
        combine(commands.isBusy, isJoining) { dismissing, joining -> dismissing || joining }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Joins the invitation's game; a refusal shows on the screen of the game the player is still in. [leaveRound]: the
     * player is in a round and chose to leave it for the invitation (a hider there is out).
     */
    fun go(invite: GameInvite, leaveRound: Boolean = false) {
        if (isJoining.value) return
        isJoining.value = true
        viewModelScope.launch {
            try {
                // The server names a logged-in player by their nickname anyway.
                val nickname = account.state.value.user?.nickname.orEmpty()
                sessionManager.join(invite.joinCode, nickname, leaveOtherGame = leaveRound)
            } finally {
                isJoining.value = false
            }
        }
    }

    fun dismiss(invite: GameInvite) = commands.execute({ social.dismissInvite(invite.id) })
}
