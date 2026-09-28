package app.hovanki.shared.rules

import app.hovanki.shared.protocol.AreaNorms
import app.hovanki.shared.protocol.CapacityState
import app.hovanki.shared.protocol.TerrainAreas
import app.hovanki.shared.protocol.ZoneCapacity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapacityTest {
    private val norms = AreaNorms()

    @Test
    fun eachKindOfGroundCountsByItsOwnNorm() {
        // 20 000 m² of blocks (20 players), 15 000 m² of woods (10), 25 000 m² of park (10), 50 000 m² of field (5).
        val areas = TerrainAreas(
            denseSquareMeters = 20_000,
            forestSquareMeters = 15_000,
            mixedSquareMeters = 25_000,
            openSquareMeters = 50_000,
            blockedSquareMeters = 1_000_000,
        )

        assertEquals(45, Capacity.players(areas, norms))
    }

    @Test
    fun housesAndWaterFitNobody() {
        assertEquals(0, Capacity.players(TerrainAreas(blockedSquareMeters = 5_000_000), norms))
    }

    @Test
    fun roundsDown() {
        assertEquals(2, Capacity.players(TerrainAreas(denseSquareMeters = 2_999), norms))
    }

    @Test
    fun theAdminsNormsCount() {
        val roomy = norms.copy(denseSquareMeters = 2_000)

        assertEquals(5, Capacity.players(TerrainAreas(denseSquareMeters = 10_000), roomy))
    }

    @Test
    fun mostlyOpenGroundHasFewCovers() {
        assertTrue(Capacity.fewCovers(TerrainAreas(denseSquareMeters = 4_000, openSquareMeters = 6_000)))
        assertFalse(Capacity.fewCovers(TerrainAreas(denseSquareMeters = 6_000, openSquareMeters = 4_000)))
        // Houses and water are not part of the playing area.
        assertTrue(Capacity.fewCovers(TerrainAreas(openSquareMeters = 6_000, blockedSquareMeters = 100_000)))
        assertFalse(Capacity.fewCovers(TerrainAreas()))
    }

    @Test
    fun theLobbyWarnsWhenCrowdedOrOpenUntilTheHostPlaysAnyway() {
        val fits24 = ZoneCapacity(CapacityState.READY, players = 24, areas = TerrainAreas(denseSquareMeters = 24_000))

        assertFalse(Capacity.needsWarning(fits24, players = 24))
        assertTrue(Capacity.needsWarning(fits24, players = 30))
        assertTrue(Capacity.isCrowded(fits24, players = 30))
        assertFalse(Capacity.needsWarning(fits24.copy(accepted = true), players = 30))
        assertTrue(Capacity.needsWarning(fits24.copy(fewCovers = true), players = 2))
    }

    @Test
    fun noWarningWithoutAnEstimate() {
        assertFalse(Capacity.needsWarning(null, players = 100))
        assertFalse(Capacity.needsWarning(ZoneCapacity(CapacityState.LOADING), players = 100))
        assertFalse(Capacity.needsWarning(ZoneCapacity(CapacityState.UNAVAILABLE), players = 100))
    }

    @Test
    fun normsMustBeSensible() {
        assertTrue(Capacity.isValid(norms))
        assertFalse(Capacity.isValid(norms.copy(openSquareMeters = 0)))
        assertFalse(Capacity.isValid(norms.copy(forestSquareMeters = 2_000_000)))
    }
}
