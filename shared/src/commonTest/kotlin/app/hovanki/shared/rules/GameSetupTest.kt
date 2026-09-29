package app.hovanki.shared.rules

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ZoneShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameSetupTest {
    private val center = GeoPoint(50.4501, 30.5234)

    @Test
    fun theDefaultIsTheZoneAppsCreatedBeforeWithTheGlowOnAndTheSqueeze() {
        val settings = GameSetup().settings(center)

        assertEquals(shrinkingZone(center).withEndgame(1800), settings.zone)
        assertEquals(shrinkingZone(center).stages, settings.zone.stages.take(3))
        assertEquals(300, settings.hidingSeconds)
        assertEquals(1800, settings.seekingSeconds)
        assertEquals(300, settings.glowEverySeconds)
        assertEquals(5, settings.glowForSeconds)
        assertEquals(ZoneShape.CIRCLE, settings.zoneShape)
    }

    @Test
    fun theZoneShrinksOverMostOfTheSearch() {
        val settings = GameSetup(radiusMeters = 1000, seekingMinutes = 60).settings(center)

        val stages = settings.zone.stages.take(GameSetup.SHRINK_STEPS)
        assertEquals(200.0, stages.last().target.radiusMeters)
        val shrinking = stages.sumOf { it.holdSeconds + it.shrinkSeconds }
        assertEquals(0.7, shrinking / 3600.0, 0.01)
    }

    @Test
    fun andKeepsSqueezingAlmostToTheEnd() {
        for (radius in listOf(150, 500, 1500)) {
            val settings = GameSetup(radiusMeters = radius, seekingMinutes = 30).settings(center)
            val zone = settings.zone

            assertEquals(GameSetup.SHRINK_STEPS + Endgame.STEPS, zone.stages.size, "$radius m")
            val end = zone.stages.sumOf { it.holdSeconds + it.shrinkSeconds }
            assertEquals(Endgame.END_SHARE, end / 1800.0, 0.01)
            assertEquals(Endgame.finalRadiusMeters(radius.toDouble()), zone.stages.last().target.radiusMeters, 0.01)
            assertTrue(zone.stages.zipWithNext().all { (a, b) -> b.target.radiusMeters < a.target.radiusMeters })
            // The last minutes: the zone no larger than about a catch.
            val atTheEnd = zone.stateAt(1750 * 1000L)
            assertEquals(Endgame.finalRadiusMeters(radius.toDouble()), atTheEnd.current.radiusMeters, 0.01)
            assertNull(SettingsLimits.problem(settings))
        }
        assertEquals(30.0, Endgame.finalRadiusMeters(500.0))
        assertEquals(75.0, Endgame.finalRadiusMeters(1500.0))
    }

    @Test
    fun aScheduleThatReachesTheEndStaysAsItIs() {
        val short = shrinkingZone(center, steps = 3, holdSeconds = 500, shrinkSeconds = 100)

        assertEquals(short, short.withEndgame(1800))
        assertEquals(shrinkingZone(center, steps = 0), shrinkingZone(center, steps = 0).withEndgame(1800))
    }

    @Test
    fun aZoneThatStays() {
        assertEquals(emptyList(), GameSetup(shrinks = false).settings(center).zone.stages)
    }

    @Test
    fun noGlow() {
        val settings = GameSetup(glowEveryMinutes = 0).settings(center)

        assertEquals(0, settings.glowEverySeconds)
        assertEquals(0, settings.glowForSeconds)
    }

    @Test
    fun backFromTheSettings() {
        for (setup in listOf(
            GameSetup(),
            GameSetup(350, 3, 45, shrinks = false, ZoneShape.STREETS, 2, 20),
            GameSetup(glowEveryMinutes = 0),
        )) {
            assertEquals(setup, GameSetup.of(setup.settings(center)))
        }
    }

    @Test
    fun theServerTakesWhatTheScreenMakes() {
        for (radius in GameSetup.RADIUS_METERS step GameSetup.RADIUS_STEP_METERS) {
            val settings = GameSetup(
                radiusMeters = radius,
                seekingMinutes = 90,
                hidingMinutes = 15,
                glowEveryMinutes = 1,
                glowForSeconds = 59,
            )
                .settings(center)
            assertNull(SettingsLimits.problem(settings), "radius $radius")
        }
    }

    @Test
    fun aGlowEndsBeforeTheNextOne() {
        val setup = GameSetup(glowEveryMinutes = 1, glowForSeconds = 60).coerced()

        assertEquals(59, setup.glowForSeconds)
        assertNull(SettingsLimits.problem(setup.settings(center)))
        assertEquals(60, GameSetup(glowEveryMinutes = 2, glowForSeconds = 60).coerced().glowForSeconds)
    }

    @Test
    fun theServerRefusesTheExtremes() {
        val fine = GameSetup().settings(center)

        assertNotNull(SettingsLimits.problem(fine.copy(zone = shrinkingZone(center, initialRadiusMeters = 10_000.0))))
        assertNotNull(SettingsLimits.problem(fine.copy(seekingSeconds = 0)))
        assertNotNull(SettingsLimits.problem(fine.copy(hidingSeconds = 100_000)))
        assertNotNull(SettingsLimits.problem(fine.copy(glowEverySeconds = 10, glowForSeconds = 10)))
        assertNull(SettingsLimits.problem(GameSettings(zone = shrinkingZone(center), hidingSeconds = 0)))
    }

    @Test
    fun openBuildingsAreFewAndInTheZone() {
        val fine = GameSetup().settings(center)
        val near = List(SettingsLimits.MAX_OPEN_BUILDINGS) { center.moveBy(it * 10.0, 0.0) }

        assertNull(SettingsLimits.problem(fine.copy(openBuildings = near)))
        assertNull(SettingsLimits.problem(fine.copy(openBuildings = listOf(center.moveBy(530.0, 0.0)))), "the margin")
        assertNotNull(SettingsLimits.problem(fine.copy(openBuildings = near + center)), "too many")
        assertNotNull(SettingsLimits.problem(fine.copy(openBuildings = listOf(center.moveBy(600.0, 0.0)))), "far")
        assertEquals(null, fine.openBuildings, "a new setup says nothing about them")
    }
}
