package app.hovanki.server.game

import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.PlayerId
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

data class PlayerRef(val gameId: GameId, val playerId: PlayerId)

/**
 * In-memory storage of running games. Enough for the MVP (one instance, games last an hour);
 * swap for Redis/Postgres behind the same methods when the server needs to scale out or survive restarts.
 */
@Component
class GameRegistry {
    private val games = ConcurrentHashMap<GameId, Game>()
    private val gamesByJoinCode = ConcurrentHashMap<String, GameId>()
    private val playersByToken = ConcurrentHashMap<String, PlayerRef>()

    fun add(game: Game): Boolean {
        if (gamesByJoinCode.putIfAbsent(game.joinCode, game.id) != null) return false
        games[game.id] = game
        return true
    }

    fun get(id: GameId): Game? = games[id]

    fun all(): List<Game> = games.values.toList()

    fun findByJoinCode(joinCode: String): Game? = gamesByJoinCode[joinCode.uppercase()]?.let(games::get)

    fun registerToken(token: String, ref: PlayerRef) {
        playersByToken[token] = ref
    }

    fun resolveToken(token: String): PlayerRef? = playersByToken[token]

    /** Removes games matching [predicate] together with their join codes and tokens. */
    fun removeIf(predicate: (Game) -> Boolean): Int {
        val removed = games.values.filter(predicate)
        for (game in removed) {
            games.remove(game.id)
            gamesByJoinCode.remove(game.joinCode)
        }
        val removedIds = removed.mapTo(HashSet()) { it.id }
        playersByToken.values.removeIf { it.gameId in removedIds }
        return removed.size
    }

    fun size(): Int = games.size
}
