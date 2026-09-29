package app.hovanki.server.debug

import app.hovanki.server.account.UserRepository
import app.hovanki.server.features.FeatureFlags
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
import app.hovanki.shared.debug.DebugSetFeatures
import app.hovanki.shared.debug.DebugSetRole
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.UserId
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
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
    private val users: UserRepository,
    private val features: FeatureFlags,
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
            game.debugState(now).copy(enabledFeatures = features.enabledNames())
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

    /**
     * Turns exactly the named server features on, as an admin does in the admin (docs/adr/0012-nearby-radar.md); with
     * [DebugSetFeatures.keepOthers], only turns them on.
     */
    @PostMapping(DebugRoutes.FEATURES)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun setFeatures(@RequestBody request: DebugSetFeatures) {
        val wanted = request.enabled.map { name ->
            ServerFeature.entries.firstOrNull { it.name == name }
                ?: throw GameException(ErrorCode.BAD_REQUEST, "Unknown feature $name")
        }.toSet()
        val now = clock.instant()
        for (feature in ServerFeature.entries) {
            val on = feature in wanted || (request.keepOthers && features.isEnabled(feature))
            if (features.isEnabled(feature) != on) features.set(feature, on, "e2e", now)
        }
    }

    /** Makes an account staff for the admin's e2e tests, as the operator does with SQL on a real server. */
    @PostMapping(DebugRoutes.USER_ROLE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun setRole(@PathVariable userId: String, @RequestBody request: DebugSetRole) {
        if (!users.setRole(UserId(userId), request.role)) throw GameException(ErrorCode.NOT_FOUND, "No such account")
    }

    companion object {
        const val PROFILE = "e2e"
        private const val MAX_REPORTS = 1000
    }
}
