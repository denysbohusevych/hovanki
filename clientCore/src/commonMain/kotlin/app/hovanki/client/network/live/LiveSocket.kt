package app.hovanki.client.network.live

import app.hovanki.client.network.ServerUrl
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.LiveOpenResponse
import app.hovanki.shared.protocol.LiveWs
import app.hovanki.shared.protocol.PlayerSession
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One open WebSocket v2 of a live session (docs/live-v2/spec.md §6): binary frames, one `Up` or one `Down` each. The
 * seam between [LiveGameConnection] and the network, as `GameSocket` is v1's: Ktor on the phones
 * ([KtorLiveSocketOpener]), a fake in the unit tests, a simulated network around Ktor for the e2e bots.
 */
interface LiveSocket {
    /** The next binary frame from the server; null once the socket is closed ([closeCode] says why). */
    suspend fun receive(): ByteArray?

    /** Throws when the socket is gone. */
    suspend fun send(bytes: ByteArray)

    /** The close code the server sent, once [receive] returned null; null: none came (the network broke). */
    suspend fun closeCode(): Int?

    /** Closes the socket, if it is still open. */
    suspend fun close()
}

/** Opens the WebSocket v2 of the player's live session [live]; throws when it can't. */
fun interface LiveSocketOpener {
    suspend fun open(session: PlayerSession, live: LiveOpenResponse): LiveSocket
}

/**
 * The WebSocket v2 over Ktor's WebSockets, like v1's `KtorGameSocketOpener`: the game token as `Authorization: Bearer`,
 * the subprotocol [LiveWs.SUBPROTOCOL], the session's id in the address.
 */
class KtorLiveSocketOpener(private val client: HttpClient, private val serverUrl: ServerUrl) : LiveSocketOpener {
    override suspend fun open(session: PlayerSession, live: LiveOpenResponse): LiveSocket {
        // As v1: Ktor runs the upgrade in the client's own scope, so a socket that opens after the caller gave up is
        // closed at once, and a failure never cancels the client's own job.
        val opening = client.async {
            runCatching {
                client.webSocketSession(liveSocketUrl(serverUrl.value, live)) {
                    header(HttpHeaders.Authorization, "${ApiRoutes.AUTH_SCHEME} ${session.token}")
                    header(HttpHeaders.SecWebSocketProtocol, LiveWs.SUBPROTOCOL)
                }
            }
        }
        try {
            return KtorLiveSocket(opening.await().getOrThrow())
        } catch (e: CancellationException) {
            opening.invokeOnCompletion { cause ->
                if (cause == null) client.launch { opening.await().getOrNull()?.let { KtorLiveSocket(it).close() } }
            }
            throw e
        }
    }
}

/** The WebSocket v2's address of [live] on the server at [serverUrl]: `wss://` for `https://`, `ws://` for `http://`. */
fun liveSocketUrl(serverUrl: String, live: LiveOpenResponse): String {
    val base = when {
        serverUrl.startsWith("https://") -> "wss://" + serverUrl.removePrefix("https://")
        serverUrl.startsWith("http://") -> "ws://" + serverUrl.removePrefix("http://")
        else -> serverUrl
    }
    // The server says where; an address that is no path of this server is not followed.
    val path = live.wsPath.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: ApiRoutes.LIVE_WS
    return "$base$path?${LiveWs.SESSION_PARAM}=${live.sessionId}"
}

private class KtorLiveSocket(private val session: DefaultClientWebSocketSession) : LiveSocket {
    override suspend fun receive(): ByteArray? {
        while (true) {
            val frame = session.incoming.receiveCatching().getOrNull() ?: return null
            if (frame is Frame.Binary) return frame.data
        }
    }

    override suspend fun send(bytes: ByteArray) {
        session.send(Frame.Binary(true, bytes))
    }

    override suspend fun closeCode(): Int? = try {
        withTimeoutOrNull(CLOSE_REASON_WAIT_MILLIS) { session.closeReason.await() }?.code?.toInt()
    } catch (e: CancellationException) {
        if (currentCoroutineContext().isActive) null else throw e
    }

    override suspend fun close() {
        try {
            withContext(NonCancellable) { session.close() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Closed already.
        }
    }

    private companion object {
        const val CLOSE_REASON_WAIT_MILLIS = 1_000L
    }
}
