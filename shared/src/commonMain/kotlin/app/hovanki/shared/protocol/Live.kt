package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

// The opening of the live channel v2 (docs/live-v2/spec.md §4, §6): JSON like every command; what the session carries
// afterwards is protobuf (shared/src/commonMain/proto/live_v2.proto).

/** The body of [ApiRoutes.LIVE_OPEN]. */
@Serializable
data class LiveOpenRequest(
    /** The app's version, for the metrics. */
    val appVersion: String = "",
    /** The ways the phone can carry the session ([LiveTransports]); names the server doesn't know are skipped. */
    val transports: List<String> = listOf(LiveTransports.UDP, LiveTransports.WS),
)

/** A new live session of the player (docs/live-v2/spec.md §4). Its keys are never logged or stored. */
@Serializable
data class LiveOpenResponse(
    /** 8 random bytes, base64url without padding: in every datagram and in the WebSocket's address. */
    val sessionId: String,
    /** 16 random bytes, base64: the AES-128-GCM key of the `Up` datagrams (phone → server). */
    val upKey: String,
    /** 16 random bytes, base64: the key of the `Down` datagrams (server → phone). */
    val downKey: String,
    /** 16 bytes, base64: the tag of this session's RESET datagram (docs/live-v2/spec.md §5.5). */
    val resetToken: String,
    /** Where to send the datagrams; null: no UDP on this server now, the WebSocket v2 only. */
    val udp: LiveUdpEndpoint? = null,
    /** The WebSocket v2's path ([ApiRoutes.LIVE_WS]). */
    val wsPath: String = ApiRoutes.LIVE_WS,
    /** Server time by which the phone opens a new session: an hour after this one's opening at most. */
    val expiresAtMillis: Long,
    val serverTimeMillis: Long,
)

/** The server's UDP address of the live channel. */
@Serializable
data class LiveUdpEndpoint(val host: String, val port: Int)

/** The names of [LiveOpenRequest.transports]. */
object LiveTransports {
    const val UDP = "udp"
    const val WS = "ws"
}

/** The WebSocket v2 of a live session (docs/live-v2/spec.md §6). */
object LiveWs {
    /** The query parameter with the session's id. */
    const val SESSION_PARAM = "s"

    /** `Sec-WebSocket-Protocol`: the phone asks for it, the server agrees to it. */
    const val SUBPROTOCOL = "hovanki.live.v2"

    /**
     * Open a new live session over HTTPS: this one is unknown (the server restarted, it expired or idled out) or a
     * newer one of the player took its place (`CLOSE_REOPEN`, `CLOSE_REPLACED`). The other codes are [SocketClose]'s:
     * 4401 the token is not a player's, 4404 the game is gone, 4429 too often, 4503 the server has v2 off (back to v1).
     */
    const val CLOSE_REOPEN = 4410
}
