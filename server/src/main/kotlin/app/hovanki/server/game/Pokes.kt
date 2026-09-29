package app.hovanki.server.game

import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.PlayerId

/**
 * Whom a change of a game concerns (docs/adr/0015-websockets.md, section 5): the players whose phones are poked to sync
 * now instead of after their usual pause. [Game] collects them under its lock where its state changes in a way a player
 * should see soon, [GameService] hands them to the [PokeSink] after the lock is released. Positions moving are not a
 * poke: they come with every sync anyway.
 */
class Pokes {
    /** Every player of the game. */
    var everyone: Boolean = false
        private set

    private val players = HashSet<PlayerId>()

    val isEmpty: Boolean get() = !everyone && players.isEmpty()

    fun addEveryone() {
        everyone = true
        players.clear()
    }

    fun add(playerId: PlayerId) {
        if (!everyone) players += playerId
    }

    fun addAll(playerIds: Iterable<PlayerId>) {
        if (!everyone) players += playerIds
    }

    /** Whether [playerId]'s phone is to be poked. */
    fun concerns(playerId: PlayerId): Boolean = everyone || playerId in players

    override fun toString(): String =
        if (everyone) "everyone" else players.joinToString(prefix = "[", postfix = "]") { it.value }
}

/** Where the pokes of a game go: the open sockets of its players ([app.hovanki.server.live.GameSockets]). */
fun interface PokeSink {
    /** Pokes the phones of [gameId] that [pokes] names, except [except]'s: it has just got the fresh snapshot itself. */
    fun poke(gameId: GameId, pokes: Pokes, except: PlayerId?)
}
