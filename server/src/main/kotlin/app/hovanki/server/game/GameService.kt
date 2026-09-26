package app.hovanki.server.game

import app.hovanki.server.buildings.BuildingLoader
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.ClaimCatchRequest
import app.hovanki.shared.protocol.ConfirmCatchRequest
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.VoteRequest
import app.hovanki.shared.rules.boundingCircle
import org.springframework.stereotype.Service
import java.time.Clock

/** Application layer: auth checks, id generation and per-game locking around the [Game] domain object. */
@Service
class GameService(
    private val registry: GameRegistry,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val buildingLoader: BuildingLoader,
) {
    fun create(request: CreateGameRequest): SessionResponse {
        val name = validName(request.playerName)
        validate(request.settings)
        val now = clock.millis()
        val hostId = ids.playerId()
        var game: Game
        do {
            game = Game(ids.gameId(), ids.joinCode(), hostId, request.settings, now)
        } while (!registry.add(game))
        return synchronized(game) {
            game.addPlayer(hostId, name, now)
            loadBuildings(game)
            newSession(game, hostId, now)
        }
    }

    /** Buildings the zone will ever cover, loaded while the players gather (an instant fake one in tests). */
    fun buildings(caller: PlayerRef, gameId: GameId): BuildingsResponse {
        val game = gameOf(caller, gameId)
        return synchronized(game) { game.buildingsFor(caller.playerId, clock.millis()) }
    }

    fun join(request: JoinGameRequest): SessionResponse {
        val name = validName(request.playerName)
        val game = registry.findByJoinCode(request.joinCode.trim())
            ?: throw GameException(ErrorCode.NOT_FOUND, "No game with this code")
        return synchronized(game) {
            val now = clock.millis()
            game.advance(now)
            val playerId = ids.playerId()
            game.addPlayer(playerId, name, now)
            newSession(game, playerId, now)
        }
    }

    fun start(caller: PlayerRef, gameId: GameId, request: StartGameRequest): GameSnapshot =
        update(caller, gameId) { game, now ->
            game.start(caller.playerId, request.seekers.toSet(), ids::catchCodeSecret, now)
        }

    fun sync(caller: PlayerRef, gameId: GameId, request: SyncRequest): GameSnapshot {
        if (request.samples.size > MAX_SAMPLES_PER_SYNC) throw GameException(ErrorCode.BAD_REQUEST, "Too many samples")
        return update(caller, gameId) { game, now -> game.recordLocations(caller.playerId, request.samples, now) }
    }

    fun claimCatch(caller: PlayerRef, gameId: GameId, request: ClaimCatchRequest): GameSnapshot =
        update(caller, gameId) { game, now -> game.claimCatch(caller.playerId, request.hiderId, ids.catchId(), now) }

    fun confirmCatch(caller: PlayerRef, gameId: GameId, catchId: CatchId, request: ConfirmCatchRequest): GameSnapshot =
        update(caller, gameId) { game, now -> game.confirmCatch(catchId, caller.playerId, request.code, now) }

    fun disputeCatch(caller: PlayerRef, gameId: GameId, catchId: CatchId): GameSnapshot =
        update(caller, gameId) { game, now -> game.disputeCatch(catchId, caller.playerId, now) }

    fun vote(caller: PlayerRef, gameId: GameId, catchId: CatchId, request: VoteRequest): GameSnapshot =
        update(caller, gameId) { game, now -> game.vote(catchId, caller.playerId, request.confirm, now) }

    private fun loadBuildings(game: Game) {
        val area = game.settings.zone.boundingCircle(BUILDINGS_MARGIN_METERS)
        buildingLoader.load(game.id.value, area) { loaded ->
            // The game may be gone meanwhile (the janitor, a failed create).
            val current = registry.get(game.id) ?: return@load
            synchronized(current) {
                when (loaded) {
                    null -> current.onBuildingsUnavailable()
                    else -> current.onBuildingsLoaded(loaded.buildings, loaded.passages)
                }
            }
        }
    }

    private fun gameOf(caller: PlayerRef, gameId: GameId): Game {
        if (caller.gameId != gameId) throw GameException(ErrorCode.FORBIDDEN, "The token belongs to another game")
        return registry.get(gameId) ?: throw GameException(ErrorCode.NOT_FOUND, "The game is over or never existed")
    }

    private fun update(caller: PlayerRef, gameId: GameId, action: (Game, Long) -> Unit): GameSnapshot {
        val game = gameOf(caller, gameId)
        return synchronized(game) {
            val now = clock.millis()
            game.advance(now)
            action(game, now)
            game.advance(now)
            game.snapshotFor(caller.playerId, now)
        }
    }

    private fun newSession(game: Game, playerId: PlayerId, now: Long): SessionResponse {
        val token = ids.token()
        registry.registerToken(token, PlayerRef(game.id, playerId))
        return SessionResponse(PlayerSession(game.id, playerId, token), game.snapshotFor(playerId, now))
    }

    private fun validName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH) {
            throw GameException(ErrorCode.BAD_REQUEST, "Name must be 1..$MAX_NAME_LENGTH characters")
        }
        return trimmed
    }

    private fun validate(settings: GameSettings) {
        val zone = settings.zone
        val valid = zone.initial.radiusMeters > 0 &&
            zone.stages.all { it.holdSeconds >= 0 && it.shrinkSeconds >= 0 && it.target.radiusMeters > 0 } &&
            settings.hidingSeconds >= 0 && settings.seekingSeconds > 0
        if (!valid) throw GameException(ErrorCode.BAD_REQUEST, "Invalid game settings")
    }

    private companion object {
        const val MAX_NAME_LENGTH = 32
        const val MAX_SAMPLES_PER_SYNC = 100

        /** Buildings just outside the zone matter too: a player at the border can step into one. */
        const val BUILDINGS_MARGIN_METERS = 50.0
    }
}
