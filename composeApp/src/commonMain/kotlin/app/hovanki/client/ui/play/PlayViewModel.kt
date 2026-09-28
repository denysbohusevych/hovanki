package app.hovanki.client.ui.play

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.account.AccountManager
import app.hovanki.client.account.AccountState
import app.hovanki.client.automation.LaunchOptionsHolder
import app.hovanki.client.bigGames.BigGameManager
import app.hovanki.client.bigGames.BigGamesState
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The «Play» tab: create a game here, join one by its code, watch an open one by its code
 * (docs/adr/0011-spectators-and-recordings.md), accept an invite, or sign up for a big game.
 */
class PlayViewModel(
    sessionManager: GameSessionManager,
    private val account: AccountManager,
    private val social: SocialManager,
    private val bigGameManager: BigGameManager,
    private val spectator: SpectatorManager,
    launchOptions: LaunchOptionsHolder,
) : ViewModel() {
    private val starter = GameStarter(sessionManager, launchOptions, viewModelScope)
    private val commands = CommandRunner(viewModelScope)

    /** Compose state: text fields need synchronous updates. */
    var joinCode by mutableStateOf("")
        private set

    val accountState: StateFlow<AccountState> = account.state
    val startStatus: StateFlow<StartStatus> = starter.status

    /** The big games (docs/adr/0010-big-games.md); kept fresh while the tab shows them. */
    val bigGames: StateFlow<BigGamesState> = bigGameManager.state

    /** A sign-up on its way: its button waits. */
    val isSigningUp: StateFlow<Boolean> = commands.isBusy

    /** Dismissing an invite failed. */
    val message: StateFlow<FormMessage?> = commands.message
    val sessionError: StateFlow<SessionError?> = sessionManager.state
        .map { it.lastError }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), sessionManager.state.value.lastError)

    init {
        // Debug builds under UI automation: a join code from the start parameters (see LaunchOptions).
        viewModelScope.launch {
            launchOptions.options.filterNotNull().collect { options -> options.joinCode?.let(::onJoinCodeChange) }
        }
    }

    fun onJoinCodeChange(value: String) {
        joinCode = GameStarter.joinCodeInput(value)
    }

    /** After the location permission was asked for: the zone is centered on this phone. */
    fun createGame(locationGranted: Boolean) = starter.create(nickname(), locationGranted)

    /** Checks the code before the location permission is requested. */
    fun canJoinGame(): Boolean = starter.validate(joinCode.isNotBlank(), StartProblem.CODE_MISSING)

    fun joinGame() = starter.join(joinCode, nickname()) { joinCode = "" }

    /** Watches the open game of the code: no location needed, nothing is shared. */
    fun watchGame() {
        if (joinCode.isBlank()) {
            commands.show(Notice.Text(Res.string.problem_code_missing))
            return
        }
        commands.execute(
            command = { spectator.watch(joinCode) },
            onFailure = { result ->
                // No game with this code: the app's words, not the server's.
                if (result is ApiResult.Rejected && result.reason == null && result.code == ErrorCode.NOT_FOUND) {
                    commands.show(Notice.Text(Res.string.error_not_found))
                }
            },
        ) { joinCode = "" }
    }

    /** Accepting an invite is joining its game by code, logged in. */
    fun acceptInvite(invite: GameInvite) = starter.join(invite.joinCode, nickname())

    fun dismissInvite(invite: GameInvite) = commands.execute({ social.dismissInvite(invite.id) })

    fun signUp(game: BigGameCard) = commands.execute({ bigGameManager.signUp(game.id) })

    fun cancelSignup(game: BigGameCard) = commands.execute({ bigGameManager.cancelSignup(game.id) })

    /** Into the open lobby of a big game the player signed up for. */
    fun joinBigGame(game: BigGameCard) = starter.joinBigGame(game.id)

    /** The account still plays a round elsewhere: leave it, and create or join as just tried. */
    fun leaveOtherGameAndRetry() = starter.leaveOtherGameAndRetry()

    fun dismissProblems() {
        starter.dismissProblems()
        commands.dismiss()
    }

    /** The server names a logged-in player by their nickname anyway. */
    private fun nickname(): String = account.state.value.user?.nickname.orEmpty()
}
