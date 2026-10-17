package app.hovanki.shared.rules

import app.hovanki.shared.geo.EARTH_RADIUS_METERS
import app.hovanki.shared.protocol.BuildingArea
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.Passage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
 * The buildings of one game, ready for point checks (docs/adr/0003-map-and-buildings.md). A game in a city's center
 * keeps thousands of outlines while it lasts, and the server keeps thousands of games, so the map is compact: every
 * point is two ints of one array (degrees × 10⁷, about a centimeter), with no object for a point or a ring; a grid of
 * cells finds the buildings and passages near a point, and a check projects only theirs to meters on a local plane
 * around the origin it is given, the zone's center (fine for a zone of a few kilometers).
 *
 * The map keeps every building of the zone, the ones the host opened too ([withOpen]): the rule leaves those alone, and
 * the players get all of them back as loaded, to the centimeter ([forbiddenAreas], [openAreas], [passages]).
 */
class BuildingMap private constructor(private val data: Packed, private val open: BooleanArray?) {
    constructor(buildings: List<BuildingArea>, passages: List<Passage>, origin: GeoPoint) :
        this(Packed(buildings, passages, origin), null)

    /** No building the rule judges by. */
    val isEmpty: Boolean get() = forbiddenCount == 0

    /** How many buildings the rule judges by: those not open. */
    val forbiddenCount: Int = data.shapeCount - (open?.count { it } ?: 0)

    /**
     * This map with the buildings [points] lie in open (docs/adr/0014-settings-lobby-redesign-open-buildings.md), as
     * [OpenBuildings.split] opens them; the outlines and the grid are this map's. This map itself when nothing changes.
     */
    fun withOpen(points: List<GeoPoint>): BuildingMap {
        val opened = BooleanArray(data.shapeCount)
        var any = false
        for (point in points) {
            val lat = point.lat * E7
            val lon = point.lon * E7
            data.shapeCells.forEach(cellKey(cell(data.east(lon)), cell(data.north(lat)))) { shape ->
                if (data.shapeContains(shape, lat, lon)) {
                    opened[shape] = true
                    any = true
                }
            }
        }
        val next = opened.takeIf { any }
        return when {
            next == null && open == null -> this
            next != null && open != null && next.contentEquals(open) -> this
            else -> BuildingMap(data, next)
        }
    }

    /**
     * How deep [point] lies inside a building: the distance to its nearest wall, courtyard or passage. Null when it is
     * not inside any building (outside, in a courtyard, in an arch or passage, in an open building). Of overlapping
     * buildings (a building and its part, a block of adjoining houses) the deepest counts.
     */
    fun depthInsideMeters(point: GeoPoint): Double? {
        val lat = point.lat * E7
        val lon = point.lon * E7
        val x = data.east(lon)
        val y = data.north(lat)
        // Negative: in no building yet. Not a Double?, which boxes every value it takes.
        var depth = -1.0
        data.shapeCells.forEach(cellKey(cell(x), cell(y))) { shape ->
            if ((open == null || !open[shape]) && data.shapeContains(shape, lat, lon)) {
                depth = max(depth, data.shapeDistanceToEdge(shape, x, y))
            }
        }
        if (depth < 0) return null
        var inside = depth
        // A passage within that distance is outdoors: the player is only as deep as the way out through it.
        for (corridor in data.corridorsAround(x, y, inside)) {
            val toCorridor = data.corridorDistance(corridor, x, y) - data.halfWidths[corridor]
            if (toCorridor <= 0) return null
            inside = min(inside, toCorridor)
        }
        return inside
    }

    /** The buildings the rule judges by, as the players draw them pink. */
    fun forbiddenAreas(): List<BuildingArea> =
        (0 until data.shapeCount).filter { open == null || !open[it] }.map(data::area)

    /** The buildings the host opened for hiding. */
    fun openAreas(): List<BuildingArea> {
        val open = open ?: return emptyList()
        return open.indices.filter { open[it] }.map(data::area)
    }

    fun passages(): List<Passage> = (0 until data.corridorCount).map(data::passage)

    /**
     * The outlines and the grid: buildings ("shapes": an outline and its courtyards) and passages ("corridors"), every
     * one a ring or a few of [points].
     */
    private class Packed(buildings: List<BuildingArea>, passages: List<Passage>, origin: GeoPoint) {
        /** Every ring's points one after another, two ints each: latitude and longitude in degrees × 10⁷. */
        private val points: IntArray

        /** Where each ring starts in [points], by point; the entry after the last closes it. */
        private val ringStarts: IntArray

        /** The rings of each building from its own entry to the next one's: the outline first, then the courtyards. */
        private val shapeRings: IntArray

        /** The outline's bounds of each building, four ints: south, north, west, east, in degrees × 10⁷. */
        private val bounds: IntArray

