package app.hovanki.server.map

import app.hovanki.shared.protocol.ZoneCircle
import app.hovanki.shared.protocol.protocolJson
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Fetching and caching map tiles, against a local stand-in for OpenFreeMap. */
class VectorTilesTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val tileRequests = ConcurrentHashMap<String, AtomicInteger>()
    private val templateRequests = AtomicInteger()

    /** Tiles the stand-in answers with an error, by path ("14/1/2"). */
    private val failing = ConcurrentHashMap.newKeySet<String>()
    private val base = "http://127.0.0.1"
    private val tile = MvtWriter()
        .layer("building", listOf(Triple(MvtGeometryType.POLYGON, emptyMap(), listOf(square(10, 10, 20, 20)))))
        .bytes()

    // A small zone in Kyiv: four z14 tiles at most.
    private val area = ZoneCircle(TileMath.toGeo(TileId(14, 9581, 5524), 4096, TilePoint(2048, 2048)), 300.0)

    init {
        server.createContext("/planet") { exchange ->
            val path = exchange.requestURI.path.removePrefix("/planet")
            if (path.isEmpty()) {
                templateRequests.incrementAndGet()
                val port = server.address.port
                val body = """{"tiles":["$base:$port/planet/v1/{z}/{x}/{y}.pbf"],"maxzoom":14}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else {
                val key = path.removePrefix("/v1/").removeSuffix(".pbf")
                tileRequests.getOrPut(key) { AtomicInteger() }.incrementAndGet()
                when {
                    key in failing -> exchange.sendResponseHeaders(503, -1)

                    key.endsWith("/5524") -> {
                        exchange.sendResponseHeaders(200, tile.size.toLong())
                        exchange.responseBody.use { it.write(tile) }
                    }

                    // No tile there: sea.
                    else -> exchange.sendResponseHeaders(404, -1)
                }
            }
            exchange.close()
        }
        server.start()
    }

    private fun tiles(maxTiles: Int = 36) = VectorTiles(
        MapProperties(tilesUrl = URI.create("$base:${server.address.port}/planet"), maxTiles = maxTiles),
        protocolJson,
        Clock.systemUTC(),
    )

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun theTilesAroundTheZoneOnceEach() {
        val tiles = tiles()

        val first = tiles.tiles(area)
        val second = tiles.tiles(area)

        assertEquals(first.map { it.id }, second.map { it.id })
        assertTrue(first.size in 1..4)
        assertEquals(1, templateRequests.get())
        assertTrue(tileRequests.values.all { it.get() == 1 }, "cached: $tileRequests")
        val withData = first.filter { it.tile.layers.isNotEmpty() }
        assertTrue(withData.isNotEmpty() && withData.all { it.id.y == 5524 })
    }

    @Test
    fun aFailedTileIsFetchedAgainNextTime() {
        val tiles = tiles()
        val key = TileMath.tilesCovering(area, 14).first().let { "${it.z}/${it.x}/${it.y}" }
        failing += key

        assertFailsWith<MapDataUnavailableException> { tiles.tiles(area) }
        failing -= key
        tiles.tiles(area)

        assertEquals(2, tileRequests.getValue(key).get())
    }

    @Test
    fun aZoneTooLargeForTheTiles() {
        val error =
            assertFailsWith<MapDataUnavailableException> { tiles(maxTiles = 1).tiles(area.copy(radiusMeters = 3000.0)) }

        assertEquals(false, error.retry)
    }
}
