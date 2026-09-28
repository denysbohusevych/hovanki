package app.hovanki.server.map

import app.hovanki.shared.protocol.ZoneCircle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Reads the ground under a game's zone off the request thread (docs/adr/0010-big-games.md), like the buildings: the
 * players gather in the lobby meanwhile, the host sees the estimate a few seconds later. The fake and the "off" sources
 * run inline, so a test game has its estimate right away.
 */
@Component
class TerrainLoader(private val source: TerrainSource, properties: MapProperties) : DisposableBean {
    private val pool: ExecutorService? = if (properties.terrain == MapProperties.TerrainData.TILES) {
        Executors.newFixedThreadPool(POOL_SIZE) { task ->
            thread(start = false, isDaemon = true, name = "terrain") { task.run() }
        }
    } else {
        null
    }
    private val executor: Executor = pool ?: Executor(Runnable::run)

    /**
     * Calls [onResult] with the ground of [zone] (a circle holding the zone during its whole schedule) and a little
     * around it (a zone by streets reaches beyond its circle), or null when it can't be read; [gameId] is for the log.
     */
    fun load(gameId: String, zone: ZoneCircle, onResult: (TerrainGrid?) -> Unit) {
        executor.execute { onResult(read(gameId, zone)) }
    }

    /** The same, blocking: the admin's estimate of a zone being drawn. */
    fun read(what: String, zone: ZoneCircle): TerrainGrid? {
        val area = ZoneCircle(zone.center, zone.radiusMeters * REACH)
        for (attempt in 1..ATTEMPTS) {
            try {
                return source.terrain(area).also {
                    // Never the area: the zone is centered on the host's position.
                    log.info("Terrain for {}: {} × {} cells of {} m", what, it.columns, it.rows, it.cellMeters.toInt())
                }
            } catch (e: MapDataUnavailableException) {
                log.warn("Terrain for {} unavailable (attempt {}): {}", what, attempt, e.message)
                if (!e.retry) return null
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } catch (e: RuntimeException) {
                // A geometry the reader can't handle: no estimate for this zone.
                log.warn("Terrain for {} failed: {}", what, e.toString())
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
        val log = LoggerFactory.getLogger(TerrainLoader::class.java)
        const val POOL_SIZE = 2
        const val ATTEMPTS = 2
        const val RETRY_DELAY_MILLIS = 5_000L

        /** A zone by streets ends at most 1.25 times its circle out (StreetZoneBuilder): a little more than that. */
        const val REACH = 1.3
    }
}
