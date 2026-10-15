package app.hovanki.server.live.v2

import app.hovanki.server.api.bearerToken
import app.hovanki.server.features.FeatureFlags
import app.hovanki.server.game.GameException
import app.hovanki.server.game.GameRegistry
import app.hovanki.shared.live.LiveDatagrams
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.LiveOpenRequest
import app.hovanki.shared.protocol.LiveOpenResponse
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.protocolJson
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.NativeWebRequest
import java.time.Clock
import java.util.Base64

/**
 * Opens a live session of the live channel v2 ([ApiRoutes.LIVE_OPEN], docs/live-v2/spec.md §4) with the player's game
 * token. While the server has [ServerFeature.LIVE_V2] off: 404 with [ErrorReason.FEATURE_DISABLED] before the token or
 * the body are looked at, and the app stays on v1. No UDP yet: `udp` is null, the session goes over the WebSocket v2.
 */
@RestController
class LiveOpenController(
    private val sessions: LiveSessions,
    private val registry: GameRegistry,
    private val features: FeatureFlags,
    private val metrics: LiveMetrics,
    private val clock: Clock,
) {
    @PostMapping(ApiRoutes.LIVE_OPEN)
    fun open(@RequestBody(required = false) body: String?, webRequest: NativeWebRequest): LiveOpenResponse {
        if (!features.isEnabled(ServerFeature.LIVE_V2)) {
            metrics.opened("disabled")
            throw GameException(ErrorCode.NOT_FOUND, "The live channel v2 is off", ErrorReason.FEATURE_DISABLED)
        }
        val token = webRequest.bearerToken()
        val ref = token?.let(registry::resolveToken)
        if (token == null || ref == null) {
            metrics.opened("rejected")
            throw GameException(ErrorCode.UNAUTHORIZED, "Unknown or expired token")
        }
        // What the phone can do, for later (UDP): read only to refuse what isn't a request at all.
        if (!body.isNullOrBlank()) {
            try {
                protocolJson.decodeFromString(LiveOpenRequest.serializer(), body)
            } catch (e: IllegalArgumentException) {
                throw GameException(ErrorCode.BAD_REQUEST, "Malformed request body")
            }
        }
        if (registry.get(ref.gameId) == null) {
            metrics.opened("not_found")
            throw GameException(ErrorCode.NOT_FOUND, "The game is over or never existed")
        }
        val session = try {
            sessions.open(ref, token)
        } catch (e: GameException) {
            metrics.opened("too_many")
            throw e
        }
        metrics.opened("ok")
        val base64 = Base64.getEncoder()
        return LiveOpenResponse(
            sessionId = LiveDatagrams.sessionIdText(session.id),
            upKey = base64.encodeToString(session.upKey.encoded),
            downKey = base64.encodeToString(session.downKey.encoded),
            resetToken = base64.encodeToString(sessions.resetToken(session)),
            udp = null,
            wsPath = ApiRoutes.LIVE_WS,
            expiresAtMillis = session.expiresAtMillis,
            serverTimeMillis = clock.millis(),
        )
    }
}
