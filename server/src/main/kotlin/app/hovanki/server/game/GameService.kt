package app.hovanki.server.game

import app.hovanki.server.account.UserRepository
import app.hovanki.server.api.AuthenticatedUser
import app.hovanki.server.buildings.BuildingLoader
import app.hovanki.server.history.HistoryWriter
import app.hovanki.server.map.StreetZoneLoader
import app.hovanki.server.moderation.NewReport
import app.hovanki.server.moderation.ReportRepository
import app.hovanki.server.moderation.SanctionService
import app.hovanki.server.ratelimit.RateLimit
import app.hovanki.server.ratelimit.RateLimiter
import app.hovanki.server.social.InviteRegistry
import app.hovanki.shared.protocol.AdminGame
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
import app.hovanki.shared.protocol.RolesRequest
import app.hovanki.shared.protocol.SendChatRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.SettingsRequest
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.StreetZoneResponse
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.TracksResponse
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.VoteRequest
import app.hovanki.shared.protocol.ZoneShape
import app.hovanki.shared.rules.RequestIds
import app.hovanki.shared.rules.SettingsLimits
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
    private val history: HistoryWriter,
    private val sanctions: SanctionService,
    private val streetZoneLoader: StreetZoneLoader,
) {
    /**
     * A new game with the caller as its host. [user]: the caller's account (null: a guest), whose nickname is the
     * player's name; [CreateGameRequest.playerName] only names guests.
     */
    fun create(request: CreateGameRequest, user: AuthenticatedUser? = null): SessionResponse {
        val name = playerName(request.playerName, user)
        validate(request.settings)
        if (user != null) leaveOtherGames(user, except = null, leaveRound = request.leaveOtherGame)
        val now = clock.millis()
        val hostId = ids.playerId()
        var game: Game
        do {
            game = Game(ids.gameId(), ids.joinCode(), hostId, request.settings, now)
        } while (!registry.add(game))
        return synchronized(game) {
            game.addPlayer(hostId, name, now, user?.userId)
            loadMap(game)
            newSession(game, hostId, now)
        }
    }

    /** Buildings the zone will ever cover, loaded while the players gather (an instant fake one in tests). */
    fun buildings(caller: PlayerRef, gameId: GameId): BuildingsResponse {
        val game = gameOf(caller, gameId)
        return synchronized(game) { game.buildingsFor(caller.playerId, clock.millis()) }
    }

    /** Every player's track of the round, for the replay: only once the game is over ([Game.tracks]). */
    fun tracks(caller: PlayerRef, gameId: GameId): TracksResponse =
        withGame(caller, gameId) { game, _ -> game.tracks() }

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
        if (user != null) leaveOtherGames(user, except = game.id, leaveRound = request.leaveOtherGame)
        val session = locked(game) { now ->
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

    /** The host picks the roles in the lobby, or has the server draw [RolesRequest.randomSeekers] seekers. */
    fun setRoles(caller: PlayerRef, gameId: GameId, request: RolesRequest): GameSnapshot =
        update(caller, gameId) { game, now ->
            val random = request.randomSeekers
            if (random != null) {
                game.drawRoles(caller.playerId, random, ids.drawRandom, now)
            } else {
                game.setRoles(caller.playerId, request.seekers.toSet(), now)
            }
        }

    /** The host changes the setup in the lobby; a new zone loads its buildings (and zone by streets) again. */
    fun updateSettings(caller: PlayerRef, gameId: GameId, request: SettingsRequest): GameSnapshot {
        validate(request.settings)
        var mapChanged = false
        val snapshot = update(caller, gameId) { game, now ->
            mapChanged = game.updateSettings(caller.playerId, request.settings, now)
        }
        if (mapChanged) registry.get(gameId)?.let { game -> synchronized(game) { loadMap(game) } }
        return snapshot
    }

    /**
     * The caller leaves the game for good ([Game.leave]); their token stops working. An empty lobby is removed with its
     * invitations (the janitor's next sweep).
     */
    fun leave(caller: PlayerRef, gameId: GameId) {
        val game = gameOf(caller, gameId)
        val empty = locked(game) { now -> game.leave(caller.playerId, now) }
        registry.revokeTokens(gameId, caller.playerId)
        if (empty) registry.removeIf { it.id == gameId }
    }

    /** The zone by streets, one polygon per stage: what the map draws and the rules check. */
    fun streetZone(caller: PlayerRef, gameId: GameId): StreetZoneResponse {
        val game = gameOf(caller, gameId)
        return synchronized(game) { game.streetZoneFor(caller.playerId) }
    }

    fun sync(caller: PlayerRef, gameId: GameId, request: SyncRequest): GameSnapshot {
        if (request.samples.size > MAX_SAMPLES_PER_SYNC) throw GameException(ErrorCode.BAD_REQUEST, "Too many samples")
        return update(caller, gameId, request.chatAfter) { game, now ->
            game.recordLocations(caller.playerId, request.samples, now)
        }
    }

    /**
     * A chat message; the snapshot brings the messages after the request's cursor, this one included. A player whose
     * account is banned or may not chat gets [ErrorReason.CHAT_MUTED] (docs/adr/0008-admin.md), checked in the database
     * before the game's lock.
     */
    fun sendChat(caller: PlayerRef, gameId: GameId, request: SendChatRequest): GameSnapshot {
        val clientMessageId = request.clientMessageId?.let(::validRequestId)
        val game = gameOf(caller, gameId)
        synchronized(game) { game.userIdOf(caller.playerId) }?.let(sanctions::checkCanChat)
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
        val (reported, snapshot) = locked(game) { now ->
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

    /** A claim; with [ClaimCatchRequest.code] (one scan of the hider's QR code), confirmed in the same step. */
    fun claimCatch(caller: PlayerRef, gameId: GameId, request: ClaimCatchRequest): GameSnapshot =
        update(caller, gameId) { game, now ->
            game.claimCatch(caller.playerId, request.hiderId, ids.catchId(), now, request.code)
        }

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
        return locked(game) { now -> block(game, now) }
    }

    /** Every game in memory as staff see it (docs/adr/0008-admin.md), newest first. */
    fun adminGames(): List<AdminGame> =
        registry.all().map { game -> locked(game) { game.adminView() } }.sortedByDescending { it.createdAtMillis }

    /**
     * Staff end game [gameId] (docs/adr/0008-admin.md): a started one finishes now, its players see the results; one
     * in the lobby is removed with its tokens. False: no such game.
     */
    fun endByStaff(gameId: GameId): Boolean {
        val game = registry.get(gameId) ?: return false
        val inLobby = locked(game) { now ->
            val lobby = game.phase == GamePhase.LOBBY
            if (!lobby) game.endNow(now)
            lobby
        }
        // Its invitations go with the janitor's next sweep.
        if (inLobby) registry.removeIf { it.id == gameId }
        return true
    }

    /** Whether [userId] can still join game [gameId] as a new player: it is in its lobby and has no player of theirs. */
    fun isOpenFor(gameId: GameId, userId: UserId): Boolean {
        val game = registry.get(gameId) ?: return false
        return synchronized(game) { game.phase == GamePhase.LOBBY && game.playerOf(userId) == null }
    }

    /**
     * An account plays in one game at a time: [user]'s players in the lobbies of other games leave them (a new host
     * takes over, an empty lobby goes). A round in progress is left only with [leaveRound], else the caller is refused
     * with [ErrorReason.IN_ANOTHER_GAME]. Finished games stay as they are. One game's lock at a time.
     */
    private fun leaveOtherGames(user: AuthenticatedUser, except: GameId?, leaveRound: Boolean) {
        for (other in registry.all()) {
            if (other.id == except) continue
            val left = locked(other) { now ->
                val playerId = other.playerOf(user.userId) ?: return@locked null
                when {
                    other.phase == GamePhase.LOBBY -> playerId to other.leave(playerId, now)

                    other.isPlaying(playerId) && !leaveRound -> throw GameException(
                        ErrorCode.WRONG_STATE,
                        "You are still playing another game: leave it first",
                        ErrorReason.IN_ANOTHER_GAME,
                    )

                    other.phase == GamePhase.FINISHED -> null

                    else -> playerId to other.leave(playerId, now)
                }
            } ?: continue
            val (playerId, empty) = left
            registry.revokeTokens(other.id, playerId)
            if (empty) registry.removeIf { it.id == other.id }
        }
    }

    /**
     * The buildings and, for a zone by streets, the streets of [game]'s zone at its current map revision (call under the
     * game's lock). Results of an older revision are dropped by the game.
     */
    private fun loadMap(game: Game) {
        val revision = game.mapRevision
        val settings = game.settings
        val area = settings.zone.boundingCircle(BUILDINGS_MARGIN_METERS)
        buildingLoader.load(game.id.value, area) { loaded ->
            // The game may be gone meanwhile (the janitor, a failed create).
            val current = registry.get(game.id) ?: return@load
            synchronized(current) {
                when (loaded) {
                    null -> current.onBuildingsUnavailable(revision)
                    else -> current.onBuildingsLoaded(loaded.buildings, loaded.passages, revision)
                }
            }
        }
        if (settings.zoneShape == ZoneShape.STREETS) {
            streetZoneLoader.load(game.id.value, settings.zone) { stages ->
                val current = registry.get(game.id) ?: return@load
                synchronized(current) {
                    when (stages) {
                        null -> current.onStreetZoneUnavailable(revision)
                        else -> current.onStreetZoneBuilt(stages, revision)
                    }
                }
            }
        }
    }

    /**
     * [block] under [game]'s lock, on its current state ([Game.advance] first). A game that finished meanwhile hands
     * over its history, which is saved after the lock is released, off the request thread ([HistoryWriter]): no request
     * ever waits for the database.
     */
    private fun <T> locked(game: Game, block: (now: Long) -> T): T {
        var finished: GameRecord? = null
        try {
            return synchronized(game) {
                val now = clock.millis()
                game.advance(now)
                try {
                    block(now)
                } finally {
                    finished = game.takeFinishedRecord()
                }
            }
        } finally {
            finished?.let(history::save)
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
        val problem = SettingsLimits.problem(settings)
        if (problem != null) throw GameException(ErrorCode.BAD_REQUEST, "Invalid game settings: $problem")
    }

    private companion object {
        const val MAX_NAME_LENGTH = 32
        const val MAX_SAMPLES_PER_SYNC = 100

        /** Buildings just outside the zone matter too: a player at the border can step into one. */
        const val BUILDINGS_MARGIN_METERS = 50.0
    }
}
