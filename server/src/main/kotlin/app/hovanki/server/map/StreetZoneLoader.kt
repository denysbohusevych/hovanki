package app.hovanki.server.map

import app.hovanki.shared.protocol.ZoneCircle
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.protocol.ZoneSchedule
import app.hovanki.shared.rules.boundingCircle
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Builds a game's zone by streets off the request thread (docs/adr/0009-game-setup-glow-streets.md), like the
 * buildings: the players gather in the lobby meanwhile, and the host starts once it is there. The fake and the "off"
 * sources run inline, so a test game has its zone right away.
 */
@Component
class StreetZoneLoader(
    private val source: StreetSource,
    private val properties: MapProperties,
    private val builder: StreetZoneBuilder,
) : DisposableBean {
    private val pool: ExecutorService? = if (properties.streets == MapProperties.StreetsSource.TILES) {
        Executors.newFixedThreadPool(POOL_SIZE) { task ->
            thread(start = false, isDaemon = true, name = "street-zone") { task.run() }
        }
    } else {
        null
    }
    private val executor: Executor = pool ?: Executor(Runnable::run)

    /** Calls [onResult] with the zone's polygons (one per stage), or null when there is no zone by streets. */
    fun load(gameId: String, schedule: ZoneSchedule, onResult: (List<ZonePolygon>?) -> Unit) {
        executor.execute { onResult(build(gameId, schedule)) }
    }

    private fun build(gameId: String, schedule: ZoneSchedule): List<ZonePolygon>? {
        val bounds = schedule.boundingCircle()
        // The builder takes blocks from a little beyond the circle: the streets must reach that far.
        val area = ZoneCircle(bounds.center, bounds.radiusMeters * STREETS_REACH)
        for (attempt in 1..ATTEMPTS) {
            try {
                val polygons = builder.build(schedule, source.streets(area))
                // Never the polygons or the area: the zone is centered on the host's position.
                log.info(
                    "Zone by streets for game {}: {} stages, {} corners",
                    gameId,
                    polygons.size,
                    polygons.sumOf {
                        it.outline.size
                    },
                )
                return polygons
            } catch (e: StreetZoneException) {
                log.info("No zone by streets for game {}: {}", gameId, e.message)
                return null
            } catch (e: MapDataUnavailableException) {
                log.warn("Streets for game {} unavailable (attempt {}): {}", gameId, attempt, e.message)
                if (!e.retry) return null
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } catch (e: RuntimeException) {
                // A geometry the builder can't handle: the game falls back to the circle.
                log.warn("Zone by streets for game {} failed: {}", gameId, e.toString())
                return null
            }
            if (attempt < ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_DELAY_MILLIS * attempt)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        return null
    }

    override fun destroy() {
        pool?.shutdownNow()
    }

    private companion object {
        val log = LoggerFactory.getLogger(StreetZoneLoader::class.java)
        const val POOL_SIZE = 2
        const val ATTEMPTS = 2
        const val RETRY_DELAY_MILLIS = 5_000L
        const val STREETS_REACH = 1.4
    }
}

/** The map tiles, and the [StreetSource] named by `hovanki.map.streets`. */
@Configuration(proxyBeanMethods = false)
class MapConfig {
    @Bean
    fun vectorTiles(properties: MapProperties, json: Json, clock: Clock): VectorTiles =
        VectorTiles(properties, json, clock)

    @Bean
    fun streetSource(properties: MapProperties, tiles: VectorTiles): StreetSource = when (properties.streets) {
        MapProperties.StreetsSource.TILES -> TileStreetSource(tiles)
        MapProperties.StreetsSource.FAKE -> FakeStreetSource()
        MapProperties.StreetsSource.OFF -> NoStreetSource()
    }

    @Bean
    fun streetZoneBuilder(): StreetZoneBuilder = StreetZoneBuilder()
}
