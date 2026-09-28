package app.hovanki.server.game

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.protocol.VisibleLocation
import app.hovanki.shared.rules.shrinkingZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The glow (docs/adr/0009-game-setup-glow-streets.md): every minute of the search the seekers see the hiders live for
 * 10 s; between glows, the spot where the last glow left them.
 */
class GameGlowTest {
    private val center = GeoPoint(50.4501, 30.5234)
    private val settings = GameSettings(
        zone = shrinkingZone(center, steps = 0),
        hidingSeconds = 60,
        seekingSeconds = 600,
        glowEverySeconds = 60,
        glowForSeconds = 10,
    )
    private val seeker = PlayerId("seeker")
    private val hider = PlayerId("hider")
    private val other = PlayerId("other")
    private var now = 1_700_000_000_000L
    private val game = Game(GameId("g"), "ABC234", seeker, settings, now)
    private val seekingStart: Long

    init {
        game.addPlayer(seeker, "Seeker", now)
        game.addPlayer(hider, "Hider", now)
        game.addPlayer(other, "Other", now)
        game.start(seeker, setOf(seeker), { "00112233445566778899aabbccddeeff00112233" }, now)
        now += 60_000
        game.advance(now)
        seekingStart = now
        check(game.phase == GamePhase.SEEKING)
    }

    /** The seconds since the search started; the hider and the other one report their spots every 2 s meanwhile. */
    private fun at(secondsIntoSearch: Int, hiderAt: GeoPoint = center) {
        val target = seekingStart + secondsIntoSearch * 1000L
        while (now < target) {
            now = minOf(now + 2_000, target)
            game.recordLocations(hider, listOf(LocationSample(hiderAt, 5.0, now)), now)
            game.recordLocations(other, listOf(LocationSample(center, 5.0, now)), now)
            game.recordLocations(seeker, listOf(LocationSample(center, 5.0, now)), now)
            game.advance(now)
        }
    }

    private fun seen(): VisibleLocation? = game.snapshotFor(seeker, now).players.single { it.id == hider }.location

    @Test
    fun hiddenUntilTheFirstGlow() {
        at(59)

        assertNull(seen())
    }

    @Test
    fun liveDuringAGlow() {
        at(62)
        val first = assertNotNull(seen())
        assertEquals(VisibilityReason.GLOW, first.cause)
        // The first app versions read it as the closest reason they know.
        assertEquals(VisibilityReason.OUT_OF_ZONE, first.reason)

        val moved = center.moveBy(30.0, 0.0)
        at(68, hiderAt = moved)

        assertEquals(moved, assertNotNull(seen()).point)
    }

    @Test
    fun betweenGlowsTheSpotTheLastOneLeft() {
        val there = center.moveBy(0.0, 40.0)
        at(69, hiderAt = there)
        at(90, hiderAt = center.moveBy(0.0, 150.0))

        val mark = assertNotNull(seen())
        assertEquals(there, mark.point)
        assertEquals(VisibilityReason.GLOW, mark.cause)
        // Taken during the glow, never later: the seekers learn nothing of where the hider went since.
        assert(mark.atMillis < seekingStart + 70_000)
    }

    @Test
    fun theHidersNeverSeeAnybody() {
        at(65)

        assertEquals(emptyList(), game.snapshotFor(hider, now).players.mapNotNull { it.location })
    }

    @Test
    fun noMarkOfACaughtOrLeftHider() {
        at(75)
        game.leave(hider, now)

        assertNull(seen())
    }

    @Test
    fun noGlowWhenItIsOff() {
        val quiet = Game(GameId("q"), "ABC235", seeker, settings.copy(glowEverySeconds = 0, glowForSeconds = 0), now)
        quiet.addPlayer(seeker, "Seeker", now)
        quiet.addPlayer(hider, "Hider", now)
        quiet.start(seeker, setOf(seeker), { "00112233445566778899aabbccddeeff00112233" }, now)
        now += 60_000
        quiet.advance(now)
        repeat(40) {
            now += 2_000
            quiet.recordLocations(hider, listOf(LocationSample(center, 5.0, now)), now)
            quiet.advance(now)
        }

        assertNull(quiet.snapshotFor(seeker, now).players.single { it.id == hider }.location)
    }
}
