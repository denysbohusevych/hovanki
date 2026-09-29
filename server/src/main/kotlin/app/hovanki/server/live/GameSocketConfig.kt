package app.hovanki.server.live

import app.hovanki.shared.protocol.ApiRoutes
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

/**
 * The live channel's route (docs/adr/0015-websockets.md): plain WebSocket, no SockJS or STOMP. Only the server's own
 * pages may open it from a browser (Spring's default origin check); the apps send no `Origin` and pass.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
class GameSocketConfig(private val handler: GameSocketHandler) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, ApiRoutes.SOCKET).addInterceptors(GameSocketHandshake())
    }
}
