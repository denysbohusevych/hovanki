package app.hovanki.client

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.account.AccountManager
import app.hovanki.client.account.AccountState
import app.hovanki.client.session.ConnectionStatus
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.session.SessionState
import app.hovanki.client.ui.common.LoadingScreen
import app.hovanki.client.ui.common.LocalLocationConsent
import app.hovanki.client.ui.common.LocationConsentLayer
import app.hovanki.client.ui.common.LocationConsentState
import app.hovanki.client.ui.common.ResumingScreen
import app.hovanki.client.ui.common.appSafeDrawingPadding
import app.hovanki.client.ui.game.GameScreen
import app.hovanki.client.ui.invite.InviteBanner
import app.hovanki.client.ui.lobby.LobbyScreen
import app.hovanki.client.ui.main.MainScreen
import app.hovanki.client.ui.results.ResultsScreen
import app.hovanki.client.ui.theme.HovankiTheme
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
        val locationConsent = remember { LocationConsentState() }
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            CompositionLocalProvider(LocalLocationConsent provides locationConsent) {
                Screen(state, account, onLeave = sessionManager::leave)
                LocationConsentLayer(locationConsent)
            }
        }
    }
}

@Composable
private fun Screen(state: SessionState, account: AccountState, onLeave: () -> Unit) {
    val snapshot = state.snapshot
    // The round draws its map (and the hider's code) under the system bars and keeps its HUD clear of them itself.
    val isRound = state.session != null &&
        (snapshot?.phase == GamePhase.HIDING || snapshot?.phase == GamePhase.SEEKING)
    // Edge to edge on both platforms: keep content clear of system bars, cutouts and the keyboard.
    Box(modifier = Modifier.fillMaxSize().then(if (isRound) Modifier else Modifier.appSafeDrawingPadding())) {
        when {
            // Not in a game: who is logged in decides. An unconfirmed email is no obstacle (confirming is offered on
            // the main screen).
            state.session == null -> when {
                !account.isRestored -> LoadingScreen()
                !account.isLoggedIn -> WelcomeScreen()
                else -> MainScreen()
            }

            // Started again during a game: checking with the server whether it is still on.
            snapshot == null && state.isResuming -> ResumingScreen(
                isReconnecting = state.connectionStatus == ConnectionStatus.RECONNECTING,
                onLeave = onLeave,
            )

            snapshot == null -> LoadingScreen()

            else -> when (snapshot.phase) {
                GamePhase.LOBBY -> LobbyScreen()
                GamePhase.HIDING, GamePhase.SEEKING -> GameScreen()
                GamePhase.FINISHED -> ResultsScreen(snapshot = snapshot)
            }
        }
        // Invitations into another game reach a player in a lobby or at the results too (not in a round: no
        // distractions there). Only accounts get invitations.
        val phase = snapshot?.phase
        if (account.isLoggedIn && (phase == GamePhase.LOBBY || phase == GamePhase.FINISHED)) {
            InviteBanner(modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}
