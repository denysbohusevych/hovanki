package app.hovanki.server.live.v2

import app.hovanki.server.features.FeatureFlags
import app.hovanki.server.game.GameRegistry
import app.hovanki.shared.live.LiveLimits
import app.hovanki.shared.live.v2.CloseReason
import app.hovanki.shared.live.v2.Up
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.LiveWs
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.SocketClose
import org.springframework.http.HttpHeaders
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.SubProtocolCapable
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.adapter.NativeWebSocketSession
import org.springframework.web.socket.handler.BinaryWebSocketHandler
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.server.HandshakeInterceptor
import org.springframework.web.util.UriComponentsBuilder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The WebSocket v2 ([ApiRoutes.LIVE_WS], docs/live-v2/spec.md §6, server.md §4.4): a live session's link when UDP
 * doesn't get through. Every binary frame is one `Up`, answered with one `Down` by [LiveExchange]; pushes come on it
 * too while it is the session's link. As v1's socket: never refused at the upgrade, it opens and closes at once with a
 * code the app acts on; frames uncompressed ([app.hovanki.server.live.NoSocketCompression]), taken in pieces.
 */
@Component
class LiveWsHandler(
    private val sessions: LiveSessions,
    private val exchange: LiveExchange,
    private val registry: GameRegistry,
    private val features: FeatureFlags,
    private val metrics: LiveMetrics,
) : BinaryWebSocketHandler(),
    SubProtocolCapable {
    override fun getSubProtocols(): List<String> = listOf(LiveWs.SUBPROTOCOL)

    /** Frames come in pieces of Tomcat's buffer (8 KB): [WsLink.assemble] puts a longer one together. */
    override fun supportsPartialMessages(): Boolean = true

    override fun afterConnectionEstablished(ws: WebSocketSession) {
        (ws as? NativeWebSocketSession)?.getNativeSession(jakarta.websocket.Session::class.java)?.let {
            it.maxIdleTimeout = LiveLimits.SESSION_IDLE_MILLIS
            it.userProperties[TOMCAT_BLOCKING_SEND_TIMEOUT] = SEND_TIME_LIMIT_MILLIS.toLong()
        }
        val token = ws.attributes[TOKEN] as String?
        val ref = token?.let(registry::resolveToken)
        val session = (ws.attributes[SESSION] as String?)?.let(sessions::get)
        val refusal = when {
            !features.isEnabled(ServerFeature.LIVE_V2) -> CloseReason.CLOSE_FALLBACK_V1

            // The upgrade lost its header on the way: v1 decides about the game, as v1's socket does.
            token == null -> CloseReason.CLOSE_FALLBACK_V1

            ref == null -> CloseReason.CLOSE_SESSION_REJECTED

            // Unknown (the server restarted, it idled out) or another player's: a new one over HTTPS.
            session == null || session.ref != ref -> CloseReason.CLOSE_REOPEN

            registry.get(ref.gameId) == null -> CloseReason.CLOSE_GAME_NOT_FOUND

            else -> null
        }
        if (refusal != null || session == null) {
            close(ws, closeStatus(refusal ?: CloseReason.CLOSE_REOPEN))
            return
        }
        val link = WsLink(ConcurrentWebSocketSessionDecorator(ws, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_LIMIT_BYTES))
        ws.attributes[LINK] = link
        ws.attributes[LIVE_SESSION] = session
        sessions.attach(session, link)
    }

    override fun handleBinaryMessage(ws: WebSocketSession, message: BinaryMessage) {
        val link = ws.attributes[LINK] as WsLink? ?: return
        val session = ws.attributes[LIVE_SESSION] as LiveSession? ?: return
        val bytes = link.assemble(message.payload, message.isLast) ?: return
        metrics.up(bytes.size)
        val up = try {
            Up.ADAPTER.decode(bytes)
        } catch (e: Exception) {
            // A newer app's message this server can't read, or garbage: skipped, the link stays.
            metrics.badMessage()
            return
        }
        exchange.up(session, up, link)
    }

    override fun afterConnectionClosed(ws: WebSocketSession, status: CloseStatus) {
        val link = ws.attributes[LINK] as WsLink? ?: return
        val session = ws.attributes[LIVE_SESSION] as LiveSession? ?: return
        sessions.detach(session, link)
    }

    /** One WebSocket v2 as a session's link. Tomcat hands one socket's pieces in turn: no lock for [assemble]. */
    private inner class WsLink(private val ws: WebSocketSession) : LiveLink {
        private var pieces: ByteArrayOutputStream? = null

        /** The whole frame once its last piece is in; null before, or when it grew over the limit (closed: 1009). */
        fun assemble(piece: ByteBuffer, last: Boolean): ByteArray? {
            if (!ws.isOpen) return null
            val started = pieces
            if ((started?.size() ?: 0) + piece.remaining() > LiveLimits.MAX_WS_FRAME_BYTES) {
                pieces = null
                metrics.oversize()
                close(ws, CloseStatus.TOO_BIG_TO_PROCESS)
                return null
            }
            val bytes = ByteArray(piece.remaining()).also(piece::get)
            if (!last) {
                pieces = (started ?: ByteArrayOutputStream()).apply { write(bytes) }
                return null
            }
            pieces = null
            return if (started == null) bytes else started.apply { write(bytes) }.toByteArray()
        }

        override fun send(down: ByteArray): Boolean {
            if (!ws.isOpen) return false
            return try {
                ws.sendMessage(BinaryMessage(down))
                true
            } catch (e: Exception) {
                // Gone, or too slow to take what it is sent (the decorator's limits): it comes back with its session.
                close(ws, CloseStatus.SESSION_NOT_RELIABLE)
                false
            }
        }

        override fun close(reason: CloseReason?, goingAway: Boolean) = close(
            ws,
            when {
                reason != null -> closeStatus(reason)
                goingAway -> CloseStatus.GOING_AWAY
                else -> CloseStatus.NORMAL
            },
        )
    }

    private fun close(ws: WebSocketSession, status: CloseStatus) {
        try {
            if (ws.isOpen) ws.close(status)
        } catch (e: Exception) {
            // Closed meanwhile.
        }
    }

    companion object {
        internal const val TOKEN = "hovanki.live.token"
        internal const val SESSION = "hovanki.live.session"
        private const val LINK = "hovanki.live.link"
        private const val LIVE_SESSION = "hovanki.live.liveSession"

        /** A phone that can't take a frame within this, or lets this much pile up, is dropped: it comes back. */
        private const val SEND_TIME_LIMIT_MILLIS = 5_000
        private const val SEND_BUFFER_LIMIT_BYTES = 512 * 1024
        private const val TOMCAT_BLOCKING_SEND_TIMEOUT = "org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT"

        /** The WebSocket's close code of [reason] (spec §6): v1's codes, and [LiveWs.CLOSE_REOPEN]. */
        fun closeStatus(reason: CloseReason): CloseStatus = when (reason) {
            CloseReason.CLOSE_SESSION_REJECTED -> CloseStatus(SocketClose.SESSION_REJECTED, "session over")
            CloseReason.CLOSE_GAME_NOT_FOUND -> CloseStatus(SocketClose.GAME_NOT_FOUND, "game over")
            CloseReason.CLOSE_TOO_MANY -> CloseStatus(SocketClose.TOO_MANY, "too many")
            CloseReason.CLOSE_FALLBACK_V1 -> CloseStatus(SocketClose.POLL, "v1")
            CloseReason.CLOSE_REOPEN, CloseReason.CLOSE_REPLACED -> CloseStatus(LiveWs.CLOSE_REOPEN, "reopen")
        }
    }
}

/**
 * Takes the game token (`Authorization: Bearer`, as the HTTP routes do) and the session's id (`?s=`) into the socket's
 * attributes; [LiveWsHandler] decides once the socket is open. Never refuses the upgrade itself.
 */
class LiveWsHandshake : HandshakeInterceptor {
    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>,
    ): Boolean {
        val header = request.headers.getFirst(HttpHeaders.AUTHORIZATION).orEmpty()
        val token = header.removePrefix("${ApiRoutes.AUTH_SCHEME} ").trim()
        if (token.isNotEmpty() && token != header.trim()) attributes[LiveWsHandler.TOKEN] = token
        UriComponentsBuilder.fromUri(request.uri).build().queryParams.getFirst(LiveWs.SESSION_PARAM)
            ?.let { attributes[LiveWsHandler.SESSION] = it }
        return true
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?,
    ) = Unit
}
