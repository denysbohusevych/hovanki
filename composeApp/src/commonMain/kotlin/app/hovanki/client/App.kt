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
import app.hovanki.client.session.ConnectionStatus
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.ui.common.LoadingScreen
import app.hovanki.client.ui.common.ResumingScreen
import app.hovanki.client.ui.game.GameScreen
import app.hovanki.client.ui.home.HomeScreen
import app.hovanki.client.ui.lobby.LobbyScreen
import app.hovanki.client.ui.results.ResultsScreen
import app.hovanki.client.ui.theme.HovankiTheme
import app.hovanki.shared.protocol.GamePhase
import org.koin.compose.koinInject

/**
 * Root of the UI on both platforms. There is no navigation library: the screen is a function of the session state,
 * so it always matches the game (e.g. every phone switches to the game screen when the server starts the round).
 */
@Composable
fun App() {
    HovankiTheme {
        val sessionManager = koinInject<GameSessionManager>()
        val state by sessionManager.state.collectAsStateWithLifecycle()
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Edge to edge on both platforms: keep content clear of system bars, cutouts and the keyboard.
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                val snapshot = state.snapshot
                when {
                    state.session == null -> HomeScreen()

                    // Started again during a game: checking with the server whether it is still on.
                    snapshot == null && state.isResuming -> ResumingScreen(
                        isReconnecting = state.connectionStatus == ConnectionStatus.RECONNECTING,
                        onLeave = sessionManager::leave,
                    )

                    snapshot == null -> LoadingScreen()

                    else -> when (snapshot.phase) {
                        GamePhase.LOBBY -> LobbyScreen()
                        GamePhase.HIDING, GamePhase.SEEKING -> GameScreen()
                        GamePhase.FINISHED -> ResultsScreen(snapshot = snapshot, onBackToStart = sessionManager::leave)
                    }
                }
            }
        }
    }
}
