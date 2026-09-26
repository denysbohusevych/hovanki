package app.hovanki.shared.debug

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.BuildingArea
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.Passage

/**
 * The test quarter the server's fake building source puts next to every zone center (Spring profile `e2e` and tests,
 * docs/adr/0003-map-and-buildings.md), so scenarios play the building rule without an external service. Offsets are
 * meters east / north of the zone center; the quarter is away from where the other scenarios walk.
 *
 * One 40 × 40 m block with an arch through it along its west side:
 * ```
 *   north 130 ┌──┬──────────────┐
 *             │  │              │
 *             │a │    block     │   a: arch, 4 m wide, north–south at east −104
 *             │  │              │
 *   north 90  └──┴──────────────┘
 *          east −110          east −70
 * ```
 */
object DebugBuildings {
    const val WEST = -110.0
    const val EAST = -70.0
    const val SOUTH = 90.0
    const val NORTH = 130.0
    const val ARCH_EAST = -104.0
    const val ARCH_WIDTH = 4.0

    /** Deep inside the block: 14 m to the nearest wall, 18 m to the arch. */
    const val INSIDE_EAST = -84.0
    const val INSIDE_NORTH = 110.0

    fun around(center: GeoPoint): BuildingsResponse {
        fun at(east: Double, north: Double) = center.moveBy(eastMeters = east, northMeters = north)
        val block = listOf(at(WEST, SOUTH), at(EAST, SOUTH), at(EAST, NORTH), at(WEST, NORTH), at(WEST, SOUTH))
        val arch = listOf(at(ARCH_EAST, SOUTH - 5), at(ARCH_EAST, NORTH + 5))
        return BuildingsResponse(
            state = BuildingsState.READY,
            buildings = listOf(BuildingArea(block)),
            passages = listOf(Passage(arch, ARCH_WIDTH)),
        )
    }
}
