package app.hovanki.server.live.v2

import app.hovanki.server.features.FeatureFlags
import app.hovanki.server.game.GameException
import app.hovanki.server.game.GameRegistry
import app.hovanki.server.game.PlayerRef
import app.hovanki.shared.live.LiveDatagrams
import app.hovanki.shared.live.LiveLimits
import app.hovanki.shared.live.v2.CloseReason
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.ServerFeature
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The live sessions of the live channel v2, in memory (docs/live-v2/server.md §4.1–4.2): opened over HTTPS, found by
 * id when a WebSocket v2 attaches, by game when its players are pushed. A session ends when its token stops being a
 * player's, its game is gone, the server turns v2 off, it idles or grows old, or a newer one of the same player takes
 * its place; the sweep finds the ones nobody asks about. The server stopping drops every link: the phones open anew.
 */
@Component
class LiveSessions(
    private val registry: GameRegistry,
    private val features: FeatureFlags,
    private val clock: Clock,
    private val metrics: LiveMetrics,
    properties: LiveProperties,
    meters: MeterRegistry,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()
    private val serials = AtomicLong()
    private val byId = ConcurrentHashMap<Long, LiveSession>()
    private val byGame = ConcurrentHashMap<GameId, MutableSet<LiveSession>>()

    /** The times of each player's recent opens: [LiveLimits.MAX_OPENS_PER_MINUTE]. */
    private val opens = ConcurrentHashMap<PlayerRef, ArrayDeque<Long>>()

    private val resetKey = SecretKeySpec(resetSecret(properties.resetSecret), HMAC)

    @Volatile private var running = false

    init {
        for (transport in listOf(LiveMetrics.WS2, LiveMetrics.UDP, NONE)) {
            Gauge.builder(SESSIONS_GAUGE, byId) { sessions ->
                sessions.values.count { (it.link?.transport ?: NONE) == transport }.toDouble()
            }
                .description(
                    "Live sessions of the live channel v2 (docs/live-v2/server.md §5), by the link they are on",
                )
                .tag("transport", transport)
                .register(meters)
        }
    }

    /**
     * A new live session of [ref] (opened with [token]); the player's oldest goes when they have more than
     * [LiveLimits.MAX_SESSIONS_PER_PLAYER]. Too many opens: [GameException.tooManyRequests].
     */
    fun open(ref: PlayerRef, token: String): LiveSession {
        val now = clock.millis()
        countOpen(ref, now)
        val session = generateSequence { random.nextLong() }
            .map { id ->
                LiveSession(
                    id = id,
                    serial = serials.incrementAndGet(),
                    ref = ref,
                    token = token,
                    upKey = ByteArray(KEY_BYTES).also(random::nextBytes),
                    downKey = ByteArray(KEY_BYTES).also(random::nextBytes),
                    openedAtMillis = now,
                    expiresAtMillis = now + LiveLimits.SESSION_MAX_AGE_MILLIS,
                )
            }
            .first { byId.putIfAbsent(it.id, it) == null }
        // Added inside the map's own step: a set the last session's end takes out meanwhile would lose it.
        val ofGame = byGame.compute(ref.gameId) { _, sessions ->
            (sessions ?: ConcurrentHashMap.newKeySet()).also { it += session }
        }!!
        ofGame.filter { it.ref == ref }
            .sortedBy { it.serial }
            .dropLast(LiveLimits.MAX_SESSIONS_PER_PLAYER)
            .forEach { end(it, CloseReason.CLOSE_REPLACED) }
        return session
    }

    private fun countOpen(ref: PlayerRef, now: Long) {
        val times = opens.computeIfAbsent(ref) { ArrayDeque() }
        synchronized(times) {
            while (times.isNotEmpty() && now - times.first() >= OPEN_WINDOW_MILLIS) times.removeFirst()
            if (times.size >= LiveLimits.MAX_OPENS_PER_MINUTE) {
                throw GameException.tooManyRequests(Duration.ofMillis(times.first() + OPEN_WINDOW_MILLIS - now))
            }
            times.addLast(now)
        }
    }

    /** The live session [text] names ([LiveDatagrams.sessionIdText]); null: no such session, or it is over. */
    fun get(text: String): LiveSession? = LiveDatagrams.sessionIdOf(text)?.let(byId::get)

    /** The live session with the id [id] (a datagram's header); null: no such session, or it is over. */
    fun get(id: Long): LiveSession? = byId[id]

    /** The live sessions of [gameId]'s players. */
    fun ofGame(gameId: GameId): Collection<LiveSession> = byGame[gameId].orEmpty()

    /** Live sessions now; for the tests. */
    fun count(): Int = byId.size

    /** The tag of the session's RESET (spec §5.5): HMAC-SHA256 of its id under the reset secret, the first 16 bytes. */
    fun resetToken(session: LiveSession): ByteArray = resetToken(session.id)

    /** The RESET tag of the session with the id [sessionId], known or not (one that is gone: a RESET answers it). */
    fun resetToken(sessionId: Long): ByteArray {
        val id = ByteBuffer.allocate(LiveDatagrams.SESSION_ID_BYTES).putLong(sessionId).array()
        return Mac.getInstance(HMAC).apply { init(resetKey) }.doFinal(id).copyOf(LiveDatagrams.RESET_TAG_BYTES)
    }

    /** [link] carries [session] from now on: pushes go to it, the link before it closes (the phone left it). */
    fun attach(session: LiveSession, link: LiveLink) {
        var over = false
        var moved = false
        val before = session.locked {
            over = session.closed
            if (over) {
                null
            } else {
                moved = session.lastTransport.let { it != null && it != link.transport }
                session.lastTransport = link.transport
                session.link.also { session.link = link }
            }
        }
        if (moved) metrics.switched(link.transport)
        // Closed outside the lock: a link may tell its handler it closed on this very thread.
        if (over) {
            link.close(CloseReason.CLOSE_REOPEN)
        } else if (before != null && before !== link) {
            before.close(reason = null)
        }
    }

    /** [link] closed: the session waits for the phone's next one, or idles out. */
    fun detach(session: LiveSession, link: LiveLink) {
        session.locked { if (session.link === link) session.link = null }
    }

    /** Ends [session] for [reason]: forgotten, and its link closed with the reason for the phone. */
    fun end(session: LiveSession, reason: CloseReason) {
        val link = session.locked {
            if (session.closed) return
            session.closed = true
            session.link.also { session.link = null }
        }
        byId.remove(session.id, session)
        byGame.computeIfPresent(session.ref.gameId) { _, sessions ->
            sessions -= session
            sessions.takeUnless { it.isEmpty() }
        }
        metrics.closed(reason)
        if (link != null) {
            link.close(reason)
        } else {
            // No link: a phone on UDP may still be confirming its address (spec §5.6). Its close goes there.
            session.udp?.link?.close(reason)
        }
    }

    /**
     * Why [session] can't go on now, or null (spec §13): v2 off, the game gone, the token no longer the player's, the
     * session expired. Checked on every Up and push.
     */
    fun refusal(session: LiveSession, now: Long): CloseReason? = when {
        !features.isEnabled(ServerFeature.LIVE_V2) -> CloseReason.CLOSE_FALLBACK_V1

        // UDP off: a session on UDP opens anew, and the open sends it to the WebSocket v2 (server.md §4.2).
        session.link?.transport == LiveMetrics.UDP && !features.isEnabled(ServerFeature.LIVE_UDP) ->
            CloseReason.CLOSE_REOPEN

        registry.get(session.ref.gameId) == null -> CloseReason.CLOSE_GAME_NOT_FOUND

        registry.resolveToken(session.token) != session.ref -> CloseReason.CLOSE_SESSION_REJECTED

        now >= session.expiresAtMillis -> CloseReason.CLOSE_REOPEN

        else -> null
    }

    /** Ends the sessions nobody asks about any more: also idle ones ([LiveLimits.SESSION_IDLE_MILLIS]). */
    @Scheduled(fixedDelayString = "PT10S", initialDelayString = "PT10S")
    fun sweep() = sweepAt(clock.millis())

    internal fun sweepAt(now: Long) {
        for (session in byId.values) {
            val reason = refusal(session, now)
                ?: CloseReason.CLOSE_REOPEN.takeIf { now - session.lastUpAtMillis >= LiveLimits.SESSION_IDLE_MILLIS }
            if (reason != null) end(session, reason)
        }
        opens.entries.removeIf { (_, times) ->
            synchronized(times) { times.isEmpty() || now - times.last() >= OPEN_WINDOW_MILLIS }
        }
    }

    override fun start() {
        running = true
    }

    /** Before the web server's graceful shutdown: every link closes, the phones come back to the next server. */
    override fun stop() {
        running = false
        val links = byId.values.mapNotNull { it.link }
        if (links.isNotEmpty()) log.info("Closing {} live links", links.size)
        links.forEach { it.close(reason = null, goingAway = true) }
    }

    override fun isRunning(): Boolean = running

    private companion object {
        const val KEY_BYTES = 16
        const val RESET_SECRET_BYTES = 32
        const val HMAC = "HmacSHA256"
        const val OPEN_WINDOW_MILLIS = 60_000L
        const val SESSIONS_GAUGE = "hovanki.live.sessions"
        const val NONE = "none"

        fun resetSecret(configured: String): ByteArray {
            if (configured.isBlank()) return ByteArray(RESET_SECRET_BYTES).also(SecureRandom()::nextBytes)
            val bytes = try {
                Base64.getDecoder().decode(configured.trim())
            } catch (e: IllegalArgumentException) {
                null
            }
            // The value itself never goes into the message.
            require(bytes != null && bytes.size == RESET_SECRET_BYTES) {
                "hovanki.live.reset-secret: 32 bytes in base64 expected"
            }
            return bytes
        }
    }
}
