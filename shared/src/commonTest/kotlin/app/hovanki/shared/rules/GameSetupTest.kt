package app.hovanki.shared.rules

import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ZoneShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GameSetupTest {
    private val center = GeoPoint(50.4501, 30.5234)

    @Test
    fun theDefaultIsTheZoneAppsCreatedBeforeWithTheGlowOn() {
        val settings = GameSetup().settings(center)

        assertEquals(shrinkingZone(center), settings.zone)
        assertEquals(300, settings.hidingSeconds)
        assertEquals(1800, settings.seekingSeconds)
        assertEquals(300, settings.glowEverySeconds)
        assertEquals(5, settings.glowForSeconds)
        assertEquals(ZoneShape.CIRCLE, settings.zoneShape)
    }

    @Test
    fun theZoneShrinksOverMostOfTheSearch() {
        val settings = GameSetup(radiusMeters = 1000, seekingMinutes = 60).settings(center)

        val stages = settings.zone.stages
        assertEquals(3, stages.size)
        assertEquals(200.0, stages.last().target.radiusMeters)
        val shrinking = stages.sumOf { it.holdSeconds + it.shrinkSeconds }
        assertEquals(0.7, shrinking / 3600.0, 0.01)
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
}