        /** The first passage's ring: the passages' rings follow the buildings' ones, one each. */
        private val firstCorridorRing: Int

        val shapeCount: Int
        val corridorCount: Int
        val halfWidths: DoubleArray
        private val widths: DoubleArray

        private val originLat = origin.lat * E7
        private val originLon = origin.lon * E7
        private val metersPerLat = EARTH_RADIUS_METERS * PI / 180 / E7
        private val metersPerLon = metersPerLat * cos(origin.lat * PI / 180)

        val shapeCells: Cells
        private val corridorCells: Cells

        init {
            val shapes = buildings.filter { it.outline.size >= 3 }
            val corridors = passages.filter { it.path.size >= 2 && it.widthMeters > 0 }
            val rings = ArrayList<List<GeoPoint>>()
            shapeRings = IntArray(shapes.size + 1)
            shapes.forEachIndexed { index, shape ->
                shapeRings[index] = rings.size
                rings += shape.outline
                shape.holes.filterTo(rings) { it.size >= 3 }
            }
            shapeRings[shapes.size] = rings.size
            firstCorridorRing = rings.size
            corridors.mapTo(rings) { it.path }
            ringStarts = IntArray(rings.size + 1)
            points = IntArray(rings.sumOf { it.size } * 2)
            var next = 0
            rings.forEachIndexed { index, ring ->
                ringStarts[index] = next
                for (point in ring) {
                    points[2 * next] = (point.lat * E7).roundToInt()
                    points[2 * next + 1] = (point.lon * E7).roundToInt()
                    next++
                }
            }
            ringStarts[rings.size] = next
            shapeCount = shapes.size
            corridorCount = corridors.size
            widths = DoubleArray(corridors.size) { corridors[it].widthMeters }
            halfWidths = DoubleArray(corridors.size) { widths[it] / 2 }
            bounds = IntArray(shapes.size * 4)
            val shapeBoxes = DoubleArray(shapes.size * 4)
            for (shape in 0 until shapeCount) {
                val ring = shapeRings[shape]
                var minLat = Int.MAX_VALUE
                var maxLat = Int.MIN_VALUE
                var minLon = Int.MAX_VALUE
                var maxLon = Int.MIN_VALUE
                for (point in ringStarts[ring] until ringStarts[ring + 1]) {
                    minLat = min(minLat, points[2 * point])
                    maxLat = max(maxLat, points[2 * point])
                    minLon = min(minLon, points[2 * point + 1])
                    maxLon = max(maxLon, points[2 * point + 1])
                }
                bounds[4 * shape] = minLat
                bounds[4 * shape + 1] = maxLat
                bounds[4 * shape + 2] = minLon
                bounds[4 * shape + 3] = maxLon
                shapeBoxes[4 * shape] = east(minLon.toDouble())
                shapeBoxes[4 * shape + 1] = east(maxLon.toDouble())
                shapeBoxes[4 * shape + 2] = north(minLat.toDouble())
                shapeBoxes[4 * shape + 3] = north(maxLat.toDouble())
            }
            shapeCells = Cells.of(shapeBoxes)
            val corridorBoxes = DoubleArray(corridorCount * 4)
            for (corridor in 0 until corridorCount) {
                val ring = firstCorridorRing + corridor
                val grow = halfWidths[corridor]
                var minX = Double.MAX_VALUE
                var maxX = -Double.MAX_VALUE
                var minY = Double.MAX_VALUE
                var maxY = -Double.MAX_VALUE
                for (point in ringStarts[ring] until ringStarts[ring + 1]) {
                    minX = min(minX, x(point))
                    maxX = max(maxX, x(point))
                    minY = min(minY, y(point))
                    maxY = max(maxY, y(point))
                }
                corridorBoxes[4 * corridor] = minX - grow
                corridorBoxes[4 * corridor + 1] = maxX + grow
                corridorBoxes[4 * corridor + 2] = minY - grow
                corridorBoxes[4 * corridor + 3] = maxY + grow
            }
            corridorCells = Cells.of(corridorBoxes)
        }

        /** Meters east of the origin of a longitude in degrees × 10⁷. */
        fun east(lon: Double): Double = (lon - originLon) * metersPerLon

        /** Meters north of the origin of a latitude in degrees × 10⁷. */
        fun north(lat: Double): Double = (lat - originLat) * metersPerLat

        private fun x(point: Int): Double = east(points[2 * point + 1].toDouble())

        private fun y(point: Int): Double = north(points[2 * point].toDouble())

