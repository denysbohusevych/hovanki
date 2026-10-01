package app.hovanki.server.sentry

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.OperatingSystem
import io.sentry.protocol.Request
import io.sentry.protocol.Response
import io.sentry.protocol.SentryException
import io.sentry.protocol.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ServerEventScrubberTest {
    private val token = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    private fun dirtyEvent() = SentryEvent().apply {
        user = User().apply {
            id = "u1"
            email = "anna@example.com"
            ipAddress = "203.0.113.7"
        }
        request = Request().apply {
            url = "https://staging.example/api/v1/games/ABC234/sync"
            headers = mapOf("Authorization" to "Bearer $token")
            data = """{"point":{"lat":55.7512,"lon":37.6173}}"""
        }
        setExtra("chat", "hello from Anna")
        setTag("player", "Anna")
        serverName = "ip-10-0-0-12"
        logger = "app.hovanki.server.api.ApiExceptionHandler"
        message = Message().apply {
            message = "Unhandled error for anna@example.com"
            formatted = "Unhandled error for anna@example.com"
            params = listOf("anna@example.com")
        }
        exceptions = listOf(
            SentryException().apply {
                module = "java.lang"
                type = "IllegalStateException"
                value = "no fix at 55.751244, 37.617300, Authorization: Bearer $token"
            },
            SentryException().apply {
                module = "org.postgresql.util"
                type = "PSQLException"
                value = "ERROR: duplicate key value violates unique constraint\n" +
                    "  Detail: Key (nickname)=(Anna) already exists."
            },
            SentryException().apply {
                module = "org.springframework.dao"
                type = "DuplicateKeyException"
                value = "PreparedStatementCallback; Key (email)=(anna@example.com)"
            },
        )
        breadcrumbs = listOf(Breadcrumb("POST /api/v1/games/ABC234/chat: hello from Anna"))
        contexts.setOperatingSystem(OperatingSystem().apply { name = "Linux" })
        contexts.setResponse(Response().apply { headers = mapOf("Set-Cookie" to "session=$token") })
        contexts.put("custom", mapOf("who" to "Anna"))
    }

    @Test
    fun anEventKeepsWhatWentWrong() {
        val event = ServerEventScrubber.scrub(dirtyEvent())

        val first = event.exceptions!!.first()
        assertEquals("IllegalStateException", first.type)
        assertEquals("no fix at [coord], [coord], Authorization: [redacted]", first.value)
        assertEquals("Unhandled error for [email]", event.message!!.formatted)
        assertEquals("app.hovanki.server.api.ApiExceptionHandler", event.logger)
        assertNotNull(event.contexts.operatingSystem)
    }

    @Test
    fun theTextOfADatabaseExceptionIsDroppedWhole() {
        val exceptions = ServerEventScrubber.scrub(dirtyEvent()).exceptions!!

        assertEquals(
            listOf("IllegalStateException", "PSQLException", "DuplicateKeyException"),
            exceptions.map { it.type },
        )
        assertNull(exceptions[1].value)
        assertNull(exceptions[2].value)
    }

    @Test
    fun aDatabaseExceptionWithoutAPackageIsRecognizedByItsFullName() {
        val event = SentryEvent().apply {
            exceptions = listOf(
                SentryException().apply {
                    type = "org.postgresql.util.PSQLException"
                    value = "Detail: Key (nickname)=(Anna) already exists."
                },
            )
        }
        assertNull(ServerEventScrubber.scrub(event).exceptions!!.single().value)
    }

    @Test
    fun anEventLosesWhoWhereAndEverythingFreeForm() {
        val event = ServerEventScrubber.scrub(dirtyEvent())

        assertNull(event.user)
        assertNull(event.request)
        assertNull(event.extras)
        assertNull(event.tags)
        assertNull(event.serverName)
        assertNull(event.breadcrumbs)
        assertNull(event.message!!.params)
        assertNull(event.contexts.response)
        assertNull(event.contexts.get("custom"))
    }

    @Test
    fun noBreadcrumbSurvives() {
        assertNull(ServerEventScrubber.scrubBreadcrumb(Breadcrumb("anything")))
    }

    @Test
    fun anEventWithNothingInItStaysEmpty() {
        val event = ServerEventScrubber.scrub(SentryEvent())

        assertNull(event.message)
        assertNull(event.exceptions)
        assertNull(event.contexts.device)
    }
}
