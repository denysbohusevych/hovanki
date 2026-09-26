package app.hovanki.shared.rules

import app.hovanki.shared.geo.offsetFrom
import app.hovanki.shared.protocol.BuildingArea
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.Passage
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The buildings of one game, ready for point checks (docs/adr/0003-map-and-buildings.md): rings are projected to
 * meters on a local plane around [origin] (fine for a zone of a few kilometers) and bucketed into a grid, so a check
 * only looks at the buildings and passages nearby.
 */
class BuildingMap(buildings: List<BuildingArea>, passages: List<Passage>, private val origin: GeoPoint) {
    private val shapes = buildings.filter { it.outline.size >= 3 }.map { area ->
        Shape(project(area.outline), area.holes.filter { it.size >= 3 }.map(::project))
    }
    private val corridors = passages.filter { it.path.size >= 2 && it.widthMeters > 0 }.map { passage ->
        Corridor(project(passage.path), passage.widthMeters / 2)
    }
    private val shapeCells = index(shapes.map { it.outline.bounds })
    private val corridorCells = index(corridors.map { it.path.bounds.grownBy(it.halfWidth) })

    val isEmpty: Boolean get() = shapes.isEmpty()

    /**
     * How deep [point] lies inside a building: the distance to its nearest wall, courtyard or passage. Null when it is
     * not inside any building (outside, in a courtyard, in an arch or passage). Of overlapping buildings (a building
     * and its part, a block of adjoining houses) the deepest counts.
     */
    fun depthInsideMeters(point: GeoPoint): Double? {
        val offset = point.offsetFrom(origin)
        val x = offset.eastMeters
        val y = offset.northMeters
        var depth: Double? = null
        for (index in shapeCells[cellKey(cell(x), cell(y))].orEmpty()) {
            val shape = shapes[index]
            if (shape.contains(x, y)) depth = max(depth ?: 0.0, shape.distanceToEdge(x, y))
        }
        var inside = depth ?: return null
        // A passage within that distance is outdoors: the player is only as deep as the way out through it.
        for (index in cellsAround(corridorCells, x, y, inside)) {
            val corridor = corridors[index]
            val toCorridor = corridor.path.distanceToPath(x, y, closed = false) - corridor.halfWidth
            if (toCorridor <= 0) return null
            inside = min(inside, toCorridor)
        }
        return inside
    }

    private fun project(points: List<GeoPoint>): Ring {
        val offsets = points.map { it.offsetFrom(origin) }
        return Ring(
            DoubleArray(offsets.size) { offsets[it].eastMeters },
            DoubleArray(offsets.size) { offsets[it].northMeters },
        )
    }

    private fun index(bounds: List<Bounds>): Map<Long, List<Int>> {
        val cells = HashMap<Long, MutableList<Int>>()
        bounds.forEachIndexed { index, box ->
            for (cx in cell(box.minX)..cell(box.maxX)) {
                for (cy in cell(box.minY)..cell(box.maxY)) cells.getOrPut(cellKey(cx, cy)) { ArrayList() } += index
            }
        }
        return cells
    }

    private fun cellsAround(cells: Map<Long, List<Int>>, x: Double, y: Double, radius: Double): Set<Int> {
        val found = LinkedHashSet<Int>()
        for (cx in cell(x - radius)..cell(x + radius)) {
            for (cy in cell(y - radius)..cell(y + radius)) cells[cellKey(cx, cy)]?.let(found::addAll)
        }
        return found
    }

    private class Bounds(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double) {
        fun contains(x: Double, y: Double) = x in minX..maxX && y in minY..maxY

        fun grownBy(meters: Double) = Bounds(minX - meters, maxX + meters, minY - meters, maxY + meters)
    }

    /** A ring (closed polygon) or a path (open line) in local meters. */
    private class Ring(val xs: DoubleArray, val ys: DoubleArray) {
        val bounds = Bounds(xs.min(), xs.max(), ys.min(), ys.max())

        /** Even-odd rule; the closing edge is implied. */
        fun contains(x: Double, y: Double): Boolean {
            if (!bounds.contains(x, y)) return false
            var inside = false
            var j = xs.size - 1
            for (i in xs.indices) {
                if ((ys[i] > y) != (ys[j] > y) && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) {
                    inside = !inside
                }
                j = i
            }
            return inside
        }

        fun distanceToPath(x: Double, y: Double, closed: Boolean): Double {
            var best = Double.MAX_VALUE
            for (i in 0 until xs.size - 1) best = min(best, segmentDistance(x, y, i, i + 1))
            if (closed) best = min(best, segmentDistance(x, y, xs.size - 1, 0))
            return best
        }

        private fun segmentDistance(x: Double, y: Double, from: Int, to: Int): Double {
            val dx = xs[to] - xs[from]
            val dy = ys[to] - ys[from]
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared == 0.0) 0.0 else (((x - xs[from]) * dx + (y - ys[from]) * dy) / lengthSquared)
            val along = t.coerceIn(0.0, 1.0)
            val px = xs[from] + along * dx - x
            val py = ys[from] + along * dy - y
            return sqrt(px * px + py * py)
        }
    }

    private class Shape(val outline: Ring, val holes: List<Ring>) {
        fun contains(x: Double, y: Double) = outline.contains(x, y) && holes.none { it.contains(x, y) }

        fun distanceToEdge(x: Double, y: Double): Double =
            holes.fold(outline.distanceToPath(x, y, closed = true)) { best, hole ->
                min(best, hole.distanceToPath(x, y, closed = true))
            }
    }

    private class Corridor(val path: Ring, val halfWidth: Double)

    private companion object {
        const val CELL_METERS = 50.0

        fun cell(meters: Double): Int = floor(meters / CELL_METERS).toInt()

        fun cellKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
    }
}

/**
 * "No hiding in buildings" without making GPS the judge (docs/adr/0003-map-and-buildings.md): near buildings a fix
 * can jump 20–30 m, also into a building. A fix only counts as inside when its whole accuracy circle plus a margin is
 * within the walls, and a player only counts as inside on several such fixes in a row. The server never eliminates
 * for it: it warns the hider and later reveals them to the seekers.
 */
object BuildingRules {
    fun isClearlyInside(fix: LocationSample, buildings: BuildingMap, rules: GameRules): Boolean {
        if (!fix.isUsable(rules)) return false
        val depth = buildings.depthInsideMeters(fix.point) ?: return false
        return depth > fix.accuracyMeters + rules.buildingWallMarginMeters
    }

    /** Enough recent usable fixes, and all of them clearly inside a building. */
    fun isConfidentlyInside(
        recentUsableFixes: List<LocationSample>,
        buildings: BuildingMap,
        rules: GameRules,
    ): Boolean = recentUsableFixes.size >= rules.minFixesForDecision &&
        recentUsableFixes.all { isClearlyInside(it, buildings, rules) }

    /**
     * Out again after a warning: the latest [GameRules.minFixesForDecision] usable fixes are all not clearly inside
     * (outside, at a wall, in a passage). One fix that jumps out resets nothing; in doubt, the player is out.
     */
    fun hasLeft(recentUsableFixes: List<LocationSample>, buildings: BuildingMap, rules: GameRules): Boolean =
        recentUsableFixes.size >= rules.minFixesForDecision &&
            recentUsableFixes.takeLast(rules.minFixesForDecision).none { isClearlyInside(it, buildings, rules) }
}
