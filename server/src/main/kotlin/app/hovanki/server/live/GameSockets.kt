package app.hovanki.server.live

import app.hovanki.server.game.GameRegistry
import app.hovanki.server.game.PlayerRef
import app.hovanki.server.game.PokeSink
import app.hovanki.server.game.Pokes
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.ServerFrame
import app.hovanki.shared.protocol.SocketClose
import app.hovanki.shared.protocol.SocketLimits
import app.hovanki.shared.protocol.protocolJson
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.SmartLifecycle
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * One open socket of a player (docs/adr/0015-websockets.md): [session] is thread-safe for sending (a
 * `ConcurrentWebSocketSessionDecorator`), [token] is the game token it was opened with, checked again on every sync.
 */
class LiveSocket(
    val session: WebSocketSession,
    val ref: PlayerRef,
    val token: String,
    private val pokeScheduler: ScheduledThreadPoolExecutor,
) {
    val openedAtNanos: Long = System.nanoTime()

    private val lock = Any()
    private var lastPokeNanos: Long? = null
    private var pokePending = false
    private val syncNanos = ArrayDeque<Long>()

    /**
     * Pokes the phone: sync now. At most one poke per [SocketLimits.POKE_GAP_MILLIS]; the pokes of a burst after one
     * are sent as one at the end of the gap. Sent on [pokeScheduler]'s threads, never the caller's: the request that
     * changed the game never waits for a phone.
     */
    fun poke() {
        val wait = synchronized(lock) {
            if (pokePending) return
            val now = System.nanoTime()
            val last = lastPokeNanos
            val waitNanos = if (last == null) 0L else last + POKE_GAP_NANOS - now
            if (waitNanos > 0) {
                pokePending = true
            } else {
                lastPokeNanos = now
            }
            waitNanos
        }
        try {
            if (wait <= 0) {
                pokeScheduler.execute { send(POKE) }
                return
            }
            pokeScheduler.schedule(
                {
                    synchronized(lock) {
                        pokePending = false
                        lastPokeNanos = System.nanoTime()
                    }
                    send(POKE)
                },
                wait,
                TimeUnit.NANOSECONDS,
            )
        } catch (e: RejectedExecutionException) {
            // The server is stopping: the sockets are being closed anyway.
        }
    }

    /** One more sync; false when the socket syncs more often than [SocketLimits.MAX_SYNCS_PER_WINDOW] allows. */
    fun countSync(): Boolean = synchronized(lock) {
        val now = System.nanoTime()
        while (syncNanos.isNotEmpty() && now - syncNanos.first() >= SYNC_WINDOW_NANOS) syncNanos.removeFirst()
        if (syncNanos.size >= SocketLimits.MAX_SYNCS_PER_WINDOW) return false
        syncNanos.addLast(now)
        true
    }

    fun send(frame: ServerFrame) = send(protocolJson.encodeToString(ServerFrame.serializer(), frame))

    private fun send(text: String) {
        if (!session.isOpen) return
        try {
            session.sendMessage(TextMessage(text))
        } catch (e: Exception) {
            // Gone, or too slow to take what it is sent (the decorator's limits): it reconnects or polls.
            close(CloseStatus.SESSION_NOT_RELIABLE)
        }
    }

    fun close(status: CloseStatus) {
        try {
            if (session.isOpen) session.close(status)
        } catch (e: Exception) {
            // Closed meanwhile.
        }
    }

    private companion object {
        val POKE = protocolJson.encodeToString(ServerFrame.serializer(), ServerFrame.Poke)
        val POKE_GAP_NANOS = TimeUnit.MILLISECONDS.toNanos(SocketLimits.POKE_GAP_MILLIS)
        val SYNC_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(SocketLimits.SYNC_WINDOW_MILLIS)
    }
}

/**
 * The open sockets of the games' players, in memory (docs/adr/0015-websockets.md, section 3): where [GameService]'s
 * pokes go ([PokeSink]). Holds nothing about a game but who is connected; a socket whose game is gone or whose token
 * no longer works is closed by the next sync, or by the sweep once a minute. Closes every socket when the server stops,
 * so the phones reconnect to the next one at once.
 */
@Component
class GameSockets(private val registry: GameRegistry) :
    PokeSink,
    SmartLifecycle,
    DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val byGame = ConcurrentHashMap<GameId, MutableSet<LiveSocket>>()

    val pokeScheduler = ScheduledThreadPoolExecutor(POKE_THREADS) { task ->
        Thread(task, "game-socket-pokes").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    @Volatile private var running = false

    fun opened(socket: LiveSocket) {
        val sockets = byGame.computeIfAbsent(socket.ref.gameId) { ConcurrentHashMap.newKeySet() }
        sockets += socket
        // One phone that reconnects while its old socket hangs, or a player on several phones: a few, not more.
        sockets.filter { it.ref == socket.ref }
            .sortedBy { it.openedAtNanos }
            .dropLast(SocketLimits.MAX_SOCKETS_PER_PLAYER)
            .forEach { it.close(CloseStatus(SocketClose.TOO_MANY, "too many sockets")) }
    }

    fun closed(socket: LiveSocket) {
        byGame.computeIfPresent(socket.ref.gameId) { _, sockets ->
            sockets -= socket
            sockets.takeUnless { it.isEmpty() }
        }
    }

    override fun poke(gameId: GameId, pokes: Pokes, except: PlayerId?) {
        val sockets = byGame[gameId] ?: return
        for (socket in sockets) {
            val playerId = socket.ref.playerId
            if (playerId != except && pokes.concerns(playerId)) socket.poke()
        }
    }

    /** Open sockets, of [gameId] or all; for the logs and the tests. */
    fun count(gameId: GameId? = null): Int =
        if (gameId == null) byGame.values.sumOf { it.size } else byGame[gameId]?.size ?: 0

    /** Closes the sockets of games that are gone (the janitor, staff) and of tokens that no longer work. */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    fun sweep() {
        for ((gameId, sockets) in byGame) {
            val gone = registry.get(gameId) == null
            for (socket in sockets) {
                when {
                    gone -> socket.close(CloseStatus(SocketClose.GAME_NOT_FOUND, "game over"))

                    registry.resolveToken(socket.token) != socket.ref ->
                        socket.close(CloseStatus(SocketClose.SESSION_REJECTED, "session over"))
                }
            }
        }
    }

    override fun start() {
        running = true
    }

    /** Before the web server's graceful shutdown: the phones reconnect to the next server, or poll meanwhile. */
    override fun stop() {
        running = false
        val open = byGame.values.flatten()
        if (open.isNotEmpty()) log.info("Closing {} game sockets", open.size)
        open.forEach { it.close(CloseStatus.GOING_AWAY) }
    }

    override fun isRunning(): Boolean = running

    override fun destroy() {
        pokeScheduler.shutdownNow()
    }

    private companion object {
        /** A phone gone silent blocks a thread for a send's time limit at most; the others keep poking. */
        const val POKE_THREADS = 4
    }
}
