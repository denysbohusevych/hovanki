package app.hovanki.server.buildings

import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ZoneCircle
import app.hovanki.shared.protocol.protocolJson
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The HTTP side against local stand-ins for Overpass instances: a busy one, a broken one, one with data. */
class OverpassBuildingSourceTest {
    private val servers = mutableListOf<HttpServer>()
    private val area = ZoneCircle(GeoPoint(50.0, 30.0), 550.0)

    private val oneBuilding = """
        {"version":0.6,"elements":[{"type":"way","id":1,"tags":{"building":"yes"},"geometry":[
          {"lat":50.0,"lon":30.0},{"lat":50.0,"lon":30.0004},{"lat":50.0004,"lon":30.0004},{"lat":50.0,"lon":30.0}
        ]}]}
    """.trimIndent()

    /** A stand-in instance answering [status] with [body]; counts its requests. */
    private fun instance(status: Int, body: String, requests: AtomicInteger = AtomicInteger()): URI {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/interpreter") { exchange ->
            requests.incrementAndGet()
            exchange.requestBody.readAllBytes()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        servers += server
        return URI.create("http://127.0.0.1:${server.address.port}/api/interpreter")
    }

    private fun source(vararg urls: URI, maxBuildings: Int = 100) = OverpassBuildingSource(
        BuildingProperties(overpassUrls = urls.toList(), maxBuildings = maxBuildings),
        protocolJson,
    )

    @AfterTest
    fun stop() = servers.forEach { it.stop(0) }

    @Test
    fun theNextInstanceWhenOneIsBusy() {
        val busy = instance(429, "rate limited")
        val erroring = instance(200, """{"elements":[],"remark":"runtime error: Query timed out"}""")
        val working = instance(200, oneBuilding)

        val buildings = source(busy, erroring, working).load(area)

        assertEquals(1, buildings.buildings.size)
    }

    @Test
    fun unavailableWhenNoInstanceHasData() {
        val failure = assertFailsWith<BuildingsUnavailableException> {
            source(instance(504, "gateway timeout"), instance(429, "rate limited")).load(area)
        }
        assertTrue("429" in failure.message.orEmpty(), failure.message)
        assertEquals(1, failure.suppressed.size, "the first instance's failure is kept too")
    }

    @Test
    fun tooManyBuildingsIsNotAskedElsewhere() {
        val second = AtomicInteger()
        val failure = assertFailsWith<BuildingsUnavailableException> {
            source(instance(200, oneBuilding), instance(200, oneBuilding, second), maxBuildings = 0).load(area)
        }
        assertEquals(false, failure.retry)
        assertEquals(0, second.get(), "the same data everywhere: no point in asking the next instance")
    }
}
