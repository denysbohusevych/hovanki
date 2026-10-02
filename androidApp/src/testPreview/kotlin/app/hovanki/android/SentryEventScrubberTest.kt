package app.hovanki.android

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Device
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SentryEventScrubberTest {
    private val token = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    private fun dirtyEvent() = SentryEvent().apply {
        user = User().apply {
            id = "install-id"
            email = "anna@example.com"
            username = "Anna"
            ipAddress = "203.0.113.7"
        }
        request = Request().apply {
            url = "https://hovanki.example/api/v1/games/ABC234/sync"
            headers = mapOf("Authorization" to "Bearer $token")
            data = """{"lat":55.7512,"lon":37.6173}"""
        }
        setExtra("position", "55.751244, 37.617300")
        setTag("nickname", "Anna")
        serverName = "phone-of-anna"
        transaction = "app.hovanki.android.MainActivity"
        message = Message().apply {
            message = "sync failed for anna@example.com"
            formatted = "sync failed for anna@example.com with Bearer $token"
            params = listOf("Anna")
        }
        exceptions = listOf(
            SentryException().apply {
                type = "java.lang.IllegalStateException"
                value = "no fix at 55.751244, 37.617300 for token=$token"
            },
        )
        breadcrumbs = listOf(
            Breadcrumb().apply {
                category = "screen"
                message = "lobby"
                setData("code", "ABC234")
            },
            Breadcrumb().apply {
                category = "ui.click"
                message = "Button Anna"
            },
            Breadcrumb().apply {
                category = "screen"
                message = "Lobby of Anna"
            },
            Breadcrumb("typed text"),
        )
        contexts.setDevice(
            Device().apply {
                id = "install-id"
                name = "Anna's Pixel"
                model = "Pixel 8"
            },
        )
        contexts.put("custom", mapOf("who" to "Anna"))
    }

    @Test
    fun anEventKeepsWhatWentWrongAndOnWhatPhone() {
        val event = SentryEventScrubber.scrub(dirtyEvent())

        assertEquals("java.lang.IllegalStateException", event.exceptions!!.single().type)
        assertEquals("no fix at [coord], [coord] for token=[redacted]", event.exceptions!!.single().value)
        assertEquals("app.hovanki.android.MainActivity", event.transaction)
        assertEquals("Pixel 8", event.contexts.device!!.model)
        assertEquals("sync failed for [email]", event.message!!.message)
        assertEquals("sync failed for [email] with Bearer [token]", event.message!!.formatted)
    }

    @Test
    fun anEventLosesWhoAndWhere() {
        val event = SentryEventScrubber.scrub(dirtyEvent())

        assertNull(event.user)
        assertNull(event.request)
        assertNull(event.extras)
        assertNull(event.tags)
        assertNull(event.serverName)
        assertNull(event.message!!.params)
        assertNull(event.contexts.device!!.id)
        assertNull(event.contexts.device!!.name)
        assertNull(event.contexts.get("custom"))
    }

    @Test
    fun onlyTheAppsOwnScreensStayAsBreadcrumbs() {
        val crumbs = SentryEventScrubber.scrub(dirtyEvent()).breadcrumbs!!

        assertEquals(listOf("lobby"), crumbs.map { it.message })
        assertEquals("screen", crumbs.single().category)
        assertTrue(crumbs.single().data.isEmpty())
    }

    @Test
    fun anEventWithNothingInItStaysEmpty() {
        val event = SentryEventScrubber.scrub(SentryEvent())
        assertNull(event.message)
        assertNull(event.exceptions)
        assertNull(event.breadcrumbs)
    }
}
