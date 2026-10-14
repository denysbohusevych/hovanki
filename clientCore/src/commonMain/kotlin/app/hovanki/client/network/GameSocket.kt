package app.hovanki.client.network

import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.SocketFrames
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One open socket of a game's live channel (docs/adr/0015-websockets.md): text frames both ways. The seam between
 * [WebSocketGameConnection] and the network: Ktor on the phones ([KtorGameSocketOpener]), a fake in the unit tests, a
 * simulated network around Ktor for the e2e bots.
 */
interface GameSocket {
    /** The next text frame from the server; null once the socket is closed ([closeCode] says why). */
    suspend fun receive(): String?

    /** Throws when the socket is gone. */
    suspend fun send(text: String)

    /** The close code the server sent, once [receive] returned null; null: none came (the network broke). */
    suspend fun closeCode(): Int?

    /** Closes the socket, if it is still open. */
    suspend fun close()
}

/** Opens a player's socket of their game; throws when it can't (no network, the server takes no upgrade). */
fun interface GameSocketOpener {
    suspend fun open(session: PlayerSession): GameSocket
}

/**
 * The live channel over Ktor's WebSockets (the client needs the `WebSockets` plugin, see [createHttpClient]): OkHttp
 * on Android and for the e2e bots, NSURLSession on iOS. The game token goes as `Authorization: Bearer`, like on every
 * game route, and [SocketFrames.HEADER] says the app takes the chat's frames.
 */
class KtorGameSocketOpener(private val client: HttpClient, private val serverUrl: ServerUrl) : GameSocketOpener {
    override suspend fun open(session: PlayerSession): GameSocket {
        // Ktor runs the upgrade in the client's own scope: cancelling the caller (the open's timeout, the game left)
        // does not stop it. A socket that opens after the caller gave up is closed at once, never left open unread.
        // Never failing itself: a failed child would cancel the client's own job, and every request with it.
        val opening = client.async {
            runCatching {
                client.webSocketSession(socketUrl(serverUrl.value, session.gameId)) {
                    header(HttpHeaders.Authorization, "${ApiRoutes.AUTH_SCHEME} ${session.token}")
                    // The chat's messages as they are sent, not a poke and a sync for each.
                    header(SocketFrames.HEADER, SocketFrames.CHAT)
                }
            }
        }
        try {
            return KtorGameSocket(opening.await().getOrThrow())
        } catch (e: CancellationException) {
            opening.invokeOnCompletion { cause ->
                if (cause == null) client.launch { opening.await().getOrNull()?.let { KtorGameSocket(it).close() } }
            }
            throw e
        }
    }
}

/** The socket's address of [gameId] on the server at [serverUrl]: `wss://` for `https://`, `ws://` for `http://`. */
fun socketUrl(serverUrl: String, gameId: GameId): String {
    val base = when {
        serverUrl.startsWith("https://") -> "wss://" + serverUrl.removePrefix("https://")
        serverUrl.startsWith("http://") -> "ws://" + serverUrl.removePrefix("http://")
        else -> serverUrl
    }
    return base + ApiRoutes.socket(gameId)
}

private class KtorGameSocket(private val session: DefaultClientWebSocketSession) : GameSocket {
    override suspend fun receive(): String? {
        while (true) {
            val frame = session.incoming.receiveCatching().getOrNull() ?: return null
            if (frame is Frame.Text) return frame.readText()
        }
    }

    override suspend fun send(text: String) {
        session.send(Frame.Text(text))
    }

    override suspend fun closeCode(): Int? = try {
        withTimeoutOrNull(CLOSE_REASON_WAIT_MILLIS) { session.closeReason.await() }?.code?.toInt()
    } catch (e: CancellationException) {
        // A session cancelled by its own failure is no cancellation of ours: no code, the caller opens a new socket.
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
