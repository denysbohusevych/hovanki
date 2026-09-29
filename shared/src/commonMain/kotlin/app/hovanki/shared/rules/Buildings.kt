package app.hovanki.shared.rules

import app.hovanki.shared.geo.offsetFrom
import app.hovanki.shared.protocol.BuildingArea
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.Passage
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Whether [point] lies in this building: inside its outline and not in one of its courtyards. */
fun BuildingArea.contains(point: GeoPoint): Boolean =
    outline.size >= 3 && ringContains(outline, point) && holes.none { it.size >= 3 && ringContains(it, point) }

/** Even-odd rule on latitude and longitude: exact enough for a building, and scale does not change inside and out. */
private fun ringContains(ring: List<GeoPoint>, point: GeoPoint): Boolean {
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val a = ring[i]
        val b = ring[j]
        if ((a.lat > point.lat) != (b.lat > point.lat) &&
            point.lon < (b.lon - a.lon) * (point.lat - a.lat) / (b.lat - a.lat) + a.lon
        ) {
            inside = !inside
        }
        j = i
    }
    return inside
}

/**
 * The buildings the host opened for hiding (docs/adr/0014-settings-lobby-redesign-open-buildings.md, section 4). The
 * server and the phones split the zone's buildings the same way: a building one of the host's points lies in is open,
 * the rest are forbidden. Adjoining houses come from the tiles as one outline, so a point opens that whole outline.
 */
object OpenBuildings {
    /** [forbidden] go into the rule and are drawn pink; [open] are drawn as open and the rule leaves them alone. */
    data class Split(val forbidden: List<BuildingArea>, val open: List<BuildingArea>)

    fun split(buildings: List<BuildingArea>, points: List<GeoPoint>): Split {
        if (points.isEmpty()) return Split(buildings, emptyList())
        val (open, forbidden) = buildings.partition { building -> points.any { building.contains(it) } }
        return Split(forbidden, open)
    }

    /**
     * The host tapped [tap]: in an open building it closes again (every point in it goes), in a forbidden one it
     * opens (while fewer than [SettingsLimits.MAX_OPEN_BUILDINGS] are open), elsewhere nothing changes.
     */
    fun toggle(points: List<GeoPoint>, buildings: List<BuildingArea>, tap: GeoPoint): List<GeoPoint> {
        val building = buildings.firstOrNull { it.contains(tap) } ?: return points
        val inside = points.filter { building.contains(it) }
        return when {
            inside.isNotEmpty() -> points - inside.toSet()
            openCount(points, buildings) >= SettingsLimits.MAX_OPEN_BUILDINGS -> points
            else -> points + tap
        }
    }

    /** How many buildings [points] open among [buildings]. */
    fun openCount(points: List<GeoPoint>, buildings: List<BuildingArea>): Int =
        buildings.count { building -> points.any { building.contains(it) } }
}

/**
 * [buildings] and [open] as the host's [points] split them now: the server sends the split of the moment it answered,
 * and the host may open or close a building afterwards without a new map revision. Null [points] (an older server):
 * as sent.
 */
fun BuildingsResponse.withOpenBuildings(points: List<GeoPoint>?): BuildingsResponse {
    if (points == null) return this
    val split = OpenBuildings.split(buildings + open, points)
    return copy(buildings = split.forbidden, open = split.open)
}

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
 * "No hiding in buildings" by the dot the player sees on the map (docs/adr/0003-map-and-buildings.md, «Изменение:
 * правило по точке на карте»): GPS indoors is too coarse to fit its accuracy circle within the walls of an ordinary
 * house, so a fix counts by its dot, with a margin at the walls, and a player only counts as inside when the dot stays
 * inside for most of the decision window. The server never eliminates for it: it warns the hider and later reveals
 * them to the seekers, so a false alarm next to a wall costs a step away from it.
 */
object BuildingRules {
    /** Where the dot of one fix is. */
    enum class Spot {
        /** In a building, at least [GameRules.buildingDotMarginMeters] from every wall. */
        INSIDE,

        /** In a building but closer to a wall: GPS can't tell this from just outside, so it decides nothing. */
        AT_WALL,

        /** In the open, in a courtyard or in an arch. */
        OUTSIDE,
    }

    /** Null when the fix is too coarse for the rule or mocked. */
    fun spotOf(fix: LocationSample, buildings: BuildingMap, rules: GameRules): Spot? {
        if (fix.isMock || fix.accuracyMeters > rules.buildingMaxAccuracyMeters) return null
        val depth = buildings.depthInsideMeters(fix.point) ?: return Spot.OUTSIDE
        return if (depth >= rules.buildingDotMarginMeters) Spot.INSIDE else Spot.AT_WALL
    }

    /** Enough recent fixes, and at least [GameRules.buildingInsideShare] of them inside a building. */
    fun isConfidentlyInside(recentFixes: List<LocationSample>, buildings: BuildingMap, rules: GameRules): Boolean {
        val spots = recentFixes.mapNotNull { spotOf(it, buildings, rules) }
        return spots.size >= rules.minFixesForDecision &&
            spots.count { it == Spot.INSIDE } >= spots.size * rules.buildingInsideShare
    }

    /**
     * Out again after a warning: the latest [GameRules.minFixesForDecision] fixes are all outside. One fix that jumps
     * out resets nothing, and neither does a dot at a wall: that is where a player by a window is, too.
     */
    fun hasLeft(recentFixes: List<LocationSample>, buildings: BuildingMap, rules: GameRules): Boolean {
        val spots = recentFixes.mapNotNull { spotOf(it, buildings, rules) }
        return spots.size >= rules.minFixesForDecision &&
            spots.takeLast(rules.minFixesForDecision).all { it == Spot.OUTSIDE }
    }
}
