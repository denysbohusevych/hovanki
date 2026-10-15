package app.hovanki.server.live.v2

import app.hovanki.server.game.PokeSink
import app.hovanki.server.game.Pokes
import app.hovanki.server.live.GameSockets
import app.hovanki.shared.live.LiveLimits
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.PlayerId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The pushes of the live channel v2 (docs/live-v2/spec.md §8.7, server.md §4.5): whom a game's change concerns
 * ([Pokes], as v1's pokes) gets the new state and chat on their live session's link, at most once per
 * [LiveLimits.PUSH_GAP_MILLIS]; what comes within the gap goes with it. Built and sent on this class's own threads,
 * never the caller's: the request that changed the game never waits for a phone.
 */
@Component
class LivePushes(private val sessions: LiveSessions, private val exchange: LiveExchange) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)

    private val scheduler = ScheduledThreadPoolExecutor(THREADS) { task ->
        Thread(task, "live-pushes").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun poke(gameId: GameId, pokes: Pokes, except: PlayerId?) {
        for (session in sessions.ofGame(gameId)) {
            val playerId = session.ref.playerId
            // The one who asked has the fresh state in their answer; a session without a link gets it with its next Up.
            if (playerId == except || session.link == null) continue
            val chat = pokes.chatFor(playerId)
            val state = pokes.concerns(playerId, takesChat = true)
            if (chat.isEmpty() && !state) continue
            val wait = session.addPush(state, chat, System.nanoTime()) ?: continue
            try {
                if (wait <= 0) {
                    scheduler.execute { push(session) }
                } else {
                    scheduler.schedule({ push(session) }, wait, TimeUnit.NANOSECONDS)
                }
            } catch (e: RejectedExecutionException) {
                // The server is stopping: the links are being closed anyway.
            }
        }
    }

    private fun push(session: LiveSession) {
        val pending = session.takePush(System.nanoTime())
        try {
            exchange.push(session, pending)
        } catch (e: Exception) {
            log.error("Unhandled error in a live push", e)
        }
    }

    override fun destroy() {
        scheduler.shutdownNow()
    }

    private companion object {
        /** A phone that stopped reading holds a thread for a send's time limit at most; the others keep pushing. */
        const val THREADS = 4
    }
}

/**
 * Where [app.hovanki.server.game.GameService]'s pokes go: v1's sockets ([GameSockets]) and v2's live sessions
 * ([LivePushes]) both, each to its own phones. [LivePushes] is taken at the first poke: it needs the game service,
 * which needs this.
 */
@Primary
@Component
class LivePokeSink(private val v1: GameSockets, private val v2Provider: ObjectProvider<LivePushes>) : PokeSink {
    private val v2 by lazy { v2Provider.getObject() }

    override fun poke(gameId: GameId, pokes: Pokes, except: PlayerId?) {
        v1.poke(gameId, pokes, except)
        v2.poke(gameId, pokes, except)
    }
}
