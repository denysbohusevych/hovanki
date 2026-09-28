package app.hovanki.server.buildings

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

/** `hovanki.buildings.*`: where the building rule gets its data (docs/adr/0003-map-and-buildings.md). */
@ConfigurationProperties("hovanki.buildings")
data class BuildingProperties(
    val source: Source = Source.TILES,
    /**
     * Tried in order until one answers with data: the main public instance, then another public one (a busy
     * instance answers 429, 504 or an error instead of data). A self-hosted one first when the load grows.
     */
    val overpassUrls: List<URI> = listOf(
        URI.create("https://overpass-api.de/api/interpreter"),
        URI.create("https://overpass.kumi.systems/api/interpreter"),
    ),
    val connectTimeout: Duration = Duration.ofSeconds(5),
    /** The whole request; the query itself asks Overpass for a shorter server-side timeout. */
    val requestTimeout: Duration = Duration.ofSeconds(30),
    /** Larger responses are dropped: the zone is too built up for the rule (and for the phones to draw). */
    val maxResponseBytes: Int = 16 * 1024 * 1024,
    val maxBuildings: Int = 10_000,
    val maxVertices: Int = 300_000,
    /** Attempts over all [overpassUrls]; after a failed one the loader waits [retryDelay], then twice that, etc. */
    val attempts: Int = 2,
    val retryDelay: Duration = Duration.ofSeconds(5),
    /**
     * Only with [Source.FAKE] (tests, the `e2e` profile): the test quarter arrives this late, off the request thread,
     * like a slow Overpass; zero (the default) loads it right away. The other sources ignore it.
     */
    val fakeDelay: Duration = Duration.ZERO,
) {
    enum class Source {
        /**
         * The `building` layer of the players' map tiles (OpenFreeMap, `hovanki.map.*`): no key, no limits, reachable
         * from cloud servers. The default since the public Overpass instances stopped answering the server in AWS.
         */
        TILES,

        /** OpenStreetMap buildings through the Overpass API: more tags (roofs, arches), public instances are flaky. */
        OVERPASS,

        /** The fixed test quarter ([FakeBuildingSource]); tests and the `e2e` profile. */
        FAKE,

        /** No building data: games run without the building rule. */
        OFF,
    }
}
