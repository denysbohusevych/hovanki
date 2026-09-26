package app.hovanki.server.buildings

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

/** `hovanki.buildings.*`: where the building rule gets its data (docs/adr/0003-map-and-buildings.md). */
@ConfigurationProperties("hovanki.buildings")
data class BuildingProperties(
    val source: Source = Source.OVERPASS,
    /** Public instance by default; a self-hosted one when the load grows. */
    val overpassUrl: URI = URI.create("https://overpass-api.de/api/interpreter"),
    val connectTimeout: Duration = Duration.ofSeconds(5),
    /** The whole request; the query itself asks Overpass for a shorter server-side timeout. */
    val requestTimeout: Duration = Duration.ofSeconds(30),
    /** Larger responses are dropped: the zone is too built up for the rule (and for the phones to draw). */
    val maxResponseBytes: Int = 16 * 1024 * 1024,
    val maxBuildings: Int = 10_000,
    val maxVertices: Int = 300_000,
    /** One more attempt after this pause when the first one fails. */
    val retryDelay: Duration = Duration.ofSeconds(5),
) {
    enum class Source {
        /** OpenStreetMap buildings through the Overpass API. */
        OVERPASS,

        /** The fixed test quarter ([FakeBuildingSource]); tests and the `e2e` profile. */
        FAKE,

        /** No building data: games run without the building rule. */
        OFF,
    }
}
