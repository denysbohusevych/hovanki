package app.hovanki.e2e.scenario

import app.hovanki.shared.protocol.ServerFeature

/**
 * How the bots sync in this run (docs/e2e.md, docs/adr/0015-websockets.md, docs/live-v2/client.md): like the app, the
 * live channel whenever the server has it on, else polling. `-Pe2e.transport=socket` turns v1's live channel on for
 * every scenario, so the whole suite plays over its sockets; `-Pe2e.transport=v2ws` turns the live channel v2 on, so it
 * plays over the WebSocket v2; `-Pe2e.transport=v2` turns its UDP on too, so it plays over datagrams, with the
 * WebSocket v2 behind them. By default all stay off, as on a server whose operator has not switched them on, and only
 * the scenarios about them (`LiveSocketTest`, `LiveV2Test`) use them.
 */
object E2eTransport {
    private val property: String? = System.getProperty("hovanki.e2e.transport")

    val isSocket: Boolean = property == "socket"

    val isLiveV2Ws: Boolean = property == "v2ws"

    val isLiveV2Udp: Boolean = property == "v2"

    val name: String = when {
        isSocket -> "socket"
        isLiveV2Ws -> "v2ws"
        isLiveV2Udp -> "v2"
        else -> "polling"
    }

    /** The server features this run keeps on whatever a scenario switches: the live channel it plays over. */
    val features: List<String> = when {
        isSocket -> listOf(ServerFeature.LIVE_SOCKET.name)
        isLiveV2Ws -> listOf(ServerFeature.LIVE_V2.name)
        isLiveV2Udp -> listOf(ServerFeature.LIVE_V2.name, ServerFeature.LIVE_UDP.name)
        else -> emptyList()
    }
}