        /**
         * Inside the outline of [shape] and in none of its courtyards. Even-odd rule on the degrees themselves: the
         * projection to meters is linear on both axes, so inside and out are the same in either.
         */
        fun shapeContains(shape: Int, lat: Double, lon: Double): Boolean {
            if (lat < bounds[4 * shape] || lat > bounds[4 * shape + 1]) return false
            if (lon < bounds[4 * shape + 2] || lon > bounds[4 * shape + 3]) return false
            val outline = shapeRings[shape]
            if (!ringContains(outline, lat, lon)) return false
            for (hole in outline + 1 until shapeRings[shape + 1]) {
                if (ringContains(hole, lat, lon)) return false
            }
            return true
        }

        /** The nearest wall or courtyard of [shape] to the point [x], [y] in meters from the origin. */
        fun shapeDistanceToEdge(shape: Int, x: Double, y: Double): Double {
            var best = Double.MAX_VALUE
            for (ring in shapeRings[shape] until shapeRings[shape + 1]) {
                best = min(best, distanceToRing(ring, x, y, closed = true))
            }
            return best
        }

        /** From the point [x], [y] in meters to the middle line of [corridor]. */
        fun corridorDistance(corridor: Int, x: Double, y: Double): Double =
            distanceToRing(firstCorridorRing + corridor, x, y, closed = false)

        /** The passages whose cells lie within [radius] meters of [x], [y]. */
        fun corridorsAround(x: Double, y: Double, radius: Double): Set<Int> {
            val found = LinkedHashSet<Int>()
            for (cx in cell(x - radius)..cell(x + radius)) {
                for (cy in cell(y - radius)..cell(y + radius)) corridorCells.forEach(cellKey(cx, cy)) { found += it }
            }
            return found
        }

        fun area(shape: Int): BuildingArea {
            val outline = shapeRings[shape]
            return BuildingArea(ringPoints(outline), (outline + 1 until shapeRings[shape + 1]).map(::ringPoints))
        }

        fun passage(corridor: Int): Passage = Passage(ringPoints(firstCorridorRing + corridor), widths[corridor])

        private fun ringPoints(ring: Int): List<GeoPoint> =
            (ringStarts[ring] until ringStarts[ring + 1]).map { GeoPoint(points[2 * it] / E7, points[2 * it + 1] / E7) }

        /** Even-odd rule on degrees × 10⁷; the closing edge is implied. */
        private fun ringContains(ring: Int, lat: Double, lon: Double): Boolean {
            val from = ringStarts[ring]
            val to = ringStarts[ring + 1]
            var inside = false
            var j = to - 1
            for (i in from until to) {
                val latI = points[2 * i].toDouble()
                val lonI = points[2 * i + 1].toDouble()
                val latJ = points[2 * j].toDouble()
                val lonJ = points[2 * j + 1].toDouble()
                if ((latI > lat) != (latJ > lat) && lon < (lonJ - lonI) * (lat - latI) / (latJ - latI) + lonI) {
                    inside = !inside
                }
                j = i
            }
            return inside
        }

        /** In meters: to the nearest segment of a ring, with the edge from its last point to its first when [closed]. */
        private fun distanceToRing(ring: Int, x: Double, y: Double, closed: Boolean): Double {
            val from = ringStarts[ring]
            val to = ringStarts[ring + 1]
            var best = Double.MAX_VALUE
            for (i in from until to - 1) best = min(best, segmentDistance(x, y, i, i + 1))
            if (closed) best = min(best, segmentDistance(x, y, to - 1, from))
            return best
        }

