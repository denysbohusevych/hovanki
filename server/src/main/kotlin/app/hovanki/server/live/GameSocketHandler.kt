package app.hovanki.server.live

import app.hovanki.server.features.FeatureFlags
import app.hovanki.server.game.GameException
import app.hovanki.server.game.GameRegistry
import app.hovanki.server.game.GameService
import app.hovanki.server.metrics.ServerMetrics
import app.hovanki.server.metrics.SyncTransport
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ClientFrame
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.ServerFrame
import app.hovanki.shared.protocol.SocketClose
import app.hovanki.shared.protocol.SocketFrames
import app.hovanki.shared.protocol.SocketLimits
import app.hovanki.shared.protocol.protocolJson
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.adapter.NativeWebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import org.springframework.web.util.UriTemplate

/**
 * The game's live channel ([ApiRoutes.SOCKET], docs/adr/0015-websockets.md, section 3): a player's `sync` as frames,
 * answered with the same snapshot as `POST /sync` by the same [GameService.sync], and the pokes and chat messages of
 * [GameSockets].
 *
 * The upgrade itself is never refused for a bad token: the socket opens and closes at once with a code the app acts on
 * ([SocketClose]), which a refused handshake could not carry. Every sync checks again that the token still works and
 * the server still has [ServerFeature.LIVE_SOCKET] on. Nothing of a frame is logged: tokens, positions and chat stay out
 * of the logs.
 */
@Component
class GameSocketHandler(
    private val games: GameService,
    private val registry: GameRegistry,
    private val sockets: GameSockets,
    private val features: FeatureFlags,
    private val metrics: ServerMetrics,
) : TextWebSocketHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun afterConnectionEstablished(session: WebSocketSession) {
        session.textMessageSizeLimit = SocketLimits.MAX_FRAME_BYTES
        (session as? NativeWebSocketSession)?.getNativeSession(jakarta.websocket.Session::class.java)?.let {
            it.maxIdleTimeout = SocketLimits.IDLE_TIMEOUT_MILLIS
            // A phone that stopped reading holds a sending thread this long at most (Tomcat's default is 20 s).
            it.userProperties[TOMCAT_BLOCKING_SEND_TIMEOUT] = SEND_TIME_LIMIT_MILLIS.toLong()
        }
        val token = session.attributes[TOKEN] as String?
        val gameId = session.attributes[GAME_ID] as String?
        val ref = token?.let(registry::resolveToken)
        val refusal = when {
            !features.isEnabled(ServerFeature.LIVE_SOCKET) -> CloseStatus(SocketClose.POLL, "poll")
            ref == null || ref.gameId.value != gameId -> CloseStatus(SocketClose.SESSION_REJECTED, "session over")
            registry.get(ref.gameId) == null -> CloseStatus(SocketClose.GAME_NOT_FOUND, "game over")
            else -> null
        }
        if (refusal != null || ref == null || token == null) {
            session.close(refusal ?: CloseStatus.POLICY_VIOLATION)
            return
        }
        val concurrent = ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_LIMIT_BYTES)
        val takesChat = session.attributes[TAKES_CHAT] == true
        val socket = LiveSocket(concurrent, ref, token, sockets.pokeScheduler, takesChat)
        session.attributes[SOCKET] = socket
        sockets.opened(socket)
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val socket = session.attributes[SOCKET] as LiveSocket? ?: return
        val frame = try {
            protocolJson.decodeFromString(ClientFrame.serializer(), message.payload)
        } catch (e: SerializationException) {
            // A newer app's frame this server doesn't know, or garbage: skipped, the socket stays.
            return
        } catch (e: IllegalArgumentException) {
            return
        }
        when (frame) {
            is ClientFrame.Sync -> sync(socket, frame)
        }
    }

    private fun sync(socket: LiveSocket, frame: ClientFrame.Sync) {
        when {
            !features.isEnabled(ServerFeature.LIVE_SOCKET) -> socket.close(CloseStatus(SocketClose.POLL, "poll"))

            registry.resolveToken(socket.token) != socket.ref ->
                socket.close(CloseStatus(SocketClose.SESSION_REJECTED, "session over"))

            !socket.countSync() -> socket.close(CloseStatus(SocketClose.TOO_MANY, "too many syncs"))

            else -> {
                val reply = try {
                    ServerFrame.Snapshot(
                        frame.seq,
                        games.sync(socket.ref, socket.ref.gameId, frame.request, SyncTransport.SOCKET),
                    )
                } catch (e: GameException) {
                    when (e.code) {
                        ErrorCode.UNAUTHORIZED, ErrorCode.FORBIDDEN -> {
                            socket.close(CloseStatus(SocketClose.SESSION_REJECTED, "session over"))
                            return
                        }

                        ErrorCode.NOT_FOUND -> {
                            socket.close(CloseStatus(SocketClose.GAME_NOT_FOUND, "game over"))
                            return
                        }

                        else -> ServerFrame.Error(
                            frame.seq,
                            ApiError(e.code, e.message.orEmpty(), e.reason, e.untilMillis),
                            e.retryAfterSeconds,
                        )
                    }
                } catch (e: Exception) {
                    log.error("Unhandled error in a game socket's sync", e)
                    socket.close(CloseStatus.SERVER_ERROR)
                    return
                }
                socket.send(reply)
            }
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        // Whoever closed it, by its code (hovanki.socket.close): the refusals at the start too.
        metrics.socketClosed(status.code)
        (session.attributes[SOCKET] as LiveSocket?)?.let(sockets::closed)
    }

    companion object {
        internal const val TOKEN = "hovanki.token"
        internal const val GAME_ID = "hovanki.gameId"
        internal const val TAKES_CHAT = "hovanki.takesChat"
        private const val SOCKET = "hovanki.socket"

        /** A phone that can't take a frame within this, or lets this much pile up, is dropped: it reconnects. */
        private const val SEND_TIME_LIMIT_MILLIS = 5_000
        private const val SEND_BUFFER_LIMIT_BYTES = 512 * 1024
        private const val TOMCAT_BLOCKING_SEND_TIMEOUT = "org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT"
    }
}

/**
 * Takes the game token (`Authorization: Bearer`, as the HTTP routes do), the game of the path and whether the app
 * takes the chat's frames ([SocketFrames.HEADER]) into the socket's attributes; [GameSocketHandler] decides once the
 * socket is open. Never refuses the upgrade itself.
 */
class GameSocketHandshake : HandshakeInterceptor {
    private val path = UriTemplate(ApiRoutes.SOCKET)

    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>,
    ): Boolean {
        val header = request.headers.getFirst(HttpHeaders.AUTHORIZATION).orEmpty()
        val token = header.removePrefix("${ApiRoutes.AUTH_SCHEME} ").trim()
        if (token.isNotEmpty() && token != header.trim()) attributes[GameSocketHandler.TOKEN] = token
        path.match(request.uri.path)["gameId"]?.let { attributes[GameSocketHandler.GAME_ID] = it }
        val frames = request.headers[SocketFrames.HEADER].orEmpty().flatMap { it.split(',') }.map { it.trim() }
        if (SocketFrames.CHAT in frames) attributes[GameSocketHandler.TAKES_CHAT] = true
        return true
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?,
    ) = Unit
}
