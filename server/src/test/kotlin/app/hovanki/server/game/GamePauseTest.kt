package app.hovanki.server.game

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.rules.shrinkingZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pause and the SOS (docs/adr/0019-pause-and-sos.md). */
class GamePauseTest {
    private val center = GeoPoint(50.4501, 30.5234)
    private val settings =
        GameSettings(zone = shrinkingZone(center, steps = 0), hidingSeconds = 60, seekingSeconds = 600)
    private val host = PlayerId("host")
    private val seeker = PlayerId("seeker")
    private val hider = PlayerId("hider")
    private val secret = "00112233445566778899aabbccddeeff00112233"

    private var now = 1_700_000_000_000L
    private val game = Game(GameId("g"), "ABC234", host, settings, now)

    private fun lobby() {
        game.addPlayer(host, "Host", now)
        game.addPlayer(seeker, "Seeker", now)
        game.addPlayer(hider, "Hider", now)
        game.start(host, setOf(seeker), { secret }, now)
    }

    private fun searching() {
        lobby()
        tick(60)
        assertEquals(GamePhase.SEEKING, game.phase)
    }

    private fun tick(seconds: Int) {
        now += seconds * 1000L
        game.advance(now)
    }

    private fun report(player: PlayerId, point: GeoPoint) {
        game.recordLocations(player, listOf(LocationSample(point, 5.0, now)), now)
        game.advance(now)
    }

    private fun snapshot(viewer: PlayerId = host) = game.snapshotFor(viewer, now)

    @Test
    fun theHidingTimeWaitsOnPause() {
        lobby()
        val endsAt = assertNotNull(snapshot().phaseEndsAtMillis)
        tick(30)
        game.setPaused(host, true, now)
        val pausedAt = now
        assertEquals(pausedAt, snapshot().pause?.sinceMillis)
        assertNull(game.nextDueMillis(now), "nothing is due on pause")

        tick(300)
        assertEquals(GamePhase.HIDING, game.phase, "the hiding time does not run out on pause")

        game.setPaused(host, false, now)
        assertNull(snapshot().pause)
        assertEquals(endsAt + 300_000L, snapshot().phaseEndsAtMillis)
        tick(29)
        assertEquals(GamePhase.HIDING, game.phase)
        tick(1)
        assertEquals(GamePhase.SEEKING, game.phase)
    }

    @Test
    fun theZoneAndTheSearchStartMoveByThePause() {
        searching()
        val zoneStart = assertNotNull(snapshot().zoneStartedAtMillis)
        val endsAt = assertNotNull(snapshot().phaseEndsAtMillis)
        game.setPaused(host, true, now)
        tick(120)
        game.setPaused(host, false, now)
        assertEquals(zoneStart + 120_000L, snapshot().zoneStartedAtMillis)
        assertEquals(endsAt + 120_000L, snapshot().phaseEndsAtMillis)
    }

    @Test
    fun aClaimWaitsOnPauseAndNothingNewIsClaimed() {
        searching()
        report(seeker, center)
        report(hider, center.moveBy(20.0, 0.0))
        game.claimCatch(seeker, hider, CatchId("c1"), now)
        val deadline = assertNotNull(snapshot(seeker).catches.single().deadlineMillis)

        game.setPaused(host, true, now)
        tick(settings.rules.catchCodeTimeoutSeconds * 2)
        assertEquals(CatchStatus.AWAITING_CODE, snapshot(seeker).catches.single().status, "silence waits on pause")
        val refused = assertFailsWith<GameException> { game.confirmCatch(CatchId("c1"), seeker, "0000", now) }
        assertEquals(ErrorReason.GAME_PAUSED, refused.reason)

        game.setPaused(host, false, now)
        val moved = snapshot(seeker).catches.single().deadlineMillis
        assertEquals(deadline + settings.rules.catchCodeTimeoutSeconds * 2 * 1000L, moved)
    }

    @Test
    fun onlyTheHostPausesAndOnlyInTheRound() {
        game.addPlayer(host, "Host", now)
        game.addPlayer(seeker, "Seeker", now)
        assertEquals(ErrorCode.WRONG_STATE, assertFailsWith<GameException> { game.setPaused(host, true, now) }.code)
        game.addPlayer(hider, "Hider", now)
        game.start(host, setOf(seeker), { secret }, now)
        assertEquals(ErrorCode.FORBIDDEN, assertFailsWith<GameException> { game.setPaused(seeker, true, now) }.code)
    }

    @Test
    fun theSeekersMapStandsStillOnPause() {
        searching()
        val outside = center.moveBy(3_000.0, 0.0)
        repeat(5) {
            report(hider, outside)
            tick(5)
        }
        val seen = snapshot(seeker).players.single { it.id == hider }.location
        assertEquals(VisibilityReason.OUT_OF_ZONE, seen?.cause)

        game.setPaused(host, true, now)
        tick(5)
        report(hider, center.moveBy(3_000.0, 500.0))
        assertEquals(outside, snapshot(seeker).players.single { it.id == hider }.location?.point)
        tick(600)
        assertEquals(PlayerStatus.ACTIVE, snapshot().players.single { it.id == hider }.status, "no time out on pause")
    }

    @Test
    fun anSosPausesTheRoundAndShowsEverybodyTheCaller() {
        searching()
        val there = center.moveBy(40.0, 10.0)
        report(hider, there)

        game.callSos(hider, now)

        val pause = assertNotNull(snapshot(seeker).pause)
        assertTrue(pause.sos)
        for (viewer in listOf(host, seeker, hider)) {
            val call = snapshot(viewer).sos.single()
            assertEquals(hider, call.playerId)
            assertEquals(there, call.location?.point)
        }
        assertEquals(1, game.adminView(now).sosCalls)
    }

    @Test
    fun theRoundGoesOnOnlyOnceEverySosIsOver() {
        searching()
        game.callSos(hider, now)
        val refused = assertFailsWith<GameException> { game.setPaused(host, false, now) }
        assertEquals(ErrorReason.SOS_ACTIVE, refused.reason)
        assertEquals(
            ErrorCode.FORBIDDEN,
            assertFailsWith<GameException> { game.endSos(seeker, hider, now) }.code,
            "only the host ends somebody else's",
        )

        game.endSos(hider, hider, now)
        assertTrue(snapshot().sos.isEmpty())
        assertNotNull(snapshot().pause, "the host lets it go on")
        game.setPaused(host, false, now)
        assertNull(snapshot().pause)
    }

    @Test
    fun theHostEndsAnybodysSos() {
        searching()
        game.callSos(seeker, now)
        game.endSos(host, seeker, now)
        assertTrue(snapshot().sos.isEmpty())
    }

    @Test
    fun aHostWhoLeftLeavesNoRoundStandingStill() {
        game.addPlayer(host, "Host", now)
        game.addPlayer(seeker, "Seeker", now)
        game.addPlayer(hider, "Hider", now)
        game.addPlayer(PlayerId("other"), "Other", now)
        game.start(host, setOf(seeker), { secret }, now)
        tick(60)
        game.setPaused(host, true, now)
        game.leave(host, now)
        assertNull(snapshot(seeker).pause)
    }
}
