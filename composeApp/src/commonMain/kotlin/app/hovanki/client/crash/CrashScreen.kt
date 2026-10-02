package app.hovanki.client.crash

import app.hovanki.client.account.AccountState
import app.hovanki.client.session.SessionState
import app.hovanki.shared.protocol.GamePhase

/**
 * The name of the screen `App` shows, for the trail of screens in a crash report (the only breadcrumbs the report
 * keeps). Mirrors `Screen` in `App.kt`; a closed set of plain words, never a game's code or a player's name.
 */
internal fun crashScreenName(state: SessionState, account: AccountState, isWatching: Boolean): String {
    val snapshot = state.snapshot
    return when {
        state.session == null -> when {
            !account.isRestored -> "loading"
            !account.isLoggedIn -> "welcome"
            isWatching -> "spectator"
            else -> "main"
        }

        snapshot == null && state.isResuming -> "resuming"

        snapshot == null -> "loading"

        else -> when (snapshot.phase) {
            GamePhase.LOBBY -> "lobby"
            GamePhase.HIDING, GamePhase.SEEKING -> "game"
            GamePhase.FINISHED -> "results"
        }
    }
}
