package app.hovanki.server.bigGames

import app.hovanki.server.admin.AuditLog
import app.hovanki.server.admin.Staff
import app.hovanki.server.api.AuthenticatedUser
import app.hovanki.server.game.CapacityProperties
import app.hovanki.server.game.GameException
import app.hovanki.server.game.GameService
import app.hovanki.server.game.IdGenerator
import app.hovanki.server.map.TerrainLoader
import app.hovanki.shared.protocol.AdminAction
import app.hovanki.shared.protocol.AdminBigGame
import app.hovanki.shared.protocol.AdminBigGameRequest
import app.hovanki.shared.protocol.AdminBigGames
import app.hovanki.shared.protocol.AdminLimits
import app.hovanki.shared.protocol.AdminZoneEstimate
import app.hovanki.shared.protocol.AdminZoneEstimateRequest
import app.hovanki.shared.protocol.AreaNorms
import app.hovanki.shared.protocol.BigGameCard
import app.hovanki.shared.protocol.BigGameId
import app.hovanki.shared.protocol.BigGameInfo
import app.hovanki.shared.protocol.BigGameStatus
import app.hovanki.shared.protocol.BigGamesResponse
import app.hovanki.shared.protocol.CapacityState
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.JoinBigGameRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.TerrainAreas
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.rules.BigGameLimits
import app.hovanki.shared.rules.Capacity
import app.hovanki.shared.rules.ZoneArea
import app.hovanki.shared.rules.areaSquareMeters
import app.hovanki.shared.rules.settings
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/**
 * Big games (docs/adr/0010-big-games.md). Admins schedule them (every change with a reason in the audit log, in the same
 * transaction); players sign up ahead, up to the limit; the lobby opens [BigGameProperties.lobbyOpensBefore] before
 * the start, hosted by the server, and the round starts on time ([tick]) or when an admin says. The schedule and the
 * sign-ups are in the database; the round is in memory like every game, and a lobby lost with a restart opens again.
 * The account of the admin running a big game can't play in it.
 */
