package app.hovanki.server.buildings

import app.hovanki.shared.protocol.BuildingArea
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.Passage
import app.hovanki.shared.protocol.ZoneCircle
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Locale
import kotlin.math.roundToInt

/**
 * OpenStreetMap buildings through the Overpass API (docs/adr/0003-map-and-buildings.md): one query per game, when it
 * is created, for the whole area the zone will ever cover. Only what the rule needs: building outlines and the
 * passages through them, with the OSM tags that tell a roof or an arch from a building one can hide in.
 */
class OverpassBuildingSource(private val properties: BuildingProperties, json: Json) : BuildingSource {
    private val client = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val parser = OverpassParser(json, properties.maxBuildings, properties.maxVertices)

    override fun load(area: ZoneCircle): Buildings {
        val request = HttpRequest.newBuilder(properties.overpassUrl)
            .timeout(properties.requestTimeout)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", USER_AGENT)
            .POST(HttpRequest.BodyPublishers.ofString("data=" + URLEncoder.encode(query(area), Charsets.UTF_8)))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: IOException) {
            throw BuildingsUnavailableException("Overpass unreachable: ${e.javaClass.simpleName}", e)
        }
        val body = response.body().use { it.readNBytes(properties.maxResponseBytes + 1) }
        val status = response.statusCode()
        if (status != HTTP_OK) throw BuildingsUnavailableException("Overpass answered $status")
        if (body.size > properties.maxResponseBytes) {
            throw BuildingsUnavailableException("Too much building data (over ${properties.maxResponseBytes} bytes)")
        }
        return parser.parse(body.decodeToString())
    }

    private fun query(area: ZoneCircle): String {
        val around = String.format(
            Locale.ROOT,
            "(around:%d,%.6f,%.6f)",
            area.radiusMeters.roundToInt(),
            area.center.lat,
            area.center.lon,
        )
        val serverTimeout = (properties.requestTimeout.seconds - 5).coerceAtLeast(5)
        return """
            [out:json][timeout:$serverTimeout][maxsize:${properties.maxResponseBytes * 2}];
            (
              way["building"]$around;
              relation["building"]["type"="multipolygon"]$around;
              way["tunnel"="building_passage"]$around;
              way["highway"]["covered"="yes"]$around;
            );
            out geom;
        """.trimIndent()
    }

    private companion object {
        const val HTTP_OK = 200
        const val USER_AGENT = "hovanki-server (street hide-and-seek; https://github.com/denysbohusevych/hovanki)"
    }
}

/** Overpass `out geom` JSON → [Buildings]; separate from the HTTP part so it is tested with fixed responses. */
class OverpassParser(private val json: Json, private val maxBuildings: Int, private val maxVertices: Int) {
    fun parse(body: String): Buildings {
        val response = try {
            json.decodeFromString(OverpassResponse.serializer(), body)
        } catch (e: SerializationException) {
            throw BuildingsUnavailableException("Unreadable Overpass response", e)
        } catch (e: IllegalArgumentException) {
            throw BuildingsUnavailableException("Unreadable Overpass response", e)
        }
        // A busy or timed-out instance still answers 200, with the error here and no (or partial) elements. The
        // remark itself is not logged: it may quote the query, and the query holds the zone center.
        if (response.remark?.contains("error", ignoreCase = true) == true) {
            throw BuildingsUnavailableException("Overpass reported an error instead of data")
        }
        val buildings = ArrayList<BuildingArea>()
        val passages = ArrayList<Passage>()
        for (element in response.elements) {
            val tags = element.tags
            when {
                element.type == "way" && isForbiddenBuilding(tags) -> {
                    val ring = element.points()
                    if (ring.isClosedRing()) buildings += BuildingArea(ring)
                }

                element.type == "relation" && isForbiddenBuilding(tags) -> buildings += multipolygon(element)

                element.type == "way" && isPassage(tags) -> {
                    val path = element.points()
                    if (path.size >= 2) passages += Passage(path, passageWidth(tags))
                }
            }
            if (buildings.size > maxBuildings) throw BuildingsUnavailableException("More than $maxBuildings buildings")
        }
        val vertices = buildings.sumOf { area -> area.outline.size + area.holes.sumOf { it.size } }
        if (vertices > maxVertices) throw BuildingsUnavailableException("More than $maxVertices building vertices")
        return Buildings(buildings, passages)
    }

