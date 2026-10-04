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
 *
 * One state, [uiState], and one way in, [onEvent] (docs/architecture.md, «Состояние экрана»). Used by the banner and by
 * the round's «More» (`GameScreen`), which shows the invitation without a banner.
 */
class InviteBannerViewModel(
    private val sessionManager: GameSessionManager,
    private val social: SocialManager,
    private val account: AccountManager,
) : ViewModel() {
    private val commands = CommandRunner(viewModelScope)
    private val isJoining = MutableStateFlow(false)

    /** Collecting it polls the inbox. */
    val uiState: StateFlow<InviteBannerUiState> =
        combine(social.inbox, sessionManager.state, commands.isBusy, isJoining) { inbox, state, dismissing, joining ->
            val here = state.snapshot?.gameId
            InviteBannerUiState(
                invite = inbox.invites.filter { it.gameId != here }.maxByOrNull { it.createdAtMillis },
                isBusy = dismissing || joining,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InviteBannerUiState())

    fun onEvent(event: InviteBannerEvent) {
        when (event) {
            is InviteBannerEvent.Go -> go(event.invite, event.leaveRound)
            is InviteBannerEvent.Dismiss -> commands.execute({ social.dismissInvite(event.invite.id) })
        }
    }

    private fun go(invite: GameInvite, leaveRound: Boolean) {
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
}

data class InviteBannerUiState(
    /** The newest invitation into a game other than this one; null: none. */
    val invite: GameInvite? = null,
    /** Joining it or dismissing it is under way. */
    val isBusy: Boolean = false,
)

sealed interface InviteBannerEvent {
    /**
     * Joins the invitation's game; a refusal shows on the screen of the game the player is still in. [leaveRound]: the
     * player is in a round and chose to leave it for the invitation (a hider there is out).
     */
    data class Go(val invite: GameInvite, val leaveRound: Boolean = false) : InviteBannerEvent

    data class Dismiss(val invite: GameInvite) : InviteBannerEvent
}
