package app.hovanki.server.sentry

import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.protocolJson
import io.sentry.Hint
import io.sentry.ITransportFactory
import io.sentry.RequestDetails
import io.sentry.Sentry
import io.sentry.SentryEnvelope
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import org.junit.jupiter.api.AfterAll
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The staging server with a DSN (a made-up one: the transport is replaced, nothing leaves the test): the starter
 * starts, errors become events, and what leaves is what [ServerEventScrubber] allows.
 */
@SpringBootTest(properties = ["sentry.dsn=https://publickey@o0.ingest.de.sentry.io/0"])
@AutoConfigureMockMvc
@Import(SentryEnabledTest.Recording::class)
class SentryEnabledTest(@Autowired private val transport: RecordingTransport, @Autowired private val mvc: MockMvc) {
    private val token = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    @Test
    fun theStarterStartsWithOurFilterAndNoPersonalData() {
        assertTrue(Sentry.isEnabled())
        val options = Sentry.getCurrentScopes().options
        assertEquals("staging", options.environment)
        @Suppress("DEPRECATION")
        assertEquals(false, options.isSendDefaultPii)
        assertEquals(SentryOptions.RequestSize.NONE, options.maxRequestBodySize)
        assertNotNull(options.beforeSend)
        assertNotNull(options.beforeBreadcrumb)
        assertNull(options.tracesSampleRate)
    }

    @Test
    fun aCapturedErrorLeavesWithoutPositionsTokensOrEmails() {
        Sentry.addBreadcrumb("requested /games/ABC234 by anna@example.com")
        Sentry.captureException(IllegalStateException("no fix at 55.751244, 37.617300 for anna@example.com $token"))

        val event = transport.awaitEvent { it.exceptions?.any { e -> e.type == "IllegalStateException" } == true }
        val text = event.exceptions!!.joinToString { it.value.orEmpty() }
        assertEquals("no fix at [coord], [coord] for [email] [token]", text)
        assertNull(event.user)
        assertNull(event.request)
        assertNull(event.breadcrumbs)
        assertNull(event.serverName)
        assertNull(event.tags)
        assertEquals("staging", event.environment)
    }

    @Test
    fun aLoggedErrorBecomesAnEventThroughLogback() {
        // ApiExceptionHandler logs an unhandled error like this.
        LoggerFactory.getLogger("app.hovanki.server.api.ApiExceptionHandler")
            .error("Unhandled error for anna@example.com", IllegalArgumentException("bad value 55.751244"))

        val event = transport.awaitEvent { it.message?.formatted?.startsWith("Unhandled error") == true }
        assertEquals("Unhandled error for [email]", event.message!!.formatted)
        assertEquals("bad value [coord]", event.exceptions!!.first().value)
        assertNull(event.breadcrumbs)
        assertNull(event.user)
    }

    @Test
    fun aClientErrorTheApiAnswersIsNotAnEvent() {
        // An unknown code, a malformed body: players' mistakes that ApiExceptionHandler answers with 4xx, every day.
        // Sentry's own exception resolver runs after the handler's (its order is 1), so only what nothing handled
        // reaches it; this keeps it so.
        val unknownCode = protocolJson.encodeToString(JoinGameRequest.serializer(), JoinGameRequest("NOPE00", "Anna"))
        mvc.post(ApiRoutes.JOIN) {
            contentType = MediaType.APPLICATION_JSON
            content = unknownCode
        }.andReturn().response.also { assertEquals(404, it.status) }
        mvc.post(ApiRoutes.JOIN) {
            contentType = MediaType.APPLICATION_JSON
            content = "{not json"
        }.andReturn().response.also { assertEquals(400, it.status) }

        // The marker goes through the same queue: when it is out, anything the requests made is out too.
        Sentry.captureException(IllegalStateException("marker-after-client-errors"))
        transport.awaitEvent { it.exceptions?.any { e -> e.value == "marker-after-client-errors" } == true }
        val reported = transport.events().flatMap { it.exceptions.orEmpty() }.map { it.type }
        assertTrue("GameException" !in reported && "HttpMessageNotReadableException" !in reported, reported.toString())
    }

    /** A transport that keeps what the SDK would have sent. */
    class RecordingTransport : ITransportFactory {
        private val events = CopyOnWriteArrayList<SentryEvent>()

        override fun create(options: SentryOptions, requestDetails: RequestDetails): ITransport = object : ITransport {
            override fun send(envelope: SentryEnvelope, hint: Hint) {
                envelope.items.forEach { item ->
                    item.getEvent(options.serializer)?.let { events += it }
                }
            }

            override fun flush(timeoutMillis: Long) = Unit

            override fun getRateLimiter(): RateLimiter? = null

            override fun close(isRestarting: Boolean) = Unit

            override fun close() = Unit
        }

        fun events(): List<SentryEvent> = events.toList()

        fun awaitEvent(matching: (SentryEvent) -> Boolean): SentryEvent {
            val deadline = System.currentTimeMillis() + WAIT_MILLIS
            while (System.currentTimeMillis() < deadline) {
                Sentry.flush(FLUSH_MILLIS)
                events.firstOrNull(matching)?.let { return it }
                Thread.sleep(POLL_MILLIS)
            }
            fail("No matching event was sent; the events: ${events.map { it.message?.formatted }}")
        }

        private companion object {
            const val WAIT_MILLIS = 10_000L
            const val FLUSH_MILLIS = 500L
            const val POLL_MILLIS = 50L
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Recording {
        @Bean
        fun recordingTransport(): RecordingTransport = RecordingTransport()
    }

    companion object {
        /** The SDK is global: leave it off for the tests that follow in this JVM. */
        @JvmStatic
        @AfterAll
        fun closeSentry() {
            Sentry.close()
        }
    }
}
