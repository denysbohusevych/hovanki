package app.hovanki.server.game

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.rules.shrinkingZone
import app.hovanki.shared.totp.catchCodeTotp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GameTest {
    private val center = GeoPoint(50.4501, 30.5234)
    private val settings =
        GameSettings(zone = shrinkingZone(center, steps = 0), hidingSeconds = 60, seekingSeconds = 600)
    private val host = PlayerId("host")
    private val seeker = PlayerId("seeker")
    private val hider = PlayerId("hider")
    private val secret = "00112233445566778899aabbccddeeff00112233"

    private var now = 1_700_000_000_000L
    private val game = Game(GameId("g"), "ABC234", host, settings, now)

    /** Lobby with three players; the host hides too. Advances into SEEKING. */
    private fun startedGame(): Game {
        game.addPlayer(host, "Host", now)
        game.addPlayer(seeker, "Seeker", now)
        game.addPlayer(hider, "Hider", now)
        game.start(host, setOf(seeker), { secret }, now)
        assertEquals(GamePhase.HIDING, game.phase)
        tick(60)
        assertEquals(GamePhase.SEEKING, game.phase)
        return game
    }

    private fun tick(seconds: Int) {
        now += seconds * 1000L
        game.advance(now)
    }

    private fun report(player: PlayerId, point: GeoPoint, accuracy: Double = 5.0) {
        game.recordLocations(player, listOf(LocationSample(point, accuracy, now)), now)
        game.advance(now)
    }

    private fun claim(): CatchId {
        val id = CatchId("c$now")
        game.claimCatch(seeker, hider, id, now)
        return id
    }

    private fun code() = catchCodeTotp(secret, settings.rules).codeAt(now)

    private fun statusOf(player: PlayerId) = game.snapshotFor(host, now).players.single { it.id == player }.status

    @Test
    fun catchConfirmedWithTheCode() {
        startedGame()
        report(seeker, center)
        report(hider, center.moveBy(20.0, 0.0))

        val id = claim()
        game.confirmCatch(id, seeker, code(), now)

        assertEquals(PlayerStatus.CAUGHT, statusOf(hider))
        assertEquals(CatchStatus.CONFIRMED, game.snapshotFor(seeker, now).catches.single().status)
    }

    @Test
    fun claimRejectedWhenGpsProvesPlayersAreFarApart() {
        startedGame()
        report(seeker, center)
        report(hider, center.moveBy(300.0, 0.0))

        val error = assertFailsWith<GameException> { claim() }
        assertEquals(ErrorCode.TOO_FAR, error.code)
    }

    @Test
    fun silenceCountsAsCaught() {
        startedGame()
        report(seeker, center)
        claim()

        tick(settings.rules.catchCodeTimeoutSeconds)

        assertEquals(PlayerStatus.CAUGHT, statusOf(hider))
        assertEquals(GamePhase.SEEKING, game.phase, "the host is still hiding")
    }

    @Test
    fun wrongCodesRejectTheClaimAfterMaxAttempts() {
        startedGame()
        report(seeker, center)
        val id = claim()
        val acceptedNow = (-1..1).map { catchCodeTotp(secret, settings.rules).codeAt(now + it * 30_000L) }
        val wrong = (0..9999).map { it.toString().padStart(4, '0') }.first { it !in acceptedNow }

        repeat(settings.rules.catchCodeMaxAttempts) {
            assertEquals(
                ErrorCode.INVALID_CODE,
                assertFailsWith<GameException> {
                    game.confirmCatch(id, seeker, wrong, now)
                }.code,
            )
        }
        assertEquals(CatchStatus.REJECTED, game.snapshotFor(seeker, now).catches.single().status)
        assertEquals(PlayerStatus.ACTIVE, statusOf(hider))
    }

    @Test
    fun disputeIsDecidedByTheOtherPlayers() {
        startedGame()
        report(seeker, center)
        val id = claim()
        game.disputeCatch(id, hider, now)

        val voterView = game.snapshotFor(host, now).catches.single()
        assertEquals(CatchStatus.DISPUTED, voterView.status)
        assertEquals(true, voterView.canVote)
        assertFailsWith<GameException> { game.vote(id, seeker, true, now) }

        game.vote(id, host, confirm = false, now)

        assertEquals(CatchStatus.REJECTED, game.snapshotFor(seeker, now).catches.single().status)
        assertEquals(PlayerStatus.ACTIVE, statusOf(hider))
    }

    @Test
    fun disputeWithoutVotesFallsBackToGps() {
        startedGame()
        report(seeker, center)
        report(hider, center.moveBy(10.0, 0.0))
        val id = claim()
        game.disputeCatch(id, hider, now)

        tick(settings.rules.disputeVoteSeconds)

        assertEquals(PlayerStatus.CAUGHT, statusOf(hider))
    }

    @Test
    fun disputeDefaultRuleUsesTheLikelyDistance() {
        startedGame()
        // 60 m apart with 15 m accuracy each: close enough to open a claim, too far for the default rule.
        report(seeker, center, accuracy = 15.0)
        report(hider, center.moveBy(60.0, 0.0), accuracy = 15.0)
        val id = claim()
        game.disputeCatch(id, hider, now)

        tick(settings.rules.disputeVoteSeconds)

        assertEquals(PlayerStatus.ACTIVE, statusOf(hider))
        assertEquals(CatchStatus.REJECTED, game.snapshotFor(seeker, now).catches.single().status)
    }

    @Test
    fun leavingTheZoneWarnsThenEliminates() {
        startedGame()
        val outside = center.moveBy(700.0, 0.0)
        repeat(3) {
            report(hider, outside)
            tick(5)
        }
        assertNotNull(game.snapshotFor(hider, now).me.outOfZoneDeadlineMillis)
        val seekerView = game.snapshotFor(seeker, now).players.single { it.id == hider }
        assertEquals(VisibilityReason.OUT_OF_ZONE, seekerView.location?.reason)

        repeat(12) {
            report(hider, outside)
            tick(5)
        }
        assertEquals(PlayerStatus.ELIMINATED, statusOf(hider))
    }

    @Test
    fun hidersAreInvisibleUntilTheirSignalGoesStale() {
        startedGame()
        report(hider, center.moveBy(50.0, 50.0))
        report(seeker, center)

        assertNull(game.snapshotFor(seeker, now).players.single { it.id == hider }.location)
        assertNull(game.snapshotFor(hider, now).players.single { it.id == seeker }.location, "hiders never see others")

        tick(settings.rules.staleLocationRevealSeconds)
        val revealed = game.snapshotFor(seeker, now).players.single { it.id == hider }.location
        assertEquals(VisibilityReason.STALE_SIGNAL, revealed?.reason)
    }

    @Test
    fun gpsOffIsRevealedEvenIfTheAppKeepsSyncing() {
        startedGame()
        report(hider, center.moveBy(50.0, 50.0))
        repeat(9) {
            tick(5)
            game.recordLocations(hider, emptyList(), now)
        }

        val revealed = game.snapshotFor(seeker, now).players.single { it.id == hider }.location
        assertEquals(VisibilityReason.STALE_SIGNAL, revealed?.reason)
    }

    @Test
    fun onlyHiderGetsItsCatchCodeSecret() {
        startedGame()
        assertEquals(secret, game.snapshotFor(hider, now).me.catchCodeSecret)
        assertNull(game.snapshotFor(seeker, now).me.catchCodeSecret)
    }

    @Test
    fun gameEndsWhenAllHidersAreCaught() {
        startedGame()
        report(seeker, center)
        game.claimCatch(seeker, hider, CatchId("c1"), now)
        game.confirmCatch(CatchId("c1"), seeker, code(), now)
        game.claimCatch(seeker, host, CatchId("c2"), now)
        tick(settings.rules.catchCodeTimeoutSeconds)

        assertEquals(GamePhase.FINISHED, game.phase)
    }
}
