package app.hovanki.server.game

import app.hovanki.shared.protocol.GameId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Wakes a game when something of it is due by the clock (docs/adr/0015-websockets.md, section 6): the end of the
 * hiding or the search, a claim's time out, a reveal, a glow. Without it such a moment happens on the next request of
 * any player; with it, on time, and whom it concerns is poked ([GameService.wake]). [Game] stays without threads: the
 * time still comes in as `nowMillis`. One timer per game, the earliest moment asked for; a moment that moved later
 * wakes the game for nothing, which asks for the next one.
 */
@Component
class GameDeadlines(private val games: ObjectProvider<GameService>, private val clock: Clock) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)

    private class Wake(val atMillis: Long, val future: ScheduledFuture<*>)

    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "game-deadlines").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    private val wakes = ConcurrentHashMap<GameId, Wake>()

    /** Wakes game [gameId] at [atMillis] (server time), unless it wakes sooner already. */
    fun schedule(gameId: GameId, atMillis: Long) {
        wakes.compute(gameId) { _, current ->
            if (current != null && current.atMillis <= atMillis && !current.future.isDone) {
                current
            } else {
                current?.future?.cancel(false)
                // A little after: the game's `now` is then past the moment for sure.
                val delay = (atMillis - clock.millis()).coerceAtLeast(0) + SLACK_MILLIS
                Wake(atMillis, executor.schedule({ wake(gameId, atMillis) }, delay, TimeUnit.MILLISECONDS))
            }
        }
    }

    /** Games with a timer set. */
    fun scheduled(): Int = wakes.size

    private fun wake(gameId: GameId, atMillis: Long) {
        wakes.computeIfPresent(gameId) { _, current -> current.takeUnless { it.atMillis == atMillis } }
        try {
            // Asks for the next moment itself, like every request does.
            games.getObject().wake(gameId)
        } catch (e: Exception) {
            log.warn("Could not wake a game: {}", e.javaClass.simpleName)
        }
    }

    override fun destroy() {
        executor.shutdownNow()
    }

    private companion object {
        const val SLACK_MILLIS = 5L
    }
}
