package app.hovanki.client

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.account.AccountManager
import app.hovanki.client.session.ConnectionStatus
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.ui.common.LoadingScreen
import app.hovanki.client.ui.common.ResumingScreen
import app.hovanki.client.ui.game.GameScreen
import app.hovanki.client.ui.lobby.LobbyScreen
import app.hovanki.client.ui.main.MainScreen
import app.hovanki.client.ui.results.ResultsScreen
import app.hovanki.client.ui.theme.HovankiTheme
import app.hovanki.client.ui.verify.VerifyEmailScreen
import app.hovanki.client.ui.welcome.WelcomeScreen
import app.hovanki.shared.protocol.GamePhase
import org.koin.compose.koinInject

/**
 * Root of the UI on both platforms. There is no navigation library: the screen is a function of the session and
 * account state, so it always matches the game (e.g. every phone switches to the game screen when the server starts
 * the round, and the welcome screen shows when the account session ends).
 */
@Composable
fun App() {
    HovankiTheme {
        val sessionManager = koinInject<GameSessionManager>()
        val accountManager = koinInject<AccountManager>()
        val state by sessionManager.state.collectAsStateWithLifecycle()
        val account by accountManager.state.collectAsStateWithLifecycle()
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Edge to edge on both platforms: keep content clear of system bars, cutouts and the keyboard.
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                val snapshot = state.snapshot
                when {
                    // Not in a game: who is logged in decides.
                    state.session == null -> when {
                        !account.isRestored -> LoadingScreen()
                        !account.isLoggedIn -> WelcomeScreen()
                        account.needsEmailVerification -> VerifyEmailScreen()
                        else -> MainScreen()
                    }

                    // Started again during a game: checking with the server whether it is still on.
                    snapshot == null && state.isResuming -> ResumingScreen(
                        isReconnecting = state.connectionStatus == ConnectionStatus.RECONNECTING,
                        onLeave = sessionManager::leave,
                    )

                    snapshot == null -> LoadingScreen()

                    else -> when (snapshot.phase) {
                        GamePhase.LOBBY -> LobbyScreen()
                        GamePhase.HIDING, GamePhase.SEEKING -> GameScreen()
                        GamePhase.FINISHED -> ResultsScreen(snapshot = snapshot)
                    }
                }
            }
        }
    }
}
