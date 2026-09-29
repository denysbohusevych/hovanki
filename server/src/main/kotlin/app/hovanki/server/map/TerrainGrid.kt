package app.hovanki.server.map

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.geo.offsetFrom
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.TerrainAreas
import app.hovanki.shared.protocol.ZoneCircle
import app.hovanki.shared.rules.ZoneArea
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** The kind of ground of one cell of a [TerrainGrid] (docs/adr/0010-big-games.md). */
enum class Terrain {
    /** Built-up blocks: streets, yards, arches. */
    DENSE,

    /** Woods and thickets. */
    FOREST,

    /** Parks, gardens, trees among lawns, and ground the map says nothing about. */
    MIXED,

    /** Fields, steppe, beaches, sports grounds, large squares. */
    OPEN,

    /** Houses and water: nobody hides there, not part of the playing area. */
    BLOCKED,
}

/**
 * The ground under an area, cell by cell (docs/adr/0010-big-games.md): [columns] × [rows] square cells of [cellMeters]
 * centered on [origin], row 0 the southmost. The game keeps it for its zone and counts the capacity of whatever zone is
 * in force on it. A few tens of kilobytes, no coordinates of anybody.
 */
class TerrainGrid(
    val origin: GeoPoint,
    val cellMeters: Double,
    val columns: Int,
    val rows: Int,
    private val cells: ByteArray,
) {
    init {
        require(columns > 0 && rows > 0 && cells.size == columns * rows) { "The grid needs its cells" }
    }

    private val west = -columns * cellMeters / 2
    private val south = -rows * cellMeters / 2

    fun at(column: Int, row: Int): Terrain = TERRAINS[cells[row * columns + column].toInt()]

    /** The ground of the cell holding [point]; null outside the grid. */
    fun at(point: GeoPoint): Terrain? {
        val offset = point.offsetFrom(origin)
        val column = floor((offset.eastMeters - west) / cellMeters).toInt()
        val row = floor((offset.northMeters - south) / cellMeters).toInt()
        if (column !in 0 until columns || row !in 0 until rows) return null
        return at(column, row)
    }

    /** The center of cell ([column], [row]) on the globe. */
    fun centerOf(column: Int, row: Int): GeoPoint =
        origin.moveBy(eastMeters = west + (column + 0.5) * cellMeters, northMeters = south + (row + 0.5) * cellMeters)

    /** Square meters of each kind of ground in [zone]: the cells whose centers are in it. */
    fun areasWithin(zone: ZoneArea): TerrainAreas {
        val counts = LongArray(TERRAINS.size)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                if (zone.contains(centerOf(column, row))) counts[cells[row * columns + column].toInt()]++
            }
        }
        val cell = cellMeters * cellMeters
        fun squareMeters(terrain: Terrain) = (counts[terrain.ordinal] * cell).roundToLong()
        return TerrainAreas(
            denseSquareMeters = squareMeters(Terrain.DENSE),
            forestSquareMeters = squareMeters(Terrain.FOREST),
            mixedSquareMeters = squareMeters(Terrain.MIXED),
            openSquareMeters = squareMeters(Terrain.OPEN),
            blockedSquareMeters = squareMeters(Terrain.BLOCKED),
        )
    }

    companion object {
        private val TERRAINS = Terrain.entries

        /** Cells no smaller than this: finer adds nothing to an estimate of players. */
        const val MIN_CELL_METERS = 10.0

        /** At most this many cells (200 × 200): a zone of kilometers gets larger cells. */
        const val MAX_CELLS = 40_000

        /** The cell size for a grid over [area]. */
        fun cellMetersFor(area: ZoneCircle): Double =
            maxOf(MIN_CELL_METERS, 2 * area.radiusMeters / sqrt(MAX_CELLS.toDouble()))

        /** Cells per side of a grid over [area] with cells of [cellMeters]. */
        fun sideFor(area: ZoneCircle, cellMeters: Double): Int = ceil(2 * area.radiusMeters / cellMeters).toInt()

        /** The same [terrain] everywhere over [area] (the test source). */
        fun uniform(area: ZoneCircle, terrain: Terrain): TerrainGrid {
            val cell = cellMetersFor(area)
            val side = sideFor(area, cell)
            val cells = ByteArray(side * side) { terrain.ordinal.toByte() }
            return TerrainGrid(area.center, cell, side, side, cells)
        }
    }
}
