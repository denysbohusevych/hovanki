package app.hovanki.client.ui.play

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.account.AccountManager
import app.hovanki.client.automation.LaunchOptionsHolder
import app.hovanki.client.bigGames.BigGameManager
import app.hovanki.client.network.ApiResult
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.error_not_found
import app.hovanki.client.resources.problem_code_missing
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.session.SessionError
import app.hovanki.client.social.SocialManager
import app.hovanki.client.spectator.SpectatorManager
import app.hovanki.client.ui.common.CommandRunner
import app.hovanki.client.ui.common.FormMessage
import app.hovanki.client.ui.common.GameStarter
import app.hovanki.client.ui.common.Notice
import app.hovanki.client.ui.common.StartProblem
import app.hovanki.client.ui.common.StartStatus
import app.hovanki.shared.protocol.BigGameCard
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameInvite
import app.hovanki.shared.protocol.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * The «Play» tab: create a game here, join one by its code, watch an open one by its code
 * (docs/adr/0011-spectators-and-recordings.md), accept an invite, or sign up for a big game.
 *
 * One state, [uiState], and one way in, [onEvent] (docs/architecture.md, «Состояние экрана»). The join code changes
 * the state at once, on the caller's thread: a text field's edit is there before the next frame.
 */
class PlayViewModel(
    private val sessionManager: GameSessionManager,
    private val account: AccountManager,
    private val social: SocialManager,
    private val bigGameManager: BigGameManager,
    private val spectator: SpectatorManager,
    launchOptions: LaunchOptionsHolder,
) : ViewModel() {
    private val starter = GameStarter(sessionManager, launchOptions, viewModelScope)
    private val commands = CommandRunner(viewModelScope)

    private var joinCode = ""
    private var inputs = currentInputs()
    private val mutableUiState = MutableStateFlow(inputs.build(joinCode))
    val uiState: StateFlow<PlayUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            val commandState = combine(commands.message, commands.isBusy) { message, busy -> message to busy }
            combine(
                account.state,
                starter.status,
                bigGameManager.state,
                sessionManager.state,
                commandState,
            ) { accountState, status, bigGames, session, (message, busy) ->
                PlayInputs(accountState.user, status, session.lastError, bigGames.games, busy, message)
            }.collect {
                inputs = it
                publish()
            }
        }
        // Debug builds under UI automation: a join code from the start parameters (see LaunchOptions).
        viewModelScope.launch {
            launchOptions.options.filterNotNull().collect { options -> options.joinCode?.let(::editJoinCode) }
        }
    }

    fun onEvent(event: PlayEvent) {
        when (event) {
            is PlayEvent.EditJoinCode -> editJoinCode(event.value)

            is PlayEvent.CreateGame -> starter.create(nickname(), event.locationGranted)

            PlayEvent.CheckJoinCode -> starter.validate(joinCode.isNotBlank(), StartProblem.CODE_MISSING)

            PlayEvent.JoinGame -> starter.join(joinCode, nickname()) { editJoinCode("") }

            PlayEvent.WatchGame -> watchGame()

            is PlayEvent.AcceptInvite -> starter.join(event.invite.joinCode, nickname())

            is PlayEvent.DismissInvite -> commands.execute({ social.dismissInvite(event.invite.id) })

            is PlayEvent.SignUp -> commands.execute({ bigGameManager.signUp(event.game.id) })

            is PlayEvent.CancelSignup -> commands.execute({ bigGameManager.cancelSignup(event.game.id) })

            is PlayEvent.JoinBigGame -> starter.joinBigGame(event.game.id)

            PlayEvent.LeaveOtherGameAndRetry -> starter.leaveOtherGameAndRetry()

            PlayEvent.DismissProblems -> {
                starter.dismissProblems()
                commands.dismiss()
            }
        }
    }

    private fun currentInputs() = PlayInputs(
        user = account.state.value.user,
        startStatus = starter.status.value,
        sessionError = sessionManager.state.value.lastError,
        bigGames = bigGameManager.state.value.games,
        isSigningUp = commands.isBusy.value,
        message = commands.message.value,
    )

    private fun publish() {
        mutableUiState.value = inputs.build(joinCode)
    }

    private fun editJoinCode(value: String) {
        joinCode = GameStarter.joinCodeInput(value)
        publish()
    }

    /** Watches the open game of the code: no location needed, nothing is shared. */
    private fun watchGame() {
        val code = joinCode
        if (code.isBlank()) {
            commands.show(Notice.Text(Res.string.problem_code_missing))
            return
        }
        commands.execute(
            command = { spectator.watch(code) },
            onFailure = { result ->
                // No game with this code: the app's words, not the server's.
                if (result is ApiResult.Rejected && result.reason == null && result.code == ErrorCode.NOT_FOUND) {
                    commands.show(Notice.Text(Res.string.error_not_found))
                }
            },
        ) { editJoinCode("") }
    }

    /** The server names a logged-in player by their nickname anyway. */
    private fun nickname(): String = account.state.value.user?.nickname.orEmpty()
}

/** Everything «Play» shows (docs/architecture.md, «Состояние экрана»). */
data class PlayUiState(
    /** The logged-in player; null for a moment while logging out. */
    val user: UserProfile? = null,
    val joinCode: String = "",
    /** A code is typed: joining asks for the location permission first. */
    val canJoin: Boolean = false,
    val startStatus: StartStatus = StartStatus(),
    val sessionError: SessionError? = null,
    /** The big games (docs/adr/0010-big-games.md); kept fresh while the tab shows them. */
    val bigGames: List<BigGameCard> = emptyList(),
    /** A sign-up (or another account command) on its way: its button waits. */
    val isSigningUp: Boolean = false,
    /** Dismissing an invite or a sign-up failed, or watching found no game. */
    val message: FormMessage? = null,
)

sealed interface PlayEvent {
    data class EditJoinCode(val value: String) : PlayEvent

    /** After the location permission was asked for: the zone is centered on this phone. */
    data class CreateGame(val locationGranted: Boolean) : PlayEvent

    /**
     * «Join», before the location permission is asked: shows a missing code. The screen asks for the permission only
     * when [PlayUiState.canJoin], then sends [JoinGame].
     */
    data object CheckJoinCode : PlayEvent

    data object JoinGame : PlayEvent

    data object WatchGame : PlayEvent

    /** Accepting an invite is joining its game by code, logged in. */
    data class AcceptInvite(val invite: GameInvite) : PlayEvent

    data class DismissInvite(val invite: GameInvite) : PlayEvent

    data class SignUp(val game: BigGameCard) : PlayEvent

    data class CancelSignup(val game: BigGameCard) : PlayEvent

    /** Into the open lobby of a big game the player signed up for. */
    data class JoinBigGame(val game: BigGameCard) : PlayEvent

    /** The account still plays a round elsewhere: leave it, and create or join as just tried. */
    data object LeaveOtherGameAndRetry : PlayEvent

    data object DismissProblems : PlayEvent
}

private data class PlayInputs(
    val user: UserProfile?,
    val startStatus: StartStatus,
    val sessionError: SessionError?,
    val bigGames: List<BigGameCard>,
    val isSigningUp: Boolean,
    val message: FormMessage?,
) {
    fun build(joinCode: String) = PlayUiState(
        user = user,
        joinCode = joinCode,
        canJoin = joinCode.isNotBlank(),
        startStatus = startStatus,
        sessionError = sessionError,
        bigGames = bigGames,
        isSigningUp = isSigningUp,
        message = message,
    )
}
