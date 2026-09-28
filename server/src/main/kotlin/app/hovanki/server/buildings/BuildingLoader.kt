package app.hovanki.server.buildings

import app.hovanki.server.map.VectorTiles
import app.hovanki.shared.protocol.ZoneCircle
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Loads a new game's buildings off the request thread, so creating a game never waits for the source: the players
 * gather in the lobby meanwhile. Fast local sources ([FakeBuildingSource], [NoBuildingSource]) run inline, so a
 * test game has its buildings right away; the fake one with [BuildingProperties.fakeDelay] runs like Overpass.
 */
@Component
class BuildingLoader(private val source: BuildingSource, private val properties: BuildingProperties) : DisposableBean {
    private val fakeDelay = properties.fakeDelay.takeIf { properties.source == BuildingProperties.Source.FAKE }
        ?.takeIf { it > Duration.ZERO }
    private val offThread = properties.source == BuildingProperties.Source.OVERPASS ||
        properties.source == BuildingProperties.Source.TILES ||
        fakeDelay != null
    private val pool: ExecutorService? = if (offThread) {
        Executors.newFixedThreadPool(POOL_SIZE) { task ->
            thread(start = false, isDaemon = true, name = "buildings") { task.run() }
        }
    } else {
        null
    }
    private val executor: Executor = pool ?: Executor(Runnable::run)

    /** Calls [onResult] with the buildings of [area], or null when they can't be loaded; [gameId] is for the log. */
    fun load(gameId: String, area: ZoneCircle, onResult: (Buildings?) -> Unit) {
        executor.execute { onResult(loadWithRetry(gameId, area)) }
    }

    private fun loadWithRetry(gameId: String, area: ZoneCircle): Buildings? {
        if (properties.source == BuildingProperties.Source.OFF) return null
        if (fakeDelay != null) {
            try {
                Thread.sleep(fakeDelay.toMillis())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        val attempts = properties.attempts.coerceAtLeast(1)
        for (attempt in 1..attempts) {
            try {
                return source.load(area).also {
                    log.info(
                        "Buildings for game {}: {} buildings, {} passages",
                        gameId,
                        it.buildings.size,
                        it.passages.size,
                    )
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } catch (e: Exception) {
                // Never the area itself: the zone is centered on the host's position.
                log.warn("Buildings for game {} unavailable (attempt {}): {}", gameId, attempt, e.message)
                if (e is BuildingsUnavailableException && !e.retry) return null
                if (attempt < attempts) Thread.sleep(properties.retryDelay.toMillis() * attempt)
            }
        }
        return null
    }

    override fun destroy() {
        pool?.shutdownNow()
    }

    private companion object {
        val log = LoggerFactory.getLogger(BuildingLoader::class.java)
        const val POOL_SIZE = 2
    }
}

/** Picks the [BuildingSource] named by `hovanki.buildings.source`. */
@Configuration(proxyBeanMethods = false)
class BuildingConfig {
    @Bean
    fun buildingSource(properties: BuildingProperties, json: Json, tiles: VectorTiles): BuildingSource =
        when (properties.source) {
            BuildingProperties.Source.TILES -> TileBuildingSource(tiles, properties)
            BuildingProperties.Source.OVERPASS -> OverpassBuildingSource(properties, json)
            BuildingProperties.Source.FAKE -> FakeBuildingSource()
            BuildingProperties.Source.OFF -> NoBuildingSource()
        }
}