    /** Outer rings with the inner rings that lie in them; ways split across members are joined into rings. */
    private fun multipolygon(relation: OverpassElement): List<BuildingArea> {
        val members = relation.members.filter { it.type == "way" }
        val outers = assembleRings(members.filter { it.role == "outer" || it.role.isEmpty() }.map { it.points() })
        val inners = assembleRings(members.filter { it.role == "inner" }.map { it.points() })
        return outers.map { outer -> BuildingArea(outer, inners.filter { contains(outer, it.first()) }) }
    }

    private fun isForbiddenBuilding(tags: Map<String, String>): Boolean {
        val building = tags["building"] ?: return false
        return building != "no" &&
            building !in OPEN_BUILDINGS &&
            tags["ruins"] != "yes" &&
            tags["location"] != "underground" &&
            (tags["layer"]?.leadingNumber() ?: 0.0) >= 0 &&
            // On stilts, or a bridge between houses: one can stand under it.
            (tags["building:min_level"]?.leadingNumber() ?: 0.0) < 1 &&
            (tags["min_height"]?.leadingNumber() ?: 0.0) < RAISED_METERS
    }

    private fun isPassage(tags: Map<String, String>): Boolean =
        tags["tunnel"] == "building_passage" || (tags["highway"] != null && tags["covered"] == "yes")

    private fun passageWidth(tags: Map<String, String>): Double =
        tags["width"]?.leadingNumber()?.coerceIn(MIN_PASSAGE_WIDTH, MAX_PASSAGE_WIDTH) ?: DEFAULT_PASSAGE_WIDTH

    private fun OverpassElement.points(): List<GeoPoint> = geometry.orEmpty().filterNotNull().map {
        GeoPoint(it.lat, it.lon)
    }

    private fun OverpassMember.points(): List<GeoPoint> = geometry.orEmpty().filterNotNull().map {
        GeoPoint(it.lat, it.lon)
    }

    @Serializable
    private data class OverpassResponse(val elements: List<OverpassElement> = emptyList(), val remark: String? = null)

    @Serializable
    private data class OverpassElement(
        val type: String,
        val tags: Map<String, String> = emptyMap(),
        val geometry: List<LatLon?>? = null,
        val members: List<OverpassMember> = emptyList(),
    )

    @Serializable
    private data class OverpassMember(val type: String, val role: String = "", val geometry: List<LatLon?>? = null)

    @Serializable
    private data class LatLon(val lat: Double, val lon: Double)

    private companion object {
        /** A roof or carport has no walls, ruins no roof: standing there is outdoors. */
        val OPEN_BUILDINGS = setOf("roof", "carport", "ruins")
        const val RAISED_METERS = 2.5
        const val DEFAULT_PASSAGE_WIDTH = 4.0
        const val MIN_PASSAGE_WIDTH = 2.0
        const val MAX_PASSAGE_WIDTH = 20.0

        /** "3", "3.5 m", "-1" → the number; anything else → null. */
        fun String.leadingNumber(): Double? = Regex("""^\s*-?\d+([.,]\d+)?""").find(this)?.value?.trim()
            ?.replace(',', '.')?.toDoubleOrNull()

        fun List<GeoPoint>.isClosedRing() = size >= 4 && first() == last()

        /** Joins ways that share end points into closed rings; open leftovers are dropped. */
        fun assembleRings(ways: List<List<GeoPoint>>): List<List<GeoPoint>> {
            val pending = ways.filter { it.size >= 2 }.toMutableList()
            val rings = ArrayList<List<GeoPoint>>()
            while (pending.isNotEmpty()) {
                val ring = pending.removeAt(0).toMutableList()
                while (ring.first() != ring.last()) {
                    val index = pending.indexOfFirst { it.first() == ring.last() || it.last() == ring.last() }
                    if (index < 0) break
                    val next = pending.removeAt(index)
                    ring += (if (next.first() == ring.last()) next else next.asReversed()).drop(1)
                }
                if (ring.isClosedRing()) rings += ring
            }
            return rings
        }

        /** Even-odd test in degrees: good enough to tell which outer ring a courtyard belongs to. */
        fun contains(ring: List<GeoPoint>, point: GeoPoint): Boolean {
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
    }
}
