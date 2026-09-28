package app.hovanki.server.history

import app.hovanki.server.account.UserRepository
import app.hovanki.server.api.AuthenticatedUser
import app.hovanki.server.game.GameException
import app.hovanki.server.game.GameRegistry
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameHistoryResponse
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameRoute
import app.hovanki.shared.protocol.PlayerStats
import app.hovanki.shared.protocol.PrivacyRequest
import app.hovanki.shared.protocol.UserProfile
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant

/**
 * The caller's own history (docs/adr/0007-game-history-and-routes.md): statistics, their games, their saved routes, and
 * the consent to keep routes. Every method is about the caller only: there is no way to read another player's
 * history, numbers or route.
 */
@Service
class HistoryService(
    private val history: HistoryRepository,
    private val users: UserRepository,
    private val writer: HistoryWriter,
    private val registry: GameRegistry,
    private val properties: HistoryProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    fun stats(user: AuthenticatedUser): PlayerStats = history.stats(user.userId)

    /** A page of the caller's games, newest first: those that ended before [before] (epoch millis; null: newest). */
    fun games(user: AuthenticatedUser, before: Long?): GameHistoryResponse {
        val games = history.games(user.userId, before?.let(Instant::ofEpochMilli), PAGE_SIZE)
        val next = games.lastOrNull()?.finishedAtMillis?.takeIf { games.size == PAGE_SIZE }
        return GameHistoryResponse(games, next)
    }

    /** The caller's saved route of [gameId]; 404 when none is saved (never was, deleted, or expired). */
    fun route(user: AuthenticatedUser, gameId: GameId): GameRoute =
        history.route(user.userId, gameId, properties.routeRetention)
            ?: throw GameException(ErrorCode.NOT_FOUND, "No route of this game is saved")

    /** Deletes the caller's saved route of [gameId], if any; the game stays in the history. */
    fun deleteRoute(user: AuthenticatedUser, gameId: GameId) {
        history.deleteRoute(user.userId, gameId)
    }

    /**
     * Turns keeping the caller's routes on or off. On: from now on, and for their games that finished but are still
     * in memory (the results screen), so the game they just played is kept too. Off: every route saved so far is
     * deleted right away; the history and statistics stay.
     */
    fun setPrivacy(user: AuthenticatedUser, request: PrivacyRequest): UserProfile {
        val now = clock.instant()
        val profile = transactions.execute {
            if (users.lock(listOf(user.userId)).isEmpty()) throw sessionExpired()
            users.setSaveRoutes(user.userId, if (request.saveRoutes) now else null)
            if (!request.saveRoutes) history.deleteRoutes(user.userId)
            users.findById(user.userId)?.toProfile()
        } ?: throw sessionExpired()
        if (request.saveRoutes) {
            val finished = registry.all().mapNotNull { game ->
                synchronized(game) { game.finishedRecord() }?.takeIf { record ->
                    record.results.any { it.userId == user.userId }
                }
            }
            writer.saveLateRoutes(user.userId, finished)
        }
        return profile
    }

    private companion object {
        const val PAGE_SIZE = 20

        fun sessionExpired() =
            GameException(ErrorCode.UNAUTHORIZED, "Logged out: log in again", ErrorReason.SESSION_EXPIRED)
    }
}
