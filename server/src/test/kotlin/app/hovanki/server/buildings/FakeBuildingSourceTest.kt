package app.hovanki.server.buildings

import app.hovanki.shared.debug.DebugBuildings
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ZoneCircle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FakeBuildingSourceTest {
    private val source = FakeBuildingSource()

    @Test
    fun theTestQuarterNextToAnyZoneCenter() {
        val center = GeoPoint(50.4476, 30.5396)
        val buildings = source.load(ZoneCircle(center, 550.0))

        assertEquals(DebugBuildings.around(center).buildings, buildings.buildings)
        assertEquals(DebugBuildings.around(center).passages, buildings.passages)
    }

    @Test
    fun noBuildingDataAroundNullIsland() {
        val nearby = DebugBuildings.NO_DATA_AT.moveBy(eastMeters = 300.0, northMeters = -200.0)

        assertFailsWith<BuildingsUnavailableException> { source.load(ZoneCircle(nearby, 550.0)) }
    }
}