@Service
class BigGameService(
    private val repository: BigGameRepository,
    private val games: GameService,
    private val terrain: TerrainLoader,
    private val audit: AuditLog,
    private val ids: IdGenerator,
    private val capacity: CapacityProperties,
    private val properties: BigGameProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transactions = TransactionTemplate(transactionManager)

    // Admins

    fun list(staff: Staff): AdminBigGames {
        requireAdmin(staff)
        val records = repository.latest(PAGE_SIZE)
        val counts = repository.counts(records.map { it.id })
        return AdminBigGames(
            games = records.map { toAdmin(it, counts[it.id] ?: 0) },
            norms = capacity.norms(),
            maxPlayers = BigGameLimits.MAX_PLAYERS,
        )
    }

    /** The area of [request]'s zone and how many players it fits: the page shows it while the admin draws. */
    fun estimate(staff: Staff, request: AdminZoneEstimateRequest): AdminZoneEstimate {
        requireAdmin(staff)
        val zone = DrawnZone.of(request.zone) ?: throw badRequest("The zone is no figure: it crosses itself")
        val norms = request.norms?.also { if (!Capacity.isValid(it)) throw badRequest("A norm is out of range") }
            ?: capacity.norms()
        val estimate = estimate(zone, norms)
        return AdminZoneEstimate(
            areaSquareMeters = zone.outline.areaSquareMeters().roundToLong(),
            state = if (estimate.areas != null) CapacityState.READY else CapacityState.UNAVAILABLE,
            capacity = estimate.capacity,
            areas = estimate.areas,
            fewCovers = estimate.areas?.let(Capacity::fewCovers) == true,
        )
    }

    fun create(staff: Staff, request: AdminBigGameRequest): AdminBigGame {
        requireAdmin(staff)
        val reason = validReason(request.reason)
        val now = clock.instant()
        val valid = validate(request, now, startMayBePast = false)
        val estimate = estimate(valid.zone, valid.norms)
        val limit = limitOf(request, estimate.capacity)
        val record = BigGameRecord(
            id = BigGameId(ids.gameId().value),
            title = request.title.trim(),
            status = BigGameStatus.SCHEDULED,
            startsAt = valid.startsAt,
            timeZone = request.timeZone,
            zone = valid.zone.outline,
            setup = request.setup,
            norms = valid.norms,
            capacity = estimate.capacity,
            areas = estimate.areas,
            playerLimit = limit,
            gameId = null,
            hostUserId = staff.userId,
            createdBy = staff.nickname,
            createdAt = now,
            updatedAt = now,
        )
        transactions.executeWithoutResult {
            repository.insert(record)
            audit.record(staff, AdminAction.BIG_GAME_CREATE, now, target = describe(record), reason = reason)
        }
        advance(record.id)
        return admin(record.id)
    }

    /**
     * Title, time, place, setup, norms or limit, before the round. An open lobby follows: its players see the new zone
     * and time. An interrupted big game scheduled again waits for its new start.
     */
    fun update(staff: Staff, id: BigGameId, request: AdminBigGameRequest): AdminBigGame {
        requireAdmin(staff)
        val reason = validReason(request.reason)
        val now = clock.instant()
        val current = repository.find(id) ?: throw notFound()
        val valid = validate(request, now, startMayBePast = current.startsAt == startOf(request))
        val estimate = if (valid.zone.outline == current.zone && valid.norms == current.norms) {
            Estimate(current.capacity, current.areas)
        } else {
            estimate(valid.zone, valid.norms)
        }
        val limit = limitOf(request, estimate.capacity)
        val updated = transactions.execute {
            val locked = repository.findForUpdate(id) ?: throw notFound()
            if (locked.status !in EDITABLE) throw wrongState("The round has started or the game is over")
            val signedUp = repository.signedUp(id)
            if (limit < signedUp) throw wrongState("$signedUp players signed up: the limit can't be lower")
            val next = locked.copy(
                title = request.title.trim(),
                status = if (locked.status == BigGameStatus.INTERRUPTED) BigGameStatus.SCHEDULED else locked.status,
                startsAt = valid.startsAt,
                timeZone = request.timeZone,
                zone = valid.zone.outline,
                setup = request.setup,
                norms = valid.norms,
                capacity = estimate.capacity,
                areas = estimate.areas,
                playerLimit = limit,
                endedAt = null,
                updatedAt = now,
            )
            repository.update(next)
            audit.record(staff, AdminAction.BIG_GAME_UPDATE, now, target = describe(next), reason = reason)
            val zoneChanged = locked.zone != next.zone || locked.setup != next.setup
            next.also { if (it.status == BigGameStatus.LOBBY) refreshLobby(it, signedUp, zoneChanged) }
        }!!
        advance(updated.id)
        return admin(id)
    }

    /** The round starts now, with the players in the lobby. */
    fun start(staff: Staff, id: BigGameId, reason: String): AdminBigGame {
        requireAdmin(staff)
        val why = validReason(reason)
        val now = clock.instant()
        transactions.executeWithoutResult {
            val record = repository.findForUpdate(id) ?: throw notFound()
            if (record.status != BigGameStatus.LOBBY) throw wrongState("The lobby is not open")
            val gameId = record.gameId ?: throw wrongState("The lobby is not open")
            if (!games.startBigGame(gameId, record.setup.seekers)) {
                throw wrongState("Fewer than two players in the lobby")
            }
            repository.update(record.copy(status = BigGameStatus.RUNNING, updatedAt = now))
            audit.record(staff, AdminAction.BIG_GAME_START, now, target = describe(record), reason = why)
        }
        return admin(id)
    }

    /** Called off; a round going on ends now (its players see the results), an open lobby closes. */
    fun cancel(staff: Staff, id: BigGameId, reason: String): AdminBigGame {
        requireAdmin(staff)
        val why = validReason(reason)
        val now = clock.instant()
        transactions.executeWithoutResult {
            val record = repository.findForUpdate(id) ?: throw notFound()
            if (record.status !in EDITABLE + BigGameStatus.RUNNING) throw wrongState("The game is over")
            record.gameId?.let(games::endByStaff)
            repository.update(record.copy(status = BigGameStatus.CANCELLED, updatedAt = now, endedAt = now))
            audit.record(staff, AdminAction.BIG_GAME_CANCEL, now, target = describe(record), reason = why)
        }
        return admin(id)
    }

    // Players

    /** The big games ahead and going on, soonest first, with the caller's part in them. */
    fun cards(user: AuthenticatedUser): BigGamesResponse = BigGamesResponse(cards(user.userId, repository.open()))

    /** The open lobbies of the big games [userId] signed up for: the inbox shows them as invitations. */
    fun openLobbiesFor(userId: UserId): List<BigGameCard> =
        cards(userId, repository.open().filter { it.status == BigGameStatus.LOBBY }).filter { it.canJoin }

    /** Signs the caller up, while there is room and the round has not started. Signing up again changes nothing. */
    fun signUp(user: AuthenticatedUser, id: BigGameId): BigGameCard {
        val now = clock.instant()
        val signedUp = transactions.execute {
            val record = repository.findForUpdate(id)?.takeIf { it.status.isOpen } ?: throw notFound()
            if (record.status == BigGameStatus.RUNNING) throw wrongState("The round has started")
            requireNotHost(record, user.userId)
            val count = repository.signedUp(id)
            if (!repository.isSignedUp(id, user.userId)) {
                if (count >= record.playerLimit) {
                    throw GameException(ErrorCode.WRONG_STATE, "The game is full", ErrorReason.LIMIT_REACHED)
                }
                repository.signUp(id, user.userId, now)
            }
            record to repository.signedUp(id)
        }!!
        signedUp.first.takeIf { it.status == BigGameStatus.LOBBY }?.let { refreshLobby(it, signedUp.second) }
        return card(user.userId, id)
    }

    /** Takes the caller's sign-up back, before the round. */
    fun cancelSignup(user: AuthenticatedUser, id: BigGameId): BigGameCard {
        val changed = transactions.execute {
            val record = repository.findForUpdate(id)?.takeIf { it.status.isOpen } ?: throw notFound()
            if (record.status == BigGameStatus.RUNNING) throw wrongState("The round has started")
            repository.cancelSignup(id, user.userId)
            record to repository.signedUp(id)
        }!!
        changed.first.takeIf { it.status == BigGameStatus.LOBBY }?.let { refreshLobby(it, changed.second) }
        return card(user.userId, id)
    }

    /** Into the open lobby (or back to the round) of a big game the caller signed up for. */
    fun join(user: AuthenticatedUser, id: BigGameId, request: JoinBigGameRequest): SessionResponse {
        val record = repository.find(id)?.takeIf { it.status.isOpen } ?: throw notFound()
        requireNotHost(record, user.userId)
        if (!repository.isSignedUp(id, user.userId)) {
            throw GameException(ErrorCode.FORBIDDEN, "Sign up for the game first", ErrorReason.BIG_GAME_SIGNUP_REQUIRED)
        }
        val gameId = record.gameId?.takeIf { record.status != BigGameStatus.SCHEDULED }
            ?: throw wrongState("The lobby opens ${properties.lobbyOpensBefore.toMinutes()} minutes before the start")
        return games.joinBigGame(gameId, user, request)
    }

    // The schedule

    /**
     * Brings every big game ahead or going on up to date: opens the lobbies that are due (again, when a restart lost
     * one), starts the rounds on time, notes the rounds that ended or were lost. Called by [BigGameScheduler].
     */
    fun tick() {
        for (record in repository.open()) {
            try {
                advance(record.id)
            } catch (e: RuntimeException) {
                log.warn("Big game {}: {}", record.id.value, e.toString())
            }
        }
    }

    /** [tick] for one big game, under its row lock. */
    fun advance(id: BigGameId) {
        transactions.executeWithoutResult {
            val record = repository.findForUpdate(id) ?: return@executeWithoutResult
            val now = clock.instant()
            val next = when (record.status) {
                BigGameStatus.SCHEDULED ->
                    if (now >= record.startsAt.minus(properties.lobbyOpensBefore)) openLobby(record, now) else null

                BigGameStatus.LOBBY -> inLobby(record, now)

                BigGameStatus.RUNNING -> when (record.gameId?.let(games::phaseOf)) {
                    GamePhase.FINISHED -> record.copy(status = BigGameStatus.FINISHED, endedAt = now)

                    null -> record.copy(status = BigGameStatus.INTERRUPTED, endedAt = now).also {
                        log.warn("Big game {}: the round is gone from memory (a restart?)", record.id.value)
                    }

                    else -> null
                }

                else -> null
            }
            if (next != null) repository.update(next.copy(updatedAt = now))
        }
    }

    private fun inLobby(record: BigGameRecord, now: Instant): BigGameRecord? {
        val phase = record.gameId?.let(games::phaseOf)
        return when {
            // Lost with a restart (or ended in «Games»): the lobby opens again, the players come back from the list.
            phase == null -> openLobby(record, now).also {
                log.info("Big game {}: the lobby opened again", record.id.value)
            }

            phase != GamePhase.LOBBY -> record.copy(status = BigGameStatus.RUNNING)

            now < record.startsAt -> null

            games.startBigGame(checkNotNull(record.gameId), record.setup.seekers) ->
                record.copy(status = BigGameStatus.RUNNING)

            now >= record.startsAt.plus(properties.startPatience) -> {
                log.info("Big game {}: too few players an hour after the start, cancelled", record.id.value)
                record.gameId?.let(games::endByStaff)
                record.copy(status = BigGameStatus.CANCELLED, endedAt = now)
            }

            else -> null
        }
    }

    private fun openLobby(record: BigGameRecord, now: Instant): BigGameRecord {
        val zone = zoneOf(record)
        val settings = record.setup.settings(zone.center, zone.radiusMeters)
        val gameId = games.openBigGame(
            info = info(record, repository.signedUp(record.id)),
            settings = settings,
            stages = zone.stages(settings.zone),
            maxPlayers = record.playerLimit,
            norms = record.norms,
        )
        log.info("Big game {}: the lobby is open, game {}", record.id.value, gameId.value)
        return record.copy(status = BigGameStatus.LOBBY, gameId = gameId, updatedAt = now)
    }

    /** The lobby learns the big game's news; with [zoneChanged], the new zone and setup too. */
    private fun refreshLobby(record: BigGameRecord, signedUp: Int, zoneChanged: Boolean = false) {
        val gameId = record.gameId ?: return
        if (!zoneChanged) {
            games.updateBigGame(gameId, info(record, signedUp), record.playerLimit, record.norms)
            return
        }
        val zone = zoneOf(record)
        val settings = record.setup.settings(zone.center, zone.radiusMeters)
        games.updateBigGame(
            gameId,
            info(record, signedUp),
            record.playerLimit,
            record.norms,
            settings,
            zone.stages(settings.zone),
        )
    }

    // Helpers

    private class Valid(val zone: DrawnZone, val startsAt: Instant, val norms: AreaNorms)

    private class Estimate(val capacity: Int?, val areas: TerrainAreas?)

    private fun validate(request: AdminBigGameRequest, now: Instant, startMayBePast: Boolean): Valid {
        BigGameLimits.problem(request)?.let { throw badRequest(it) }
        val zone = DrawnZone.of(request.zone) ?: throw badRequest("The zone is no figure: it crosses itself")
        if (zone.radiusMeters > BigGameLimits.MAX_ZONE_RADIUS_METERS) {
            throw badRequest("No corner farther than ${BigGameLimits.MAX_ZONE_RADIUS_METERS.toInt()} m from the center")
        }
        val startsAt = startOf(request)
        if (!startMayBePast && startsAt <= now) throw badRequest("The start is in the past")
        if (startsAt > now.plus(Duration.ofDays(BigGameLimits.MAX_DAYS_AHEAD.toLong()))) {
            throw badRequest("At most ${BigGameLimits.MAX_DAYS_AHEAD} days ahead")
        }
        return Valid(zone, startsAt, request.norms ?: capacity.norms())
    }

    /** The start: the place's local time in its time zone, or the instant itself. */
    private fun startOf(request: AdminBigGameRequest): Instant {
        val zoneId = try {
            ZoneId.of(request.timeZone)
        } catch (e: DateTimeException) {
            throw badRequest("Unknown time zone")
        }
        val local = request.startsAtLocal ?: return Instant.ofEpochMilli(request.startsAtMillis)
        return try {
            LocalDateTime.parse(local).atZone(zoneId).toInstant()
        } catch (e: DateTimeParseException) {
            throw badRequest("The start is yyyy-mm-ddThh:mm")
        }
    }

    /** The limit asked for, or as many as the zone fits (no more than the server takes). */
    private fun limitOf(request: AdminBigGameRequest, capacity: Int?): Int {
        val limit = request.playerLimit
            ?: capacity?.coerceAtMost(BigGameLimits.MAX_PLAYERS)
            ?: throw badRequest("No map data for this zone: set the limit yourself")
        if (limit < BigGameLimits.MIN_PLAYERS) throw badRequest("The zone fits fewer than two players")
        if (request.setup.seekers >= limit) throw badRequest("Fewer seekers than players")
        return limit
    }

    private fun estimate(zone: DrawnZone, norms: AreaNorms): Estimate {
        val grid = terrain.read("a drawn zone", zone.bounds) ?: return Estimate(null, null)
        val areas = grid.areasWithin(ZoneArea.Polygon(zone.outline))
        return Estimate(Capacity.players(areas, norms), areas)
    }

    private fun zoneOf(record: BigGameRecord): DrawnZone = checkNotNull(DrawnZone.of(record.zone)) { "Stored zone" }

    private fun info(record: BigGameRecord, signedUp: Int) = BigGameInfo(
        id = record.id,
        title = record.title,
        startsAtMillis = record.startsAt.toEpochMilli(),
        timeZone = record.timeZone,
        signedUp = signedUp,
        playerLimit = record.playerLimit,
    )

    private fun card(userId: UserId, id: BigGameId): BigGameCard =
        cards(userId, listOfNotNull(repository.find(id))).single()

    private fun cards(userId: UserId, records: List<BigGameRecord>): List<BigGameCard> {
        val ids = records.map { it.id }
        val counts = repository.counts(ids)
        val mine = repository.signedUpAmong(userId, ids)
        val friends = repository.friendsSignedUp(userId, ids)
        return records.map { record ->
            BigGameCard(
                id = record.id,
                title = record.title,
                status = record.status,
                startsAtMillis = record.startsAt.toEpochMilli(),
                timeZone = record.timeZone,
                zone = record.zone,
                setup = record.setup,
                signedUp = counts[record.id] ?: 0,
                playerLimit = record.playerLimit,
                signedUpByMe = record.id in mine,
                canJoin = record.id in mine && record.gameId != null &&
                    (record.status == BigGameStatus.LOBBY || record.status == BigGameStatus.RUNNING),
                friends = friends[record.id].orEmpty(),
            )
        }
    }

    private fun admin(id: BigGameId): AdminBigGame {
        val record = repository.find(id) ?: throw notFound()
        return toAdmin(record, repository.signedUp(id))
    }

    private fun toAdmin(record: BigGameRecord, signedUp: Int): AdminBigGame {
        val zone = ZoneId.of(record.timeZone)
        val local = record.startsAt.atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)
        return AdminBigGame(
            id = record.id,
            title = record.title,
            status = record.status,
            startsAtMillis = record.startsAt.toEpochMilli(),
            timeZone = record.timeZone,
            startsAtLocal = local.toString(),
            zone = record.zone,
            setup = record.setup,
            norms = record.norms,
            areaSquareMeters = record.zone.areaSquareMeters().roundToLong(),
            capacity = record.capacity,
            areas = record.areas,
            fewCovers = record.areas?.let(Capacity::fewCovers) == true,
            playerLimit = record.playerLimit,
            signedUp = signedUp,
            gameId = record.gameId?.takeIf {
                record.status == BigGameStatus.LOBBY ||
                    record.status == BigGameStatus.RUNNING
            },
            players = record.gameId?.takeIf { record.status.isOpen }?.let(games::playersIn),
            createdByName = record.createdBy,
            createdAtMillis = record.createdAt.toEpochMilli(),
            updatedAtMillis = record.updatedAt.toEpochMilli(),
            endedAtMillis = record.endedAt?.toEpochMilli(),
        )
    }

    /** For the audit log: which big game, its limit and estimate. Never the zone. */
    private fun describe(record: BigGameRecord): String {
        val estimate = record.capacity?.let { "fits $it" } ?: "no estimate"
        val above = if (record.capacity != null && record.playerLimit > record.capacity) ", above the estimate" else ""
        return "big game ${record.id.value} «${record.title}»: limit ${record.playerLimit} ($estimate$above)"
    }

    private fun requireNotHost(record: BigGameRecord, userId: UserId) {
        if (record.hostUserId == userId) {
            throw GameException(ErrorCode.FORBIDDEN, "The account running the game can't play in it")
        }
    }

    private fun requireAdmin(staff: Staff) {
        if (!staff.isAdmin) throw GameException(ErrorCode.FORBIDDEN, "Admins only")
    }

    private fun validReason(reason: String): String {
        val trimmed = reason.trim()
        if (trimmed.isEmpty() || trimmed.length > AdminLimits.REASON_MAX_LENGTH) {
            throw badRequest("A reason is required: 1..${AdminLimits.REASON_MAX_LENGTH} characters")
        }
        return trimmed
    }

    private fun badRequest(message: String) = GameException(ErrorCode.BAD_REQUEST, message)

    private fun wrongState(message: String) = GameException(ErrorCode.WRONG_STATE, message)

    private fun notFound() = GameException(ErrorCode.NOT_FOUND, "No such big game")

    private companion object {
        const val PAGE_SIZE = 50

        /** An admin changes a big game before its round, or schedules an interrupted one again. */
        val EDITABLE = setOf(BigGameStatus.SCHEDULED, BigGameStatus.LOBBY, BigGameStatus.INTERRUPTED)
    }
}
