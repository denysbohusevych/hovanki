package app.hovanki.e2e.bot

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncMetricsTest {
    @Test
    fun onlyFiveHundredsAreServerErrors() {
        val metrics = SyncMetrics()

        metrics.record(Exchange("POST", "/api/v1/games/g/sync", 200, 12, "{}"))
        metrics.record(Exchange("POST", "/api/v1/games/g/catches", 422, 5, "{}"))
        metrics.record(Exchange("POST", "/api/v1/games/g/sync", null, 3, null))
        metrics.record(Exchange("POST", "/api/v1/games/g/confirm", 200, 4, null, isResponseLost = true))
        metrics.record(Exchange("POST", "/api/v1/games/g/sync", 500, 7, "{}"))
        metrics.record(Exchange("GET", "/api/v1/me", 503, 2, "{}"))

        assertEquals(listOf("POST /api/v1/games/g/sync -> 500", "GET /api/v1/me -> 503"), metrics.serverErrors)
        assertEquals(5, metrics.errors.size, "422, I/O error, lost response, 500, 503")
        assertEquals(2, metrics.syncCount, "a lost response or an I/O error has no latency to count")
    }
}
