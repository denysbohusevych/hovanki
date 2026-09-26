package app.hovanki.server.game

import app.hovanki.shared.debug.DebugBuildings
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.BuildingsState
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
    fun oneFixInsideDoesNotLiftTheWarning() {
        startedGame()
        // Zone radius 500 m, border margin 10 m: 540 m with 5 m accuracy is clearly outside, 500 m is not.
        val outside = center.moveBy(540.0, 0.0)
        repeat(3) {
            report(hider, outside)
            tick(5)
        }
        val deadline = assertNotNull(game.snapshotFor(hider, now).me.outOfZoneDeadlineMillis)

        report(hider, center.moveBy(500.0, 0.0))
        tick(1)
        assertEquals(deadline, game.snapshotFor(hider, now).me.outOfZoneDeadlineMillis, "a GPS jump is not a return")

        repeat(3) {
            report(hider, center.moveBy(480.0, 0.0))
            tick(2)
        }
        assertNull(game.snapshotFor(hider, now).me.outOfZoneDeadlineMillis, "back for several fixes: warning lifted")
        assertEquals(PlayerStatus.ACTIVE, statusOf(hider))
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

    @Test
    fun gameFinishesWhenTheLastHiderIsCaughtBySilence() {
        startedGame()
        report(seeker, center)
        game.claimCatch(seeker, hider, CatchId("c1"), now)
        game.confirmCatch(CatchId("c1"), seeker, code(), now)
        game.claimCatch(seeker, host, CatchId("c2"), now)
        val deadline = now + settings.rules.catchCodeTimeoutSeconds * 1000L

        // The next request comes a bit after the deadline: the game still ended at the deadline.
        tick(settings.rules.catchCodeTimeoutSeconds + 3)

        assertEquals(GamePhase.FINISHED, game.phase)
        assertEquals(deadline, game.debugState(now).finishedAtMillis)
        assertEquals(deadline, game.debugState(now).phaseStartedAtMillis)
    }

    // ---- Buildings (docs/adr/0003-map-and-buildings.md) ----

    private val insideBlock = center.moveBy(DebugBuildings.INSIDE_EAST, DebugBuildings.INSIDE_NORTH)
    private val nextToBlock = center.moveBy(DebugBuildings.INSIDE_EAST, DebugBuildings.SOUTH - 20)
    private val inTheArch = center.moveBy(DebugBuildings.ARCH_EAST, DebugBuildings.INSIDE_NORTH)
    private val revealMillis = settings.rules.insideBuildingRevealSeconds * 1000L

    private fun withTestQuarter(): Game {
        val quarter = DebugBuildings.around(center)
        game.onBuildingsLoaded(quarter.buildings, quarter.passages)
        return startedGame()
    }

    /** [player] keeps reporting [point] every 3 s for [seconds], the seeker stays put. */
    private fun stay(player: PlayerId, point: GeoPoint, seconds: Int) {
        repeat(seconds / 3) {
            now += 3_000
            report(seeker, center)
            report(player, point)
        }
    }

    private fun warning(): Long? = game.snapshotFor(hider, now).me.insideBuildingRevealAtMillis

    private fun hiderAsSeenBySeeker() = game.snapshotFor(seeker, now).players.single { it.id == hider }.location

    @Test
    fun insideABuildingForLongerThanAllowedIsRevealedNeverEliminated() {
        withTestQuarter()
        stay(hider, insideBlock, 9)

        val revealAt = assertNotNull(warning(), "warned as soon as the server is confident")
        assertEquals(now + revealMillis, revealAt)
        assertNull(hiderAsSeenBySeeker(), "not revealed before the time is up")

        stay(hider, insideBlock, settings.rules.insideBuildingRevealSeconds)

        val seen = assertNotNull(hiderAsSeenBySeeker())
        assertEquals(VisibilityReason.INSIDE_BUILDING, seen.cause)
        assertEquals(VisibilityReason.OUT_OF_ZONE, seen.reason, "the closest reason the first app versions know")
        assertEquals(
            VisibilityReason.INSIDE_BUILDING,
            game.debugState(now).players.single {
                it.id == hider
            }.revealedToSeekers,
        )
        assertEquals(PlayerStatus.ACTIVE, statusOf(hider), "GPS near houses is a hint, not a judge")
    }

    @Test
    fun timeInsideDuringTheHidingPhaseDoesNotCount() {
        val quarter = DebugBuildings.around(center)
        game.onBuildingsLoaded(quarter.buildings, quarter.passages)
        game.addPlayer(host, "Host", now)
        game.addPlayer(seeker, "Seeker", now)
        game.addPlayer(hider, "Hider", now)
        game.start(host, setOf(seeker), { secret }, now)

        stay(hider, insideBlock, settings.hidingSeconds)

        assertEquals(GamePhase.SEEKING, game.phase)
        val seekingStarted = game.debugState(now).phaseStartedAtMillis
        assertEquals(seekingStarted + revealMillis, warning(), "the full time to get out, counted from the seeking")
        stay(hider, insideBlock, settings.rules.insideBuildingRevealSeconds - 3)
        assertNull(hiderAsSeenBySeeker())
    }

    @Test
    fun oneFixThatJumpsIntoTheBuildingDecidesNothing() {
        withTestQuarter()
        stay(hider, nextToBlock, 9)
        stay(hider, insideBlock, 3)
        stay(hider, nextToBlock, settings.rules.insideBuildingRevealSeconds + 9)

        assertNull(warning())
        assertNull(hiderAsSeenBySeeker())
    }

    @Test
    fun leavingBeforeTheRevealLiftsTheWarning() {
        withTestQuarter()
        stay(hider, insideBlock, 9)
        assertNotNull(warning())

        stay(hider, nextToBlock, 9)
        assertNull(warning(), "out again, judged on several fixes")

        stay(hider, nextToBlock, settings.rules.insideBuildingRevealSeconds)
        assertNull(hiderAsSeenBySeeker())
    }

    @Test
    fun oneFixOutsideDoesNotResetTheTimer() {
        withTestQuarter()
        stay(hider, insideBlock, 9)
        val revealAt = assertNotNull(warning())

        stay(hider, nextToBlock, 3)
        stay(hider, insideBlock, 6)
        assertEquals(revealAt, warning())
    }

    @Test
    fun anArchThroughTheBuildingIsOutdoors() {
        withTestQuarter()
        stay(hider, inTheArch, settings.rules.insideBuildingRevealSeconds + 9)

        assertNull(warning())
        assertNull(hiderAsSeenBySeeker())
    }

    @Test
    fun withoutBuildingDataTheRuleIsOffAndThePlayersKnow() {
        assertEquals(BuildingsState.LOADING, game.debugState(now).buildings, "until the source answers")
        game.onBuildingsUnavailable()
        startedGame()

        stay(hider, insideBlock, settings.rules.insideBuildingRevealSeconds + 9)

        assertEquals(BuildingsState.UNAVAILABLE, game.snapshotFor(hider, now).buildings)
        assertNull(warning())
        assertNull(hiderAsSeenBySeeker())
    }

    @Test
    fun playersGetTheBuildingsTheRuleJudgesBy() {
        withTestQuarter()

        val buildings = game.buildingsFor(hider)
        assertEquals(BuildingsState.READY, buildings.state)
        assertEquals(DebugBuildings.around(center).buildings, buildings.buildings)
        assertEquals(1, buildings.passages.size)
        assertEquals(ErrorCode.NOT_FOUND, assertFailsWith<GameException> { game.buildingsFor(PlayerId("x")) }.code)
    }
}
