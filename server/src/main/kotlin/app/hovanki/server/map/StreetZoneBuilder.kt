package app.hovanki.server.map

import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ZoneCircle
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.protocol.ZoneSchedule
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.polygonize.Polygonizer
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.locationtech.jts.simplify.TopologyPreservingSimplifier
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.sqrt

/** No zone by streets could be built here; [message] must not hold coordinates. */
class StreetZoneException(message: String) : Exception(message)

/**
 * The zone by streets (docs/adr/0009-game-setup-glow-streets.md). The streets cut the area into city blocks (each
 * block reaches to the middle of the streets around it). Around each circle of the schedule the zone takes the whole
 * blocks that are more than half inside the circle, so its area comes out close to the circle's. The far halves of the
 * streets around them belong to the zone too: the border runs along the far side of a street, where a player walking
 * it is still inside. Where there are no streets (a park, a river), the circle, a little larger, cuts the block. Every
 * stage takes its blocks out of the zone before it: the zone only ever shrinks. A block the zone walls in is taken with
 * it, and the border is tidied: no slits, narrow bays, needles or thin wedges ([tidy]).
 */
class StreetZoneBuilder(private val streetHalfWidthMeters: Double = STREET_HALF_WIDTH_METERS) {
    /**
     * One polygon per stage, the initial zone first ([ZoneSchedule.stages] after it), from [streets] (lines of points).
     * Throws [StreetZoneException] when the streets make no sensible zone.
     */
    fun build(schedule: ZoneSchedule, streets: List<List<GeoPoint>>): List<ZonePolygon> {
        val projection = LocalProjection(schedule.initial.center)
        val reach = projection.circle(schedule.initial.center, schedule.initial.radiusMeters * REACH)
        val blocks = blocks(streets, reach, projection)
        val neighbors = neighbors(blocks)
        val stages = ArrayList<Geometry>()
        var allowed: Geometry = reach
        for ((index, circle) in (listOf(schedule.initial) + schedule.stages.map { it.target }).withIndex()) {
            val zone = try {
                zoneAround(circle, blocks, neighbors, allowed, projection)
            } catch (e: StreetZoneException) {
                // A late stage too small for whole blocks (the squeeze at the end): its circle within the stage before.
                if (index == 0) throw e
                circleWithin(circle, allowed, projection)
            }
            stages += zone
            allowed = zone
        }
        return stages.map { zone -> ZonePolygon(projection.points(largest(zone).exteriorRing)) }
    }

    /** The faces the streets and the reach's own border cut the reach into. */
    private fun blocks(streets: List<List<GeoPoint>>, reach: Polygon, projection: LocalProjection): List<Block> {
        // Streets are cut to a box a little larger than the reach, not to the reach itself: a street has to cross the
        // reach's border for the union to put a corner exactly there.
        val box = reach.factory.toGeometry(Envelope(reach.envelopeInternal).apply { expandBy(BOX_MARGIN_METERS) })
        val cuts = ArrayList<Geometry>()
        for (street in streets) {
            if (street.size < 2) continue
            val line = projection.line(street)
            if (!line.envelopeInternal.intersects(box.envelopeInternal)) continue
            val inBox = line.intersection(box)
            if (!inBox.isEmpty) cuts += inBox
        }
        if (cuts.isEmpty()) throw StreetZoneException("No streets around the zone")
        cuts += reach.exteriorRing
        // Union nodes the lines: every crossing becomes a corner the faces can turn at. Lines that end nowhere (a
        // cul-de-sac, the ends beyond the reach) are dropped by the polygonizer.
        val noded = UnaryUnionOp.union(cuts)
        val polygonizer = Polygonizer()
        polygonizer.add(noded)
        val blocks = polygonizer.polygons.filterIsInstance<Polygon>()
            .filter { it.area >= MIN_BLOCK_SQUARE_METERS }
            .map(::Block)
            .filter { reach.contains(it.inside) }
        if (blocks.size < MIN_BLOCKS) throw StreetZoneException("No streets cross the zone")
        return blocks
    }

    /**
     * The blocks next to each block, with how many meters of street they share (a segment, not only a corner): a block
     * mostly walled in by the zone is taken with it ([WRAPPED_SHARE]).
     */
    private fun neighbors(blocks: List<Block>): List<Map<Int, Double>> {
        val owners = HashMap<Pair<Coordinate, Coordinate>, MutableList<Int>>()
        blocks.forEachIndexed { index, block ->
            val rings = listOf(block.polygon.exteriorRing) +
                (0 until block.polygon.numInteriorRing).map { block.polygon.getInteriorRingN(it) }
            for (ring in rings) {
                val points = ring.coordinates
                for (i in 0 until points.size - 1) {
                    val a = points[i]
                    val b = points[i + 1]
                    val key = if (a.compareTo(b) <= 0) a to b else b to a
                    owners.getOrPut(key) { ArrayList(2) } += index
                }
            }
        }
        val neighbors = List(blocks.size) { HashMap<Int, Double>() }
        for ((segment, sharing) in owners) {
            val length = segment.first.distance(segment.second)
            for (a in sharing) for (b in sharing) if (a != b) neighbors[a].merge(b, length, Double::plus)
        }
        return neighbors
    }

