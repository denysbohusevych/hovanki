package app.hovanki.server.game

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.StreetZoneState
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.protocol.ZoneShape
import app.hovanki.shared.rules.shrinkingZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The zone by streets in the rules: out of the polygon counts, not out of the circle. */
class GameStreetZoneTest {
    private val center = GeoPoint(50.4501, 30.5234)
    private val settings = GameSettings(
        zone = shrinkingZone(center, initialRadiusMeters = 300.0, steps = 0),
        hidingSeconds = 0,
        seekingSeconds = 600,
        zoneShape = ZoneShape.STREETS,
    )
    private val seeker = PlayerId("seeker")
    private val hider = PlayerId("hider")
    private var now = 1_700_000_000_000L
    private val game = Game(GameId("g"), "ABC234", seeker, settings, now).apply {
        addPlayer(seeker, "Seeker", now)
        addPlayer(hider, "Hider", now)
    }

    /** A 200 × 800 m strip, east–west: inside the circle only near the center, far outside it to the east and west. */
    private val strip = ZonePolygon(
        listOf(
            center.moveBy(-400.0, -100.0),
            center.moveBy(400.0, -100.0),
            center.moveBy(400.0, 100.0),
            center.moveBy(-400.0, 100.0),
            center.moveBy(-400.0, -100.0),
        ),
    )

    private fun report(point: GeoPoint, seconds: Int) {
        repeat(seconds / 2) {
            now += 2_000
            game.recordLocations(hider, listOf(LocationSample(point, 5.0, now)), now)
            game.recordLocations(seeker, listOf(LocationSample(center, 5.0, now)), now)
            game.advance(now)
        }
    }

    private fun startWithTheStrip() {
        game.onStreetZoneBuilt(listOf(strip))
        game.start(seeker, setOf(seeker), { SECRET }, now)
        game.advance(now)
        assertEquals(GamePhase.SEEKING, game.phase)
    }

    @Test
    fun outsideTheCircleButOnTheStreetsIsInside() {
        startWithTheStrip()

        report(center.moveBy(350.0, 0.0), 30)

        assertNull(game.snapshotFor(hider, now).me.outOfZoneDeadlineMillis)
    }

    @Test
    fun insideTheCircleButOffTheStreetsIsOutside() {
        startWithTheStrip()

        report(center.moveBy(0.0, 150.0), 30)

        assertNotNull(game.snapshotFor(hider, now).me.outOfZoneDeadlineMillis)
    }

    /**
     * While a stage shrinks, the blocks that go are eaten from the outside in (ZoneArea.Shrinking), as the map shows:
     * a hider at the far end of the strip is out when the closing circle passes, long before the stage is over.
     */
    @Test
    fun theBlocksThatGoAreEatenFromTheOutsideIn() {
        val square = ZonePolygon(
            listOf(
                center.moveBy(-100.0, -100.0),
                center.moveBy(100.0, -100.0),
                center.moveBy(100.0, 100.0),
                center.moveBy(-100.0, 100.0),
                center.moveBy(-100.0, -100.0),
            ),
        )
        // 30 s hold, then 60 s from the strip to the square in its middle.
        val schedule =
            shrinkingZone(center, initialRadiusMeters = 300.0, finalRadiusMeters = 100.0, steps = 1, holdSeconds = 30)
                .let { it.copy(stages = it.stages.map { stage -> stage.copy(shrinkSeconds = 60) }) }
        val shrinking = Game(GameId("s"), "ABC235", seeker, settings.copy(zone = schedule), now).apply {
            addPlayer(seeker, "Seeker", now)
            addPlayer(hider, "Hider", now)
            onStreetZoneBuilt(listOf(strip, square))
            start(seeker, setOf(seeker), { SECRET }, now)
            advance(now)
        }
        val farEnd = center.moveBy(350.0, 0.0)
        fun stayUntil(seconds: Int) {
            val until = shrinking.debugState(now).phaseStartedAtMillis + seconds * 1000L
            while (now < until) {
                now += 2_000
                shrinking.recordLocations(hider, listOf(LocationSample(farEnd, 5.0, now)), now)
                shrinking.recordLocations(seeker, listOf(LocationSample(center, 5.0, now)), now)
                shrinking.advance(now)
            }
        }

        stayUntil(40)
        assertNull(shrinking.snapshotFor(hider, now).me.outOfZoneDeadlineMillis, "the circle has not got there yet")
        // The cut closes in from 412 m (the strip's far corner) to 100 m: past 350 m (and the GPS margin) at ~45 s.
        stayUntil(55)
        assertNotNull(shrinking.snapshotFor(hider, now).me.outOfZoneDeadlineMillis, "out halfway through the shrink")
    }

    @Test
    fun theZoneGoesToThePlayersAndTheSnapshotSaysSo() {
        assertEquals(StreetZoneState.LOADING, game.snapshotFor(hider, now).streetZone)

        game.onStreetZoneBuilt(listOf(strip))

        assertEquals(StreetZoneState.READY, game.snapshotFor(hider, now).streetZone)
        assertEquals(listOf(strip), game.streetZoneFor(hider).stages)
    }

    @Test
    fun theWrongNumberOfStagesIsNoZone() {
        game.onStreetZoneBuilt(listOf(strip, strip))

        assertEquals(StreetZoneState.UNAVAILABLE, game.snapshotFor(hider, now).streetZone)
    }

    @Test
    fun withoutStreetsTheCircle() {
        game.onStreetZoneUnavailable()
        game.start(seeker, setOf(seeker), { SECRET }, now)

        report(center.moveBy(350.0, 0.0), 30)

        assertNotNull(game.snapshotFor(hider, now).me.outOfZoneDeadlineMillis)
    }

    @Test
    fun notForeverLoading() {
        now += Game.STREET_ZONE_PATIENCE_MILLIS
        game.advance(now)

        assertEquals(StreetZoneState.UNAVAILABLE, game.snapshotFor(hider, now).streetZone)
    }

    private companion object {
        const val SECRET = "00112233445566778899aabbccddeeff00112233"
    }
}
