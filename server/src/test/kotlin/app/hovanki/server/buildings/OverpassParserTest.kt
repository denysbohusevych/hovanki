package app.hovanki.server.buildings

import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.protocolJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OverpassParserTest {
    private val parser = OverpassParser(protocolJson, maxBuildings = 100, maxVertices = 1_000)

    private fun square(lat: Double, lon: Double, size: Double = 0.0004) =
        listOf(lat to lon, lat to lon + size, lat + size to lon + size, lat + size to lon, lat to lon)

    private fun geometry(points: List<Pair<Double, Double>>) =
        points.joinToString(",", "[", "]") { (lat, lon) -> """{"lat":$lat,"lon":$lon}""" }

    private fun way(tags: String, points: List<Pair<Double, Double>>) =
        """{"type":"way","id":1,"tags":{$tags},"geometry":${geometry(points)}}"""

    private fun response(vararg elements: String) =
        """{"version":0.6,"generator":"Overpass API","elements":[${elements.joinToString(",")}]}"""

    @Test
    fun keepsBuildingsOneCanHideIn() {
        val buildings = parser.parse(
            response(
                way(""""building":"yes"""", square(50.0, 30.0)),
                way(""""building":"apartments","building:levels":"9"""", square(50.001, 30.0)),
                way(""""building":"yes","layer":"0"""", square(50.002, 30.0)),
            ),
        )

        assertEquals(3, buildings.buildings.size)
        assertEquals(GeoPoint(50.0, 30.0), buildings.buildings.first().outline.first())
    }

    @Test
    fun dropsWhatIsOutdoors() {
        val buildings = parser.parse(
            response(
                way(""""building":"roof"""", square(50.0, 30.0)),
                way(""""building":"carport"""", square(50.0, 30.001)),
                way(""""building":"yes","ruins":"yes"""", square(50.0, 30.002)),
                way(""""building":"yes","location":"underground"""", square(50.0, 30.003)),
                way(""""building":"yes","layer":"-1"""", square(50.0, 30.004)),
                way(""""building":"yes","building:min_level":"1"""", square(50.0, 30.005)),
                way(""""building":"yes","min_height":"3.5 m"""", square(50.0, 30.006)),
                way(""""building":"no"""", square(50.0, 30.007)),
                way(""""building":"yes"""", square(50.0, 30.008).dropLast(1)),
            ),
        )

        assertTrue(buildings.buildings.isEmpty(), "roofs, ruins, underground, raised and open outlines")
    }

    @Test
    fun passagesAndArcadesWithTheirWidth() {
        val buildings = parser.parse(
            response(
                way(""""tunnel":"building_passage","highway":"footway"""", listOf(50.0 to 30.0, 50.001 to 30.0)),
                way(""""highway":"footway","covered":"yes","width":"6"""", listOf(50.0 to 30.1, 50.001 to 30.1)),
                way(""""highway":"footway"""", listOf(50.0 to 30.2, 50.001 to 30.2)),
            ),
        )

        assertEquals(listOf(4.0, 6.0), buildings.passages.map { it.widthMeters })
    }

    @Test
    fun multipolygonsAreJoinedWithTheirCourtyards() {
        // An outer ring split into two ways (one reversed), and a courtyard.
        val outerA = listOf(50.0 to 30.0, 50.0 to 30.002, 50.002 to 30.002)
        val outerB = listOf(50.0 to 30.0, 50.002 to 30.0, 50.002 to 30.002)
        val courtyard = square(50.0008, 30.0008)
        val relation = """{"type":"relation","id":7,"tags":{"type":"multipolygon","building":"yes"},"members":[""" +
            """{"type":"way","ref":1,"role":"outer","geometry":${geometry(outerA)}},""" +
            """{"type":"way","ref":2,"role":"outer","geometry":${geometry(outerB)}},""" +
            """{"type":"way","ref":3,"role":"inner","geometry":${geometry(courtyard)}}]}"""

        val area = parser.parse(response(relation)).buildings.single()

        assertEquals(5, area.outline.size)
        assertEquals(area.outline.first(), area.outline.last())
        assertEquals(1, area.holes.size)
    }

    @Test
    fun tooMuchDataOrGarbageIsUnavailable() {
        val many = (0..100).map { way(""""building":"yes"""", square(50.0 + it * 0.001, 30.0)) }

        assertFailsWith<BuildingsUnavailableException> { parser.parse(response(*many.toTypedArray())) }
        assertFailsWith<BuildingsUnavailableException> { parser.parse("<html>rate limited</html>") }
    }
}
