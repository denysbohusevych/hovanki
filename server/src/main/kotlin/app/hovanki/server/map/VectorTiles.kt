package app.hovanki.server.map

import app.hovanki.shared.protocol.ZoneCircle
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A tile with the layers the server reads. */
class LoadedTile(val id: TileId, val tile: MvtTile)

/**
 * The vector tiles of the players' map (OpenFreeMap, docs/adr/0003-map-and-buildings.md): no key, no request limits,
 * the same data the players see. The TileJSON names the current tile URLs; tiles are fetched in parallel, decoded
 * (only [LAYERS]) and kept for [MapProperties.cacheTtl], so the buildings and the streets of one game, and the next
 * game nearby, fetch each tile once. Blocking: called off the request threads.
 */
class VectorTiles(private val properties: MapProperties, private val json: Json, private val clock: Clock) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Volatile private var template: TileTemplate? = null
    private val cache = ConcurrentHashMap<TileId, CachedTile>()

    /** The tiles covering [area]; throws [MapDataUnavailableException] when they can't be loaded. */
    fun tiles(area: ZoneCircle): List<LoadedTile> {
        val template = template()
        val ids = TileMath.tilesCovering(area, template.zoom)
        if (ids.size > properties.maxTiles) {
            val message = "The area needs ${ids.size} tiles, at most ${properties.maxTiles}"
            throw MapDataUnavailableException(message, retry = false)
        }
        evictOld()
        val loading = ids.map { id -> id to tile(id, template) }
        val deadline = System.nanoTime() + properties.requestTimeout.toNanos() * 2
        return loading.map { (id, future) ->
            try {
                LoadedTile(id, future.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS))
            } catch (e: TimeoutException) {
                throw MapDataUnavailableException("Tile $id timed out", e)
            } catch (e: ExecutionException) {
                val cause = e.cause
                throw cause as? MapDataUnavailableException
                    ?: MapDataUnavailableException("Tile $id: ${cause?.javaClass?.simpleName}", cause)
            }
        }
    }

    /** The tile from the cache, or on its way there: two games (or buildings and streets) fetch it once. */
    private fun tile(id: TileId, template: TileTemplate): CompletableFuture<MvtTile> {
        val now = clock.millis()
        var created: CachedTile? = null
        val entry = cache.compute(id) { _, cached ->
            val usable = cached != null && cached.template == template.url &&
                now - cached.createdAtMillis < properties.cacheTtl.toMillis() && !cached.future.isCompletedExceptionally
            if (usable) cached else CachedTile(template.url, now, CompletableFuture()).also { created = it }
        }!!
        created?.let { fresh ->
            fetch(id, template).whenComplete { tile, error ->
                // A failed tile is fetched again next time.
                if (error != null) {
                    cache.remove(id, fresh)
                    fresh.future.completeExceptionally(error)
                } else {
                    fresh.future.complete(tile)
                }
            }
        }
        return entry.future
    }

    private fun fetch(id: TileId, template: TileTemplate): CompletableFuture<MvtTile> {
        val url = URI.create(template.url.replace("{z}", "${id.z}").replace("{x}", "${id.x}").replace("{y}", "${id.y}"))
        val request = HttpRequest.newBuilder(url)
            .timeout(properties.requestTimeout)
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).thenApply { response ->
            val body = response.body().use { it.readNBytes(properties.maxTileBytes + 1) }
            when (response.statusCode()) {
                HTTP_OK -> {
                    if (body.size > properties.maxTileBytes) {
                        val message = "Tile $id is larger than ${properties.maxTileBytes} bytes"
                        throw MapDataUnavailableException(message, retry = false)
                    }
                    try {
                        Mvt.decode(body, LAYERS)
                    } catch (e: IllegalArgumentException) {
                        throw MapDataUnavailableException("Tile $id is unreadable", e)
                    }
                }

                // No tile: nothing there (sea, nowhere).
                HTTP_NO_CONTENT, HTTP_NOT_FOUND -> MvtTile(emptyMap())

                else -> throw MapDataUnavailableException("Tile $id: ${url.host} answered ${response.statusCode()}")
            }
        }
    }

    /**
     * The tile URL template from the TileJSON, fetched again every [TEMPLATE_TTL_MILLIS]; the last one if that
     * fails.
     */
    private fun template(): TileTemplate {
        val now = clock.millis()
        val known = template
        if (known != null && now - known.fetchedAtMillis < TEMPLATE_TTL_MILLIS) return known
        return try {
            fetchTemplate(now).also { template = it }
        } catch (e: MapDataUnavailableException) {
            known ?: throw e
        }
    }

    private fun fetchTemplate(now: Long): TileTemplate {
        val request = HttpRequest.newBuilder(properties.tilesUrl)
            .timeout(properties.requestTimeout)
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw MapDataUnavailableException("${properties.tilesUrl.host} unreachable: ${e.javaClass.simpleName}", e)
        }
        if (response.statusCode() != HTTP_OK) {
            throw MapDataUnavailableException("${properties.tilesUrl.host} answered ${response.statusCode()}")
        }
        val tileJson = try {
            json.decodeFromString(TileJson.serializer(), response.body())
        } catch (e: SerializationException) {
            throw MapDataUnavailableException("Unreadable TileJSON", e)
        } catch (e: IllegalArgumentException) {
            throw MapDataUnavailableException("Unreadable TileJSON", e)
        }
        // HTTPS only, unless the TileJSON itself came over plain HTTP (a local stand-in in tests).
        val schemes = if (properties.tilesUrl.scheme == "http") listOf("https://", "http://") else listOf("https://")
        val url = tileJson.tiles.firstOrNull { url ->
            schemes.any(url::startsWith) && "{z}" in url && "{x}" in url && "{y}" in url
        } ?: throw MapDataUnavailableException("The TileJSON names no tile URL", retry = false)
        val zoom = minOf(properties.tilesZoom, tileJson.maxzoom ?: properties.tilesZoom)
        return TileTemplate(url, zoom, now)
    }

    private fun evictOld() {
        val oldest = clock.millis() - properties.cacheTtl.toMillis()
        cache.entries.removeIf { it.value.createdAtMillis < oldest }
        if (cache.size > properties.cacheSize) {
            cache.entries.sortedBy { it.value.createdAtMillis }.take(cache.size - properties.cacheSize)
                .forEach { cache.remove(it.key, it.value) }
        }
    }

    private data class TileTemplate(val url: String, val zoom: Int, val fetchedAtMillis: Long)

    private class CachedTile(val template: String, val createdAtMillis: Long, val future: CompletableFuture<MvtTile>)

    @Serializable
    private data class TileJson(val tiles: List<String> = emptyList(), val maxzoom: Int? = null)

    companion object {
        /** The layers of the OpenMapTiles schema the server reads. */
        const val BUILDING_LAYER = "building"
        const val TRANSPORTATION_LAYER = "transportation"
        val LAYERS = setOf(BUILDING_LAYER, TRANSPORTATION_LAYER)

        private const val HTTP_OK = 200
        private const val HTTP_NO_CONTENT = 204
        private const val HTTP_NOT_FOUND = 404
        private const val TEMPLATE_TTL_MILLIS = 60 * 60_000L
        private const val USER_AGENT =
            "hovanki-server (street hide-and-seek; https://github.com/denysbohusevych/hovanki)"
    }
}