        private fun segmentDistance(x: Double, y: Double, from: Int, to: Int): Double {
            val fromX = x(from)
            val fromY = y(from)
            val dx = x(to) - fromX
            val dy = y(to) - fromY
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared == 0.0) 0.0 else (((x - fromX) * dx + (y - fromY) * dy) / lengthSquared)
            val along = t.coerceIn(0.0, 1.0)
            val px = fromX + along * dx - x
            val py = fromY + along * dy - y
            return sqrt(px * px + py * py)
        }
    }

    /**
     * The grid: the indexes of what lies in each cell, by the cell's key, all in three arrays. The keys are sorted and
     * found by binary search: a map would box every key it is asked for, and it is asked for each fix of each hider.
     */
    private class Cells(private val keys: LongArray, private val starts: IntArray, private val items: IntArray) {
        /** Calls [action] with the index of everything in the cell of [key]. */
        inline fun forEach(key: Long, action: (Int) -> Unit) {
            val found = find(key)
            if (found < 0) return
            for (item in starts[found] until starts[found + 1]) action(items[item])
        }

        fun find(key: Long): Int {
            var low = 0
            var high = keys.size - 1
            while (low <= high) {
                val middle = (low + high) ushr 1
                val found = keys[middle]
                when {
                    found < key -> low = middle + 1
                    found > key -> high = middle - 1
                    else -> return middle
                }
            }
            return -1
        }

        companion object {
            /** The grid of items by their bounds in meters, four numbers each: west, east, south, north. */
            fun of(boxes: DoubleArray): Cells {
                val byKey = HashMap<Long, MutableList<Int>>()
                for (index in 0 until boxes.size / 4) {
                    for (cx in cell(boxes[4 * index])..cell(boxes[4 * index + 1])) {
                        for (cy in cell(boxes[4 * index + 2])..cell(boxes[4 * index + 3])) {
                            byKey.getOrPut(cellKey(cx, cy)) { ArrayList() } += index
                        }
                    }
                }
                val keys = byKey.keys.sorted().toLongArray()
                val starts = IntArray(keys.size + 1)
                val items = IntArray(byKey.values.sumOf { it.size })
                var next = 0
                keys.forEachIndexed { index, key ->
                    starts[index] = next
                    for (item in byKey.getValue(key)) items[next++] = item
                }
                starts[keys.size] = next
                return Cells(keys, starts, items)
            }
        }
    }

    private companion object {
        const val CELL_METERS = 50.0

        /** Degrees to the ints of [Packed]: 10⁻⁷ of a degree is about a centimeter. */
        const val E7 = 1e7

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
    fun isConfidentlyInside(recentFixes: List<LocationSample>, buildings: BuildingMap, rules: GameRules): Boolean =
        isConfidentlyInside(recentFixes, rules) { spotOf(it, buildings, rules) }

    /** [isConfidentlyInside] with every fix judged once ([BuildingSpots]). */
    fun isConfidentlyInside(recentFixes: List<LocationSample>, spots: BuildingSpots, rules: GameRules): Boolean =
        isConfidentlyInside(recentFixes, rules, spots::spotOf)

    /**
     * Out again after a warning: the latest [GameRules.minFixesForDecision] fixes are all outside. One fix that jumps
     * out resets nothing, and neither does a dot at a wall: that is where a player by a window is, too.
     */
    fun hasLeft(recentFixes: List<LocationSample>, buildings: BuildingMap, rules: GameRules): Boolean =
        hasLeft(recentFixes, rules) { spotOf(it, buildings, rules) }

    /** [hasLeft] with every fix judged once ([BuildingSpots]). */
    fun hasLeft(recentFixes: List<LocationSample>, spots: BuildingSpots, rules: GameRules): Boolean =
        hasLeft(recentFixes, rules, spots::spotOf)

    private inline fun isConfidentlyInside(
        recentFixes: List<LocationSample>,
        rules: GameRules,
        spotOf: (LocationSample) -> Spot?,
    ): Boolean {
        // Counted in one pass, by index: the server asks this for every hider many times a second.
        var spots = 0
        var inside = 0
        for (index in recentFixes.indices) {
            val spot = spotOf(recentFixes[index]) ?: continue
            spots++
            if (spot == Spot.INSIDE) inside++
        }
        return spots >= rules.minFixesForDecision && inside >= spots * rules.buildingInsideShare
    }

    private inline fun hasLeft(
        recentFixes: List<LocationSample>,
        rules: GameRules,
        spotOf: (LocationSample) -> Spot?,
    ): Boolean {
        // From the latest fix back, the fixes the rule can judge, until there are enough of them.
        var outside = 0
        for (index in recentFixes.lastIndex downTo 0) {
            if (outside >= rules.minFixesForDecision) break
            val spot = spotOf(recentFixes[index]) ?: continue
            if (spot != Spot.OUTSIDE) return false
            outside++
        }
        return outside >= rules.minFixesForDecision
    }
}

/**
 * The spots of one player's fixes on one [map] under one set of [rules], each fix judged once. The server asks the rule
 * for every hider at every sync of the game, many times a second, over the same window of fixes: in a city's center,
 * where a dot is tested against hundreds of houses, judging them again was a fifth of the server's CPU. A fix's spot
 * never changes, and a fix is known by its time: a track holds at most one fix a millisecond. The latest [CAPACITY] fixes
 * are kept, a decision window's at one fix a second; an older one is judged again.
 */
class BuildingSpots(val map: BuildingMap, val rules: GameRules) {
    private val times = LongArray(CAPACITY) { Long.MIN_VALUE }
    private val spots = ByteArray(CAPACITY)
    private var next = 0

    fun spotOf(fix: LocationSample): BuildingRules.Spot? {
        val time = fix.timestampMillis
        for (index in times.indices) {
            if (times[index] == time) return decode(spots[index])
        }
        val spot = BuildingRules.spotOf(fix, map, rules)
        times[next] = time
        spots[next] = encode(spot)
        next = (next + 1) % CAPACITY
        return spot
    }

    private companion object {
        const val CAPACITY = 24
        const val NONE: Byte = -1

        fun encode(spot: BuildingRules.Spot?): Byte = spot?.ordinal?.toByte() ?: NONE

        fun decode(code: Byte): BuildingRules.Spot? =
            if (code == NONE) null else BuildingRules.Spot.entries[code.toInt()]
    }
}
