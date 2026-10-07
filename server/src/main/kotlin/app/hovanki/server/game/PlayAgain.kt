package app.hovanki.server.game

import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.UserId

/** The game the host of a finished one opened to play again ([Game.openPlayAgain]). */
data class NextGame(val gameId: GameId, val joinCode: String)

/** What «Play again» takes from the finished game ([Game.playAgainAsk]). */
data class PlayAgainAsk(
    val name: String,
    val userId: UserId?,
    val isHost: Boolean,
    /** The finished game's setup for the next one. */
    val settings: GameSettings,
    /** The next game, once the host opened it. */
    val next: NextGame?,
)
