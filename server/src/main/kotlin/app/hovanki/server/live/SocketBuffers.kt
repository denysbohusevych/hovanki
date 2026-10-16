package app.hovanki.server.live

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.stereotype.Component

/**
 * Tomcat's buffers of every open connection at 2 KB instead of its 8 KB. A socket of the live channel holds them for its
 * whole life: the connection's read and write buffers (16 KB of the heap, 4 KB now) and the WebSocket's frame, message,
 * encoder and output buffers (48 KB, 12 KB now). With 1 800 sockets the heap after a collection fell from 184 to 101 MB
 * at the same CPU and latencies, polling unchanged (perf/results/2026-10-14-ws-buffers.md). A longer frame from the
 * phone comes in pieces that [GameSockets] puts together; a longer answer goes out in a few writes.
 *
 * Tomcat reads the WebSocket's size once, from a system property, when its WebSocket classes load with the server's
 * start: after this customizer. A size given on the command line (`-Dorg.apache.tomcat.websocket.DEFAULT_BUFFER_SIZE`)
 * stays.
 */
@Component
class SocketBuffers : WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
    override fun customize(factory: TomcatServletWebServerFactory) {
        if (System.getProperty(WEBSOCKET_PROPERTY) == null) System.setProperty(WEBSOCKET_PROPERTY, SIZE.toString())
        factory.addConnectorCustomizers({ connector ->
            connector.setProperty(READ_PROPERTY, SIZE.toString())
            connector.setProperty(WRITE_PROPERTY, SIZE.toString())
        })
    }

    companion object {
        const val SIZE = 2 * 1024
        const val WEBSOCKET_PROPERTY = "org.apache.tomcat.websocket.DEFAULT_BUFFER_SIZE"
        const val READ_PROPERTY = "socket.appReadBufSize"
        const val WRITE_PROPERTY = "socket.appWriteBufSize"
    }
}
