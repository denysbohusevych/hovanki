package app.hovanki.shared.protocol

/**
 * HTTP API paths. The server maps the templates, the client builds concrete paths with the functions.
 * All mutating calls return the fresh [GameSnapshot] so the UI does not wait for the next poll.
 */
object ApiRoutes {
    const val GAMES = "/api/v1/games"
    const val JOIN = "$GAMES/join"
    const val START = "$GAMES/{gameId}/start"
    const val SYNC = "$GAMES/{gameId}/sync"
    const val CATCHES = "$GAMES/{gameId}/catches"
    const val CATCH_CONFIRM = "$CATCHES/{catchId}/confirm"
    const val CATCH_DISPUTE = "$CATCHES/{catchId}/dispute"
    const val CATCH_VOTE = "$CATCHES/{catchId}/vote"

    const val AUTH_SCHEME = "Bearer"

    fun start(gameId: GameId): String = START.fill(gameId)

    fun sync(gameId: GameId): String = SYNC.fill(gameId)

    fun catches(gameId: GameId): String = CATCHES.fill(gameId)

    fun catchConfirm(gameId: GameId, catchId: CatchId): String = CATCH_CONFIRM.fill(gameId, catchId)

    fun catchDispute(gameId: GameId, catchId: CatchId): String = CATCH_DISPUTE.fill(gameId, catchId)

    fun catchVote(gameId: GameId, catchId: CatchId): String = CATCH_VOTE.fill(gameId, catchId)

    private fun String.fill(gameId: GameId, catchId: CatchId? = null): String {
        val withGame = replace("{gameId}", gameId.value)
        return if (catchId == null) withGame else withGame.replace("{catchId}", catchId.value)
    }
}
