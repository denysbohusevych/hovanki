package app.hovanki.server.metrics

import app.hovanki.shared.protocol.SocketClose
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

/** The server's own meters: the live channel's closes by code, the syncs of the field log's window. */
class ServerMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = ServerMetrics(registry)

    private fun closes(code: String): Double =
        registry.find(ServerMetrics.SOCKET_CLOSE_COUNTER).tag("code", code).counter()?.count() ?: 0.0

    @Test
    fun socketClosesAreCountedByCodeAndStrangeOnesAsOther() {
        metrics.socketClosed(SocketClose.TOO_MANY)
        metrics.socketClosed(SocketClose.TOO_MANY)
        metrics.socketClosed(1006)
        metrics.socketClosed(4999)
        metrics.socketClosed(3000)
        assertEquals(2.0, closes("4429"))
        assertEquals(1.0, closes("1006"))
        assertEquals(2.0, closes("other"), "codes no side sends: the tag's values stay few")
    }

    @Test
    fun theSyncWindowKeepsTimesOnlyWhileTaken() {
        metrics.timeSync(SyncTransport.POLL) { }
        assertEquals(0, metrics.takeSyncWindow(SyncTransport.POLL).size, "off: nothing kept")
        metrics.windowed = true
        repeat(3) { metrics.timeSync(SyncTransport.POLL) { } }
        metrics.timeSync(SyncTransport.SOCKET) { }
        assertEquals(3, metrics.takeSyncWindow(SyncTransport.POLL).size)
        assertEquals(1, metrics.takeSyncWindow(SyncTransport.SOCKET).size)
        assertEquals(0, metrics.takeSyncWindow(SyncTransport.POLL).size, "a new window")
        // The timer counts every sync, window or not.
        assertEquals(4L, registry.find(ServerMetrics.SYNC_TIMER).tag("transport", "poll").timer()?.count())
    }
}