    /**
     * Whole blocks within [allowed] around [circle]: every block more than half inside the circle and connected to the
     * block at its center (so the area comes out close to the circle's: what sticks out makes up for what is left
     * out), then, while that is too little, the neighbor with the most of it inside. See the class.
     */
    private fun zoneAround(
        circle: ZoneCircle,
        blocks: List<Block>,
        neighbors: List<Map<Int, Double>>,
        allowed: Geometry,
        projection: LocalProjection,
    ): Geometry {
        val center = projection.factory.createPoint(projection.toMeters(circle.center))
        val disc = projection.circle(circle.center, circle.radiusMeters)
        val clip = projection.circle(circle.center, circle.radiusMeters * CLIP)
        val candidates = blocks.indices.filterTo(HashSet()) { allowed.contains(blocks[it].inside) }
        if (candidates.isEmpty()) throw StreetZoneException("No blocks inside the zone before")
        val first = candidates.firstOrNull { blocks[it].polygon.contains(center) }
            ?: candidates.minBy { blocks[it].polygon.distance(center) }
        val insideShare = HashMap<Int, Double>()
        fun share(index: Int): Double = insideShare.getOrPut(index) {
            val block = blocks[index].polygon
            if (disc.contains(block)) 1.0 else block.intersection(disc).area / block.area
        }
        // The blocks mostly inside the circle, as far as they hang together with the center's.
        val taken = LinkedHashSet<Int>()
        val queue = ArrayDeque(listOf(first))
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            if (!taken.add(index)) continue
            for (next in neighbors[index].keys) {
                if (next in candidates && next !in taken && share(next) > HALF) queue += next
            }
        }
        // Too little (big blocks around a small circle): the neighbors with the most inside, until enough.
        val target = PI * circle.radiusMeters * circle.radiusMeters
        var area = taken.sumOf { clipped(blocks[it].polygon, clip) }
        while (area < target * MIN_FILL) {
            val next = taken.asSequence().flatMap { neighbors[it].keys.asSequence() }
                .filter { it in candidates && it !in taken && share(it) > 0 }
                .maxByOrNull(::share) ?: break
            taken += next
            area += clipped(blocks[next].polygon, clip)
        }
        wrapIn(taken, candidates, neighbors, blocks)
        val union = UnaryUnionOp.union(taken.map { blocks[it].polygon })
        val parameters = BufferParameters(2, BufferParameters.CAP_ROUND, BufferParameters.JOIN_MITRE, MITRE_LIMIT)
        val withStreets = BufferOp.bufferOp(filled(largest(union)), streetHalfWidthMeters, parameters)
        val zone = tidy(withStreets.intersection(clip).intersection(allowed), allowed)
        if (zone.area < target * MIN_AREA_SHARE) throw StreetZoneException("The blocks make too small a zone")
        return zone
    }

    /** [circle] cut to [allowed]; [allowed] itself when they hardly meet (the circle moved off the zone before). */
    private fun circleWithin(circle: ZoneCircle, allowed: Geometry, projection: LocalProjection): Geometry {
        val disc = projection.circle(circle.center, circle.radiusMeters)
        val inside = disc.intersection(allowed)
        return if (inside.area < disc.area * MIN_AREA_SHARE) allowed else largest(inside)
    }

    /**
     * Takes the blocks the zone walls in: more than [WRAPPED_SHARE] of their border is a street shared with the zone.
     * Left out, such a block is a deep bay in the border, walked around for nothing.
     */
    private fun wrapIn(
        taken: MutableSet<Int>,
        candidates: Set<Int>,
        neighbors: List<Map<Int, Double>>,
        blocks: List<Block>,
    ) {
        do {
            val wrapped = taken.asSequence().flatMap { neighbors[it].keys.asSequence() }.distinct()
                .filter { it in candidates && it !in taken }
                .filter { block ->
                    val shared = neighbors[block].entries.sumOf { (other, length) ->
                        if (other in
                            taken
                        ) {
                            length
                        } else {
                            0.0
                        }
                    }
                    shared > blocks[block].polygon.exteriorRing.length * WRAPPED_SHARE
                }
                .toList()
            taken += wrapped
        } while (wrapped.isNotEmpty())
    }

    /**
     * A border without odd bits (they puzzle a player on the map and on the street): slits and narrow bays filled
     * (a closing of [TIDY_CLOSE_METERS]), thin spikes and wedges cut (an opening of [TIDY_OPEN_METERS]), kept inside
     * [allowed], then the last needle-sharp corners taken off ([despiked]) and the corners simplified.
     */
    private fun tidy(zone: Geometry, allowed: Geometry): Polygon {
        val parameters = BufferParameters(2, BufferParameters.CAP_ROUND, BufferParameters.JOIN_MITRE, TIDY_MITRE_LIMIT)
        fun grow(geometry: Geometry, meters: Double) = BufferOp.bufferOp(geometry, meters, parameters)
        val closed = grow(grow(zone, TIDY_CLOSE_METERS), -TIDY_CLOSE_METERS)
        val opened = grow(grow(closed, -TIDY_OPEN_METERS), TIDY_OPEN_METERS)
        // Where the tidying took away everything (a zone thinner than the opening), the zone as it was.
        val simple = TopologyPreservingSimplifier.simplify(if (opened.isEmpty) zone else opened, SIMPLIFY_METERS)
        val polygon = despiked(filled(largest(simple.intersection(allowed))))
        // Taking off a needle must not reach beyond the zone before; what does is cut again.
        return if (polygon.difference(allowed).area < NESTED_SLACK) polygon else largest(polygon.intersection(allowed))
    }

    /**
     * [polygon] without corners sharper than [SPIKE_DEGREES]: what is left of a slit or a needle where two borders
     * almost coincide. Each such corner is dropped until none is left.
     */
    private fun despiked(polygon: Polygon): Polygon {
        val ring = polygon.exteriorRing.coordinates.dropLast(1).toMutableList()
        var changed = true
        while (changed && ring.size > 3) {
            changed = false
            for (i in ring.indices) {
                val before = ring[(i - 1 + ring.size) % ring.size]
                val corner = ring[i]
                val after = ring[(i + 1) % ring.size]
                if (angleDegrees(before, corner, after) < SPIKE_DEGREES) {
                    ring.removeAt(i)
                    changed = true
                    break
                }
            }
        }
        val result = polygon.factory.createPolygon((ring + ring.first()).toTypedArray())
        return if (result.isValid) result else largest(result.buffer(0.0))
    }

    /** The angle at [corner] between the ways to [before] and to [after]; 180 for a straight line, 0 for a spike. */
    private fun angleDegrees(before: Coordinate, corner: Coordinate, after: Coordinate): Double {
        val ax = before.x - corner.x
        val ay = before.y - corner.y
        val bx = after.x - corner.x
        val by = after.y - corner.y
        val lengths = sqrt(ax * ax + ay * ay) * sqrt(bx * bx + by * by)
        if (lengths == 0.0) return 0.0
        return Math.toDegrees(acos(((ax * bx + ay * by) / lengths).coerceIn(-1.0, 1.0)))
    }

    /** The part of [block] within [clip]: a block the clip cuts (a park) counts only with that. */
    private fun clipped(block: Polygon, clip: Polygon): Double =
        if (clip.contains(block)) block.area else block.intersection(clip).area

    private fun largest(geometry: Geometry): Polygon {
        val polygons = (0 until geometry.numGeometries).mapNotNull { geometry.getGeometryN(it) as? Polygon }
        return polygons.filter { !it.isEmpty }.maxByOrNull { it.area }
            ?: throw StreetZoneException("The zone came out empty")
    }

    /** [polygon] without its holes: a block nobody took in the middle of the zone belongs to it. */
    private fun filled(polygon: Polygon): Polygon = polygon.factory.createPolygon(polygon.exteriorRing.coordinates)

    private class Block(val polygon: Polygon) {
        /** A point surely inside the block (a block can be any shape). */
        val inside = polygon.interiorPoint
    }

    private companion object {
        const val STREET_HALF_WIDTH_METERS = 8.0

        /** Blocks come from a circle this much larger than the initial zone. */
        const val REACH = 1.35

        /** Where there are no streets, the zone ends at the circle this much larger. */
        const val CLIP = 1.25

        /** A block more than this share inside the circle is in the zone. */
        const val HALF = 0.5

        /** The zone takes more blocks while it covers less than this share of the circle's area. */
        const val MIN_FILL = 0.8

        /** Less than this share of the circle: the streets make no sensible zone here. */
        const val MIN_AREA_SHARE = 0.3
        const val MIN_BLOCK_SQUARE_METERS = 20.0
        const val MIN_BLOCKS = 2
        const val BOX_MARGIN_METERS = 50.0
        const val SIMPLIFY_METERS = 1.0
        const val MITRE_LIMIT = 3.0

        /** A block whose border is more than this share a street shared with the zone is taken with it. */
        const val WRAPPED_SHARE = 0.6

        /** Bays and slits narrower than twice this are filled. */
        const val TIDY_CLOSE_METERS = 20.0

        /** Spikes and wedges thinner than twice this are cut. */
        const val TIDY_OPEN_METERS = 12.0

        /** The tidying bevels corners sharper than this mitre allows. */
        const val TIDY_MITRE_LIMIT = 2.0

        /** A corner sharper than this is a needle, not a street corner. */
        const val SPIKE_DEGREES = 15.0

        /** Square meters a stage may reach beyond the one before through rounding. */
        const val NESTED_SLACK = 0.1
    }
}
