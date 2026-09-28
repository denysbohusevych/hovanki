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
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier
import kotlin.math.PI

/** No zone by streets could be built here; [message] must not hold coordinates. */
class StreetZoneException(message: String) : Exception(message)

/**
 * The zone by streets (docs/adr/0009-game-setup-glow-streets.md). The streets cut the area into city blocks (each
 * block reaches to the middle of the streets around it). Around each circle of the schedule the zone takes the whole
 * blocks that are more than half inside the circle, so its area comes out close to the circle's. The far halves of the
 * streets around them belong to the zone too: the border runs along the far side of a street, where a player walking
 * it is still inside. Where there are no streets (a park, a river), the circle, a little larger, cuts the block. Every
 * stage takes its blocks out of the zone before it: the zone only ever shrinks.
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
        for (circle in listOf(schedule.initial) + schedule.stages.map { it.target }) {
            val zone = zoneAround(circle, blocks, neighbors, allowed, projection)
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

    /** The blocks next to each block: sharing a piece of street (a segment), not only a corner. */
    private fun neighbors(blocks: List<Block>): List<Set<Int>> {
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
        val neighbors = List(blocks.size) { HashSet<Int>() }
        for (sharing in owners.values) {
            for (a in sharing) for (b in sharing) if (a != b) neighbors[a] += b
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
        neighbors: List<Set<Int>>,
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
            for (next in neighbors[index]) {
                if (next in candidates && next !in taken && share(next) > HALF) queue += next
            }
        }
        // Too little (big blocks around a small circle): the neighbors with the most inside, until enough.
        val target = PI * circle.radiusMeters * circle.radiusMeters
        var area = taken.sumOf { clipped(blocks[it].polygon, clip) }
        while (area < target * MIN_FILL) {
            val next = taken.asSequence().flatMap { neighbors[it].asSequence() }
                .filter { it in candidates && it !in taken && share(it) > 0 }
                .maxByOrNull(::share) ?: break
            taken += next
            area += clipped(blocks[next].polygon, clip)
        }
        val union = UnaryUnionOp.union(taken.map { blocks[it].polygon })
        val parameters = BufferParameters(2, BufferParameters.CAP_ROUND, BufferParameters.JOIN_MITRE, MITRE_LIMIT)
        val withStreets = BufferOp.bufferOp(filled(largest(union)), streetHalfWidthMeters, parameters)
        val zone = withStreets.intersection(clip).intersection(allowed)
        val simplified = filled(largest(DouglasPeuckerSimplifier.simplify(zone, SIMPLIFY_METERS)))
        if (simplified.area < target * MIN_AREA_SHARE) throw StreetZoneException("The blocks make too small a zone")
        return simplified
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
    }
}
