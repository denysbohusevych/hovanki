package app.hovanki.server.debug

import app.hovanki.server.game.GameException
import app.hovanki.server.game.GameRegistry
import app.hovanki.server.mail.EmailSender
import app.hovanki.server.mail.RecordingEmailSender
import app.hovanki.server.moderation.ReportRepository
import app.hovanki.shared.debug.DebugEmail
import app.hovanki.shared.debug.DebugEmails
import app.hovanki.shared.debug.DebugGameList
import app.hovanki.shared.debug.DebugGameState
import app.hovanki.shared.debug.DebugGameSummary
import app.hovanki.shared.debug.DebugReport
import app.hovanki.shared.debug.DebugReportList
import app.hovanki.shared.debug.DebugRoutes
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.UserId
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

/**
 * Observer for end-to-end tests: every game with every position, claim and reveal, the emails the server sent (with
 * their codes) and the chat reports, without authentication. Exists only with the Spring profile `e2e` (see
 * docs/e2e.md); a normal server has no such routes.
 */
@RestController
@Profile(DebugController.PROFILE)
class DebugController(
    private val registry: GameRegistry,
    private val clock: Clock,
    private val emailSender: EmailSender,
    private val reports: ReportRepository,
) {
    init {
        LoggerFactory.getLogger(javaClass)
            .warn(
                "Profile '{}' is active: {} exposes all player positions. Never use it in production.",
                PROFILE,
                DebugRoutes.GAMES,
            )
    }

    @GetMapping(DebugRoutes.GAMES)
    fun games(): DebugGameList {
        val now = clock.millis()
        val summaries = registry.all().map { game ->
            synchronized(game) {
                game.advance(now)
                val state = game.debugState(now)
                DebugGameSummary(state.gameId, state.joinCode, state.phase, state.hostId, state.players.map { it.name })
            }
        }
        return DebugGameList(now, summaries)
    }

    // Both catch up with time first, like every game request does, so the observer sees the current state.

    @GetMapping(DebugRoutes.GAME)
    fun game(@PathVariable gameId: String): DebugGameState {
        val game = registry.get(GameId(gameId)) ?: throw GameException(ErrorCode.NOT_FOUND, "No such game")
        return synchronized(game) {
            val now = clock.millis()
            game.advance(now)
            game.debugState(now)
        }
    }

    /** The emails sent to [email] (case-insensitive), oldest first; needs `hovanki.mail.sender=recording`. */
    @GetMapping(DebugRoutes.EMAILS)
    fun emails(@PathVariable email: String): DebugEmails {
        val recorder = emailSender as? RecordingEmailSender
            ?: throw GameException(ErrorCode.WRONG_STATE, "Emails are not recorded: set hovanki.mail.sender=recording")
        return DebugEmails(
            recorder.sentTo(email).map { (sent, sentAt) ->
                DebugEmail(sent.to, sent.purpose.name, sent.language, sent.subject, sent.text, sent.code, sentAt)
            },
        )
    }

    /** The newest reported chat messages, newest first. */
    @GetMapping(DebugRoutes.REPORTS)
    fun reports(): DebugReportList = DebugReportList(
        reports.latest(MAX_REPORTS).map {
            DebugReport(
                id = it.id.toString(),
                gameId = GameId(it.gameId),
                messageSeq = it.messageSeq,
                reporterPlayerId = PlayerId(it.reporterPlayerId),
                reporterUserId = it.reporterUserId?.let(::UserId),
                reportedUserId = it.reportedUserId?.let(::UserId),
                reportedName = it.reportedName,
                text = it.text,
                createdAtMillis = it.createdAt.toEpochMilli(),
            )
        },
    )

    companion object {
        const val PROFILE = "e2e"
        private const val MAX_REPORTS = 1000
    }
}
