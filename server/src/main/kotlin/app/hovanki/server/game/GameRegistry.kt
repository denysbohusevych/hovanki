package app.hovanki.server.game

import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.SpectatorId
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

data class PlayerRef(val gameId: GameId, val playerId: PlayerId)

/** Whom a spectator token belongs to (docs/adr/0011-spectators-and-recordings.md). */
data class SpectatorRef(val gameId: GameId, val spectatorId: SpectatorId)

/**
 * In-memory storage of running games. Enough for the MVP (one instance, games last an hour);
 * swap for Redis/Postgres behind the same methods when the server needs to scale out or survive restarts.
 */
@Component
class GameRegistry {
    private val games = ConcurrentHashMap<GameId, Game>()
    private val gamesByJoinCode = ConcurrentHashMap<String, GameId>()
    private val playersByToken = ConcurrentHashMap<String, PlayerRef>()
    private val spectatorsByToken = ConcurrentHashMap<String, SpectatorRef>()

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

    /** Every token of [playerId] in [gameId] stops working (the player came back on another device). */
    fun revokeTokens(gameId: GameId, playerId: PlayerId) {
        val ref = PlayerRef(gameId, playerId)
        playersByToken.values.removeIf { it == ref }
    }

    fun registerSpectatorToken(token: String, ref: SpectatorRef) {
        spectatorsByToken[token] = ref
    }

    fun resolveSpectatorToken(token: String): SpectatorRef? = spectatorsByToken[token]

    /** Every token of these spectators of [gameId] stops working (they stopped watching, the game closed). */
    fun revokeSpectatorTokens(gameId: GameId, spectatorIds: Collection<SpectatorId>) {
        val refs = spectatorIds.mapTo(HashSet()) { SpectatorRef(gameId, it) }
        spectatorsByToken.values.removeIf { it in refs }
    }

    /** Removes games matching [predicate] together with their join codes and tokens. */
    fun removeIf(predicate: (Game) -> Boolean): Int {
        val removed = games.values.filter(predicate)
        for (game in removed) {
            games.remove(game.id)
            gamesByJoinCode.remove(game.joinCode)
        }
        val removedIds = removed.mapTo(HashSet()) { it.id }
        playersByToken.values.removeIf { it.gameId in removedIds }
        spectatorsByToken.values.removeIf { it.gameId in removedIds }
        return removed.size
    }

    fun size(): Int = games.size

    /**
     * How many players the games in memory have, without a game's lock: every player holds one token, dropped when they
     * leave, come back on another phone or the game is removed. For the metrics.
     */
    fun playerCount(): Int = playersByToken.size
}
