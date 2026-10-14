package app.hovanki.server.live

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.stereotype.Component

/**
 * Tomcat's buffers of every open socket at 2 KB instead of its 8 KB: the frame read, the message, the encoder and the
 * output buffers, 48 KB of the heap for a socket's whole life, 12 KB now. With 1 800 sockets the heap after a
 * collection fell from 184 to 122 MB at the same CPU and latencies (perf/results/2026-10-14-ws-buffers.md). A longer
 * frame from the phone comes in pieces that [GameSockets] puts together; a longer answer goes out in a few writes.
 *
 * Tomcat reads the size once, from a system property, when its WebSocket classes load with the server's start: after
 * this customizer. A size given on the command line (`-Dorg.apache.tomcat.websocket.DEFAULT_BUFFER_SIZE`) stays.
 */
@Component
class SocketBuffers : WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
    override fun customize(factory: TomcatServletWebServerFactory) {
        if (System.getProperty(PROPERTY) == null) System.setProperty(PROPERTY, SIZE.toString())
    }

    companion object {
        const val PROPERTY = "org.apache.tomcat.websocket.DEFAULT_BUFFER_SIZE"
        const val SIZE = 2 * 1024
    }
}
