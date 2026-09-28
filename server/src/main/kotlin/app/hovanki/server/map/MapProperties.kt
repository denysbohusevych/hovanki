package app.hovanki.server.map

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

/**
 * `hovanki.map.*`: the vector tiles the server reads the zone's buildings, streets and ground from (the map the players
 * see, OpenFreeMap), where the streets for the zone by streets come from (docs/adr/0009-game-setup-glow-streets.md), and
 * the ground for the zone's capacity (docs/adr/0010-big-games.md).
 */
@ConfigurationProperties("hovanki.map")
data class MapProperties(
    /** TileJSON of the tiles: it names the current tile URLs (they move with every weekly map update). */
    val tilesUrl: URI = URI.create("https://tiles.openfreemap.org/planet"),
    /** The zoom level whose tiles are read: the most detailed one (all buildings and streets). */
    val tilesZoom: Int = 14,
    /** A zone needing more tiles is too large for the map data: the buildings and the streets are unavailable. */
    val maxTiles: Int = 36,
    /** Larger tiles are refused (a z14 tile of a city center is a few hundred KB). */
    val maxTileBytes: Int = 4 * 1024 * 1024,
    val connectTimeout: Duration = Duration.ofSeconds(5),
    /** One tile, or the TileJSON. */
    val requestTimeout: Duration = Duration.ofSeconds(20),
    /** Decoded tiles are kept this long: the buildings and the streets of a game, and nearby games, share them. */
    val cacheTtl: Duration = Duration.ofMinutes(15),
    val cacheSize: Int = 48,
    /** Where the zone by streets gets its streets. */
    val streets: StreetsSource = StreetsSource.TILES,
    /** Where a zone's capacity gets the ground under it (docs/adr/0010-big-games.md). */
    val terrain: TerrainData = TerrainData.TILES,
) {
    enum class StreetsSource {
        /** The streets of the vector tiles. */
        TILES,

        /** A fixed grid of streets around every zone center (tests, the `e2e` profile). */
        FAKE,

        /** No streets: a zone by streets falls back to the circle. */
        OFF,
    }

    enum class TerrainData {
        /** The houses, water, woods, parks and fields of the vector tiles. */
        TILES,

        /** Built-up blocks everywhere (tests, the `e2e` profile). */
        FAKE,

        /** No ground: no estimate of players, no warning. */
        OFF,
    }
}

/**
 * The map data could not be loaded. [message] must not hold coordinates. [retry]: false when trying again can't help.
 */
class MapDataUnavailableException(message: String, cause: Throwable? = null, val retry: Boolean = true) :
    Exception(message, cause)
