package app.hovanki.shared.rules

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.AdminBigGameRequest
import app.hovanki.shared.protocol.AreaNorms
import app.hovanki.shared.protocol.BigGameSetup
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.protocol.ZoneShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BigGamesTest {
    private val park = GeoPoint(50.4501, 30.5234)
    private val square = ZonePolygon(
        listOf(
            park.moveBy(-100.0, -100.0),
            park.moveBy(100.0, -100.0),
            park.moveBy(100.0, 100.0),
            park.moveBy(-100.0, 100.0),
        ),
    )
    private val request = AdminBigGameRequest(
        title = "Saturday",
        startsAtMillis = 0,
        timeZone = "Europe/Kyiv",
        zone = square,
        reason = "test",
    )

    @Test
    fun theAreaOfAFigure() {
        assertEquals(40_000.0, square.areaSquareMeters(), 1.0)
        assertEquals(40_000.0, ZonePolygon(square.outline + square.outline.first()).areaSquareMeters(), 1.0)
    }

    @Test
    fun theSettingsOfABigGame() {
        val settings = BigGameSetup(hidingMinutes = 10, seekingMinutes = 60, glowEveryMinutes = 5, glowForSeconds = 10)
            .settings(park, 700.0)

        assertEquals(ZoneShape.DRAWN, settings.zoneShape)
        assertEquals(600, settings.hidingSeconds)
        assertEquals(3_600, settings.seekingSeconds)
        assertEquals(700.0, settings.zone.initial.radiusMeters)
        assertEquals(140.0, settings.zone.stages.last().target.radiusMeters, 0.01)
        assertEquals(300, settings.glowEverySeconds)
        assertTrue(ZoneShape.DRAWN.hasPolygons && ZoneShape.STREETS.hasPolygons && !ZoneShape.CIRCLE.hasPolygons)
        // Small too: a fifth, no floor of meters.
        assertEquals(20.0, BigGameSetup().settings(park, 100.0).zone.stages.last().target.radiusMeters, 0.01)
    }

    @Test
    fun whatTheServerTakes() {
        assertNull(BigGameLimits.problem(request))
        assertNotNull(BigGameLimits.problem(request.copy(title = "")))
        assertNotNull(BigGameLimits.problem(request.copy(zone = ZonePolygon(square.outline.take(2)))))
        val tiny = ZonePolygon(
            square.outline.map {
                GeoPoint(
                    park.lat + (it.lat - park.lat) / 10,
                    park.lon + (it.lon - park.lon) / 10,
                )
            },
        )
        assertNotNull(BigGameLimits.problem(request.copy(zone = tiny)), "400 m²")
        assertNotNull(BigGameLimits.problem(request.copy(playerLimit = 5_000)))
        assertNotNull(BigGameLimits.problem(request.copy(playerLimit = 10, setup = BigGameSetup(seekers = 10))))
        assertNotNull(
            BigGameLimits.problem(request.copy(setup = BigGameSetup(glowEveryMinutes = 1, glowForSeconds = 60))),
        )
        assertNotNull(BigGameLimits.problem(request.copy(norms = AreaNorms(denseSquareMeters = 0))))
        assertNull(BigGameLimits.problem(request.copy(setup = BigGameSetup(glowEveryMinutes = 0, glowForSeconds = 0))))
    }
}
