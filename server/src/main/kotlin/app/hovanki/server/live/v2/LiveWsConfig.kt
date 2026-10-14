package app.hovanki.server.live.v2

import app.hovanki.shared.protocol.ApiRoutes
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

/**
 * The WebSocket v2's route (docs/live-v2/spec.md §6), next to v1's ([app.hovanki.server.live.GameSocketConfig], which
 * turns WebSockets on and keeps both uncompressed): plain WebSocket, the subprotocol `hovanki.live.v2`.
 */
@Configuration(proxyBeanMethods = false)
class LiveWsConfig(private val handler: LiveWsHandler) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, ApiRoutes.LIVE_WS).addInterceptors(LiveWsHandshake())
    }
}
