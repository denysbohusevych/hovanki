package app.hovanki.server.game

import app.hovanki.shared.debug.DebugBuildings
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.BuildingArea
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.rules.shrinkingZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The buildings the host opens for hiding (docs/adr/0014-settings-lobby-redesign-open-buildings.md, section 4): the
 * rule leaves them alone, the players get them apart from the forbidden ones, and opening one loads nothing again.
 */
class GameOpenBuildingsTest {
    private val center = GeoPoint(50.4501, 30.5234)
    private val settings =
        GameSettings(zone = shrinkingZone(center, steps = 0), hidingSeconds = 60, seekingSeconds = 600)
    private val host = PlayerId("host")
    private val seeker = PlayerId("seeker")
    private val hider = PlayerId("hider")

    private var now = 1_700_000_000_000L
    private val game = Game(GameId("g"), "ABC234", host, settings, now)

    private val quarter = DebugBuildings.around(center)
    private val block = quarter.buildings.single()
    private val insideBlock = center.moveBy(DebugBuildings.INSIDE_EAST, DebugBuildings.INSIDE_NORTH)

    /** A 20 × 20 m shed south of the zone center, well away from the block. */
    private val shed = BuildingArea(
        listOf(at(0.0, -80.0), at(20.0, -80.0), at(20.0, -60.0), at(0.0, -60.0), at(0.0, -80.0)),
    )
    private val insideShed = at(10.0, -70.0)

    private fun at(east: Double, north: Double) = center.moveBy(east, north)

    private fun lobby() {
        game.addPlayer(host, "Host", now)
        game.addPlayer(seeker, "Seeker", now)
        game.addPlayer(hider, "Hider", now)
        game.onBuildingsLoaded(listOf(block, shed), quarter.passages)
    }

    private fun start() {
        game.start(host, setOf(seeker), { "00112233445566778899aabbccddeeff00112233" }, now)
        now += settings.hidingSeconds * 1000L
        game.advance(now)
        assertEquals(GamePhase.SEEKING, game.phase)
    }

    private fun stay(point: GeoPoint, seconds: Int) {
        repeat(seconds / 3) {
            now += 3_000
            game.recordLocations(seeker, listOf(LocationSample(center, 5.0, now)), now)
            game.recordLocations(hider, listOf(LocationSample(point, 5.0, now)), now)
            game.advance(now)
        }
    }

    private fun seenBySeeker() = game.snapshotFor(seeker, now).players.single { it.id == hider }.location

    private fun open(vararg points: GeoPoint) =
        game.updateSettings(host, settings.copy(openBuildings = points.toList()), now)

    @Test
    fun hidingInAnOpenBuildingIsFine() {
        lobby()
        open(insideBlock)
        start()

        stay(insideBlock, settings.rules.insideBuildingRevealSeconds + 15)

        assertNull(game.snapshotFor(hider, now).me.insideBuildingRevealAtMillis, "no warning")
        assertNull(seenBySeeker(), "not revealed")
    }

    @Test
    fun theOtherBuildingsStayForbidden() {
        lobby()
        open(insideBlock)
        start()

        stay(insideShed, settings.rules.insideBuildingRevealSeconds + 15)

        assertEquals(VisibilityReason.INSIDE_BUILDING, assertNotNull(seenBySeeker()).cause)
    }

    @Test
    fun playersGetTheOpenOnesApartAndNothingIsLoadedAgain() {
        lobby()
        val revision = game.mapRevision

        assertFalse(open(insideBlock), "no new zone, no new map")

        assertEquals(revision, game.mapRevision)
        val buildings = game.buildingsFor(hider, now)
        assertEquals(listOf(shed), buildings.buildings)
        assertEquals(listOf(block), buildings.open)
        assertEquals(quarter.passages, buildings.passages)
        assertEquals(listOf(insideBlock), game.snapshotFor(hider, now).settings.openBuildings)

        open()
        assertEquals(listOf(block, shed), game.buildingsFor(hider, now).buildings, "closed again")
        assertEquals(emptyList(), game.buildingsFor(hider, now).open)
    }

    @Test
    fun aSetupThatSaysNothingKeepsThemWhileTheyAreByTheZone() {
        lobby()
        open(insideBlock)

        // An older app, or the host changing the time: no list in the request.
        game.updateSettings(host, settings.copy(seekingSeconds = 900), now)
        assertEquals(listOf(insideBlock), game.settings.openBuildings)
        assertEquals(listOf(block), game.buildingsFor(hider, now).open)

        // A larger zone loads its buildings again; the point opens the same block in them.
        val larger = settings.copy(zone = shrinkingZone(center, initialRadiusMeters = 800.0, steps = 0))
        assertTrue(game.updateSettings(host, larger, now))
        game.onBuildingsLoaded(listOf(block, shed), quarter.passages)
        assertEquals(listOf(block), game.buildingsFor(hider, now).open)

        // A zone two kilometers away: the block is not by it any more.
        val away = settings.copy(zone = shrinkingZone(center.moveBy(2_000.0, 0.0), steps = 0))
        game.updateSettings(host, away, now)
        assertEquals(emptyList(), game.settings.openBuildings)
    }

    @Test
    fun aGameAlwaysHasAList() {
        assertEquals(null, settings.openBuildings)
        assertEquals(emptyList(), game.settings.openBuildings)
    }
}
