package app.hovanki.server.game

import app.hovanki.server.account.UserRepository
import app.hovanki.server.api.AuthenticatedUser
import app.hovanki.server.buildings.BuildingLoader
import app.hovanki.server.moderation.NewReport
import app.hovanki.server.moderation.ReportRepository
import app.hovanki.server.ratelimit.RateLimit
import app.hovanki.server.ratelimit.RateLimiter
import app.hovanki.server.social.InviteRegistry
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.ClaimCatchRequest
import app.hovanki.shared.protocol.ConfirmCatchRequest
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.SendChatRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.VoteRequest
import app.hovanki.shared.rules.RequestIds
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
    private val users: UserRepository,
    private val reports: ReportRepository,
    private val rateLimiter: RateLimiter,
    private val invites: InviteRegistry,
) {
    /**
     * A new game with the caller as its host. [user]: the caller's account (null: a guest), whose nickname is the
     * player's name; [CreateGameRequest.playerName] only names guests.
     */
    fun create(request: CreateGameRequest, user: AuthenticatedUser? = null): SessionResponse {
        val name = playerName(request.playerName, user)
        validate(request.settings)
        val now = clock.millis()
        val hostId = ids.playerId()
        var game: Game
        do {
            game = Game(ids.gameId(), ids.joinCode(), hostId, request.settings, now)
        } while (!registry.add(game))
        return synchronized(game) {
            game.addPlayer(hostId, name, now, user?.userId)
            loadBuildings(game)
            newSession(game, hostId, now)
        }
    }

    /** Buildings the zone will ever cover, loaded while the players gather (an instant fake one in tests). */
    fun buildings(caller: PlayerRef, gameId: GameId): BuildingsResponse {
        val game = gameOf(caller, gameId)
        return synchronized(game) { game.buildingsFor(caller.playerId, clock.millis()) }
    }

    /**
     * Joins the game of [JoinGameRequest.joinCode] as a new player, in the lobby only. [user]: the caller's account
     * (null: a guest). An account that already has a player in the game gets that player back instead, in any phase (a
     * reinstalled app, a second phone): a new token, and the player's old tokens stop working. So does a join request
     * sent again ([JoinGameRequest.requestId]: its answer got lost), guest or not. Either way, the account's invitation
     * into the game is answered.
     */
    fun join(request: JoinGameRequest, user: AuthenticatedUser? = null): SessionResponse {
        val requestId = request.requestId?.let(::validRequestId)
        // The nickname comes from the database, outside the game's lock: the game's other requests never wait for it.
        val name = playerName(request.playerName, user)
        val game = registry.findByJoinCode(request.joinCode.trim())
            ?: throw GameException(ErrorCode.NOT_FOUND, "No game with this code")
        val session = synchronized(game) {
            val now = clock.millis()
            game.advance(now)
            // The account's player, or the one this very join request created before its answer got lost.
            val returning = user?.let { game.playerOf(it.userId) } ?: requestId?.let(game::playerOfJoinRequest)
            val playerId = if (returning != null) {
                registry.revokeTokens(game.id, returning)
                returning
            } else {
                ids.playerId().also { game.addPlayer(it, name, now, user?.userId, requestId) }
            }
            newSession(game, playerId, now)
        }
        if (user != null) invites.removeInvitee(game.id, user.userId)
        return session
    }

    fun start(caller: PlayerRef, gameId: GameId, request: StartGameRequest): GameSnapshot =
        update(caller, gameId) { game, now ->
            game.start(caller.playerId, request.seekers.toSet(), ids::catchCodeSecret, now)
        }

    fun sync(caller: PlayerRef, gameId: GameId, request: SyncRequest): GameSnapshot {
        if (request.samples.size > MAX_SAMPLES_PER_SYNC) throw GameException(ErrorCode.BAD_REQUEST, "Too many samples")
        return update(caller, gameId, request.chatAfter) { game, now ->
            game.recordLocations(caller.playerId, request.samples, now)
        }
    }

    /** A chat message; the snapshot brings the messages after the request's cursor, this one included. */
    fun sendChat(caller: PlayerRef, gameId: GameId, request: SendChatRequest): GameSnapshot {
        val clientMessageId = request.clientMessageId?.let(::validRequestId)
        return update(caller, gameId, request.chatAfter) { game, now ->
            game.sendChat(caller.playerId, request.text, request.team, now, clientMessageId)
        }
    }

    /**
     * Reports chat message [seq] to the moderators ([Game.reportedMessage] says which ones can be). The message is
     * copied under the game's lock, the report is written after it is released: the game's other requests never wait
     * for the database. Reporting a message again changes nothing.
     */
    fun reportChat(caller: PlayerRef, gameId: GameId, seq: Long): GameSnapshot {
        val game = gameOf(caller, gameId)
        val (reported, snapshot) = synchronized(game) {
            val now = clock.millis()
            game.advance(now)
            game.reportedMessage(caller.playerId, seq) to game.snapshotFor(caller.playerId, now)
        }
        // Per account; guests have none, so per player.
        val reporter = reported.reporterUserId?.value ?: "${gameId.value}/${caller.playerId.value}"
        rateLimiter.acquire(RateLimit.REPORTS, reporter)
        val report = NewReport(
            gameId = gameId,
            messageSeq = seq,
            reporterPlayerId = caller.playerId,
            reporterUserId = reported.reporterUserId,
            reportedUserId = reported.senderUserId,
            reportedName = reported.senderName,
            text = reported.message.text,
        )
        reports.insert(report, clock.instant())
        return snapshot
    }

    fun claimCatch(caller: PlayerRef, gameId: GameId, request: ClaimCatchRequest): GameSnapshot =
        update(caller, gameId) { game, now -> game.claimCatch(caller.playerId, request.hiderId, ids.catchId(), now) }

    fun confirmCatch(caller: PlayerRef, gameId: GameId, catchId: CatchId, request: ConfirmCatchRequest): GameSnapshot =
        update(caller, gameId) { game, now -> game.confirmCatch(catchId, caller.playerId, request.code, now) }

    fun disputeCatch(caller: PlayerRef, gameId: GameId, catchId: CatchId): GameSnapshot =
        update(caller, gameId) { game, now -> game.disputeCatch(catchId, caller.playerId, now) }

    fun vote(caller: PlayerRef, gameId: GameId, catchId: CatchId, request: VoteRequest): GameSnapshot =
        update(caller, gameId) { game, now -> game.vote(catchId, caller.playerId, request.confirm, now) }

    /**
     * [block] with the caller's game under its lock, on its current state ([Game.advance] first), for the services that
     * combine a game with the database, like invitations. Never touch the database in [block]: the game's other
     * requests wait for it.
     */
    fun <T> withGame(caller: PlayerRef, gameId: GameId, block: (Game, Long) -> T): T {
        val game = gameOf(caller, gameId)
        return synchronized(game) {
            val now = clock.millis()
            game.advance(now)
            block(game, now)
        }
    }

    /** Whether [userId] can still join game [gameId] as a new player: it is in its lobby and has no player of theirs. */
    fun isOpenFor(gameId: GameId, userId: UserId): Boolean {
        val game = registry.get(gameId) ?: return false
        return synchronized(game) { game.phase == GamePhase.LOBBY && game.playerOf(userId) == null }
    }

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

    /** [action] under the game's lock, on the current state; the caller's snapshot with chat after [chatAfter]. */
    private fun update(
        caller: PlayerRef,
        gameId: GameId,
        chatAfter: Long? = null,
        action: (Game, Long) -> Unit,
    ): GameSnapshot = withGame(caller, gameId) { game, now ->
        action(game, now)
        game.advance(now)
        game.snapshotFor(caller.playerId, now, chatAfter)
    }

    private fun newSession(game: Game, playerId: PlayerId, now: Long): SessionResponse {
        val token = ids.token()
        registry.registerToken(token, PlayerRef(game.id, playerId))
        return SessionResponse(PlayerSession(game.id, playerId, token), game.snapshotFor(playerId, now))
    }

    /** The account's nickname for a logged-in player, else the name the guest typed. */
    private fun playerName(typed: String, user: AuthenticatedUser?): String {
        if (user == null) return validName(typed)
        // Deleted since its token was checked: as if the token was unknown.
        return users.findById(user.userId)?.nickname
            ?: throw GameException(ErrorCode.UNAUTHORIZED, "Log in again", ErrorReason.SESSION_EXPIRED)
    }

    private fun validName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH) {
            throw GameException(ErrorCode.BAD_REQUEST, "Name must be 1..$MAX_NAME_LENGTH characters")
        }
        return trimmed
    }

    private fun validRequestId(id: String): String {
        if (!RequestIds.isValid(id)) throw GameException(ErrorCode.BAD_REQUEST, "Invalid request id")
        return id
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
