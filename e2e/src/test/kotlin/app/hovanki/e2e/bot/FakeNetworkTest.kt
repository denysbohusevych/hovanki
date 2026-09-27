package app.hovanki.e2e.bot

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class FakeNetworkTest {
    private val exchanges = CopyOnWriteArrayList<Exchange>()
    private val network = FakeNetwork { exchanges += it }

    /** Requests that reached the "server": the last interceptor answers them without a real network. */
    private val served = AtomicInteger()

    private val client = OkHttpClient.Builder()
        .addInterceptor(network)
        .addInterceptor(Interceptor { chain -> serve(chain.request()) })
        .build()

    @Test
    fun aLostResponseReachedTheServer() {
        network.loseResponseTo("/catches")

        assertFailsWith<IOException> { call("/api/v1/games/g/catches") }
        assertEquals(1, served.get(), "the server got the request")
        val lost = exchanges.single()
        assertEquals(200, lost.status)
        assertTrue(lost.isResponseLost)

        assertEquals("ok", call("/api/v1/games/g/catches"), "only the next response is lost")
    }

    @Test
    fun lostResponsesMatchThePathAndCount() {
        network.loseResponseTo("/join", times = 2)

        assertEquals("ok", call("/api/v1/games/g/sync"))
        assertFailsWith<IOException> { call("/api/v1/games/join") }
        assertFailsWith<IOException> { call("/api/v1/games/join") }
        assertEquals("ok", call("/api/v1/games/join"))
        assertEquals(4, served.get())
    }

    @Test
    fun offlineRequestsNeverReachTheServer() {
        network.isOnline = false

        assertFailsWith<IOException> { call("/api/v1/games/g/sync") }
        assertEquals(0, served.get())
    }

    @Test
    fun aFlakyNetworkFailsSomeRequestsBeforeTheServer() {
        network.failRequests(0.5, seed = 7)

        val outcomes = (1..40).map { runCatching { call("/api/v1/games/g/sync") }.isSuccess }
        val failed = outcomes.count { !it }
        assertTrue(failed in 8..32, "about half fail: $failed of 40")
        assertEquals(40 - failed, served.get(), "failed requests never reached the server")
        assertEquals(failed, exchanges.count { it.status == null })

        network.failRequests(0.0)
        assertEquals("ok", call("/api/v1/games/g/sync"))
    }

    @Test
    fun latencyDelaysEveryRequest() {
        network.latency = 150.milliseconds
        val started = System.nanoTime()

        call("/api/v1/games/g/sync")

        assertTrue((System.nanoTime() - started) / 1_000_000 >= 150)
    }

    private fun call(path: String): String {
        val request = Request.Builder().url("http://phone.test$path").build()
        return client.newCall(request).execute().use { it.body!!.string() }
    }

    private fun serve(request: Request): Response {
        served.incrementAndGet()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("ok".toResponseBody())
            .build()
    }
}
