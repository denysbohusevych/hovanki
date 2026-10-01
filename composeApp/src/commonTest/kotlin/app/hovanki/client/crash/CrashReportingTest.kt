package app.hovanki.client.crash

import app.hovanki.client.BuildConstants
import app.hovanki.client.account.AccountState
import app.hovanki.client.session.SessionState
import app.hovanki.shared.crash.SentryScrubber
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.MyState
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.rules.shrinkingZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CrashReportingTest {
    @Test
    fun theNoopReporterReportsNothing() {
        assertNull(NoopCrashReporter.capture(IllegalStateException("boom")))
        NoopCrashReporter.breadcrumb("lobby")
        assertNull(NoopCrashReporter.asErrorReporter().capture(IllegalStateException("boom")))
    }

    @Test
    fun theErrorReporterSeamAsksTheCrashReporter() {
        val seen = mutableListOf<Throwable>()
        val reporter = object : CrashReporter {
            override fun capture(t: Throwable): String? {
                seen += t
                return "event-1"
            }

            override fun breadcrumb(screen: String) = Unit
        }
        val failure = IllegalStateException("boom")
        assertEquals("event-1", reporter.asErrorReporter().capture(failure))
        assertSame(failure, seen.single())
    }

    @Test
    fun whatKoinBindsForwardsToTheReporterInstalledNow() {
        // Nothing installed (every build but the field test one): nothing is reported.
        assertSame(NoopCrashReporter, CrashReporting.reporter)
        assertNull(CurrentCrashReporter.capture(IllegalStateException("boom")))

        val screens = mutableListOf<String>()
        val installed = object : CrashReporter {
            override fun capture(t: Throwable): String? = "event-2"

            override fun breadcrumb(screen: String) {
                screens += screen
            }
        }
        // Installed after the binding was made, as iOS does from Swift.
        try {
            CrashReporting.install(installed)
            assertEquals("event-2", CurrentCrashReporter.capture(IllegalStateException("boom")))
            CurrentCrashReporter.breadcrumb("lobby")
            assertEquals(listOf("lobby"), screens)
        } finally {
            CrashReporting.install(NoopCrashReporter)
        }
        assertNull(CurrentCrashReporter.capture(IllegalStateException("boom")))
    }

    @Test
    fun theUnitTestBuildHasNoDsn() {
        // `hovanki.sentryDsn` is passed to the field test build only (preview.yml).
        assertTrue(BuildConstants.SENTRY_DSN.isEmpty() || BuildConstants.SENTRY_DSN.startsWith("https://"))
        assertEquals(BuildConstants.SENTRY_DSN, CrashReporting.dsn)
    }

    @Test
    fun theFacadeForSwiftIsTheScrubber() {
        assertEquals(SentryScrubber.SCREEN_CATEGORY, CrashReporting.screenCategory)
        assertEquals("a [token] b", CrashReporting.scrub("a ${"f".repeat(40)} b"))
        assertEquals("lobby", CrashReporting.screenName("lobby"))
        assertNull(CrashReporting.screenName("Anna's game"))
    }

    @Test
    fun screensAreNamedLikeTheAppShowsThem() {
        val loggedIn = AccountState(
            user = UserProfile(UserId("u1"), "Anna", "anna@example.com", true, 0L),
            isRestored = true,
        )
        val session = PlayerSession(GameId("g1"), PlayerId("p1"), "t")
        fun inGame(phase: GamePhase?) = SessionState(session = session, snapshot = phase?.let(::snapshot))

        assertEquals("loading", crashScreenName(SessionState(), AccountState(), isWatching = false))
        assertEquals("welcome", crashScreenName(SessionState(), AccountState(isRestored = true), isWatching = false))
        assertEquals("main", crashScreenName(SessionState(), loggedIn, isWatching = false))
        assertEquals("spectator", crashScreenName(SessionState(), loggedIn, isWatching = true))
        assertEquals("loading", crashScreenName(inGame(null), loggedIn, isWatching = false))
        assertEquals(
            "resuming",
            crashScreenName(SessionState(session = session, isResuming = true), loggedIn, isWatching = false),
        )
        assertEquals("lobby", crashScreenName(inGame(GamePhase.LOBBY), loggedIn, isWatching = false))
        assertEquals("game", crashScreenName(inGame(GamePhase.HIDING), loggedIn, isWatching = false))
        assertEquals("game", crashScreenName(inGame(GamePhase.SEEKING), loggedIn, isWatching = false))
        assertEquals("results", crashScreenName(inGame(GamePhase.FINISHED), loggedIn, isWatching = false))
        // Whatever the app shows, the name passes the breadcrumb's own check.
        listOf("loading", "welcome", "main", "spectator", "resuming", "lobby", "game", "results").forEach {
            assertNotNull(SentryScrubber.screenName(it), it)
        }
    }

    private fun snapshot(phase: GamePhase) = GameSnapshot(
        gameId = GameId("g1"),
        joinCode = "ABC234",
        hostId = PlayerId("p1"),
        phase = phase,
        settings = GameSettings(zone = shrinkingZone(GeoPoint(50.4501, 30.5234))),
        serverTimeMillis = 1_000L,
        players = emptyList(),
        me = MyState(PlayerId("p1"), Role.HIDER, PlayerStatus.ACTIVE),
    )
}
