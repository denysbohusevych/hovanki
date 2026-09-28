package app.hovanki.server.game

import app.hovanki.server.map.Terrain
import app.hovanki.server.map.TerrainGrid
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.AreaNorms
import app.hovanki.shared.protocol.CapacityState
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.ZoneCircle
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.protocol.ZoneShape
import app.hovanki.shared.rules.Capacity
import app.hovanki.shared.rules.boundingCircle
import app.hovanki.shared.rules.shrinkingZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How many players the zone fits, and the host who plays anyway (docs/adr/0010-big-games.md). */
class GameCapacityTest {
    private val center = GeoPoint(50.4501, 30.5234)

    /** 50 m: about 7 850 m², 80 cells of 10 m on the grid: 8 players on built-up ground. */
    private val settings = GameSettings(zone = shrinkingZone(center, initialRadiusMeters = 50.0, steps = 0))
    private val host = PlayerId("host")
    private val anna = PlayerId("anna")
    private var now = 1_700_000_000_000L
    private val game = Game(GameId("g"), "ABC234", host, settings, now).apply {
        addPlayer(host, "Host", now)
        addPlayer(anna, "Anna", now)
    }

    private fun ground(terrain: Terrain, radiusMeters: Double = 70.0) =
        TerrainGrid.uniform(ZoneCircle(center, radiusMeters), terrain)

    private fun join(count: Int) = repeat(count) { game.addPlayer(PlayerId("p$it"), "P$it", now) }

    @Test
    fun unknownUntilTheGroundIsRead() {
        val capacity = game.snapshotFor(host, now).capacity

        assertEquals(CapacityState.LOADING, capacity?.state)
        assertNull(capacity?.players)
        assertFalse(Capacity.needsWarning(capacity, 30))
    }

    @Test
    fun theZoneFitsPlayersByItsGround() {
        game.onTerrainLoaded(ground(Terrain.DENSE))

        val capacity = game.snapshotFor(anna, now).capacity!!
        assertEquals(CapacityState.READY, capacity.state)
        assertEquals(8, capacity.players)
        assertFalse(capacity.fewCovers)
        assertEquals(8, game.adminView(now).capacity)
    }

    @Test
    fun openGroundFitsFewAndHasFewCovers() {
        game.onTerrainLoaded(ground(Terrain.OPEN))

        val capacity = game.snapshotFor(host, now).capacity!!
        assertEquals(0, capacity.players)
        assertTrue(capacity.fewCovers)
        assertTrue(Capacity.needsWarning(capacity, 2))
    }

    @Test
    fun theGamesNormsCount() {
        val roomy = Game(GameId("h"), "ABC235", host, settings, now, AreaNorms(denseSquareMeters = 500)).apply {
            addPlayer(host, "Host", now)
            onTerrainLoaded(ground(Terrain.DENSE))
        }

        assertEquals(16, roomy.capacity().players)
    }

    @Test
    fun theHostPlaysAnywayAndIsWarnedNoMore() {
        game.onTerrainLoaded(ground(Terrain.DENSE))
        join(8)
        assertTrue(Capacity.needsWarning(game.snapshotFor(host, now).capacity, 10))

        val refused = assertFailsWith<GameException> { game.acceptCrowding(anna, now) }
        assertEquals(ErrorCode.FORBIDDEN, refused.code)
        game.acceptCrowding(host, now)

        val capacity = game.snapshotFor(anna, now).capacity
        assertTrue(capacity!!.accepted)
        assertFalse(Capacity.needsWarning(capacity, 10))
        assertTrue(game.adminView(now).crowdingAccepted)
    }

    @Test
    fun aNewZoneIsCountedAgainButTheHostsChoiceStays() {
        game.onTerrainLoaded(ground(Terrain.DENSE))
        game.acceptCrowding(host, now)
        val larger = settings.copy(zone = shrinkingZone(center, initialRadiusMeters = 100.0, steps = 0))

        game.updateSettings(host, larger, now)

        assertEquals(CapacityState.LOADING, game.capacity().state)
        assertTrue(game.capacity().accepted)
        // What was read for the old zone arrives late: dropped.
        game.onTerrainLoaded(ground(Terrain.OPEN), revision = 0)
        assertEquals(CapacityState.LOADING, game.capacity().state)
        game.onTerrainLoaded(ground(Terrain.DENSE, larger.zone.boundingCircle().radiusMeters * 1.3), revision = 1)
        assertEquals(31, game.capacity().players)
    }

    @Test
    fun theZoneByStreetsIsCountedOnceItIsThere() {
        val streets = Game(
            GameId("s"),
            "ABC236",
            host,
            settings.copy(zoneShape = ZoneShape.STREETS),
            now,
        ).apply { addPlayer(host, "Host", now) }
        streets.onTerrainLoaded(ground(Terrain.DENSE))
        assertEquals(8, streets.capacity().players, "the circle meanwhile")

        // A block of 100 × 40 m.
        val block = ZonePolygon(
            listOf(
                center.moveBy(-50.0, -20.0),
                center.moveBy(50.0, -20.0),
                center.moveBy(50.0, 20.0),
                center.moveBy(-50.0, 20.0),
                center.moveBy(-50.0, -20.0),
            ),
        )
        streets.onStreetZoneBuilt(listOf(block))

        assertEquals(4, streets.capacity().players)
    }

    @Test
    fun noGroundNoEstimate() {
        game.onTerrainUnavailable()

        assertEquals(CapacityState.UNAVAILABLE, game.capacity().state)
        assertNull(game.capacity().players)
    }

    @Test
    fun givenUpOnAfterAWhile() {
        now += Game.MAP_PATIENCE_MILLIS
        game.advance(now)

        assertEquals(CapacityState.UNAVAILABLE, game.capacity().state)
    }

    @Test
    fun onlyInTheLobby() {
        game.start(host, setOf(anna), { "3132333435363738393031323334353637383930" }, now)

        val error = assertFailsWith<GameException> { game.acceptCrowding(host, now) }
        assertEquals(ErrorCode.WRONG_STATE, error.code)
    }
}
