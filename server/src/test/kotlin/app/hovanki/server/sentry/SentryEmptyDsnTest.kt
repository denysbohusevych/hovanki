package app.hovanki.server.sentry

import io.sentry.Sentry
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * A deployment that passes `SENTRY_DSN` through blank (compose's `${SENTRY_DSN:-}`): the property is there but empty.
 * The starter then starts, and the SDK stays off, as Sentry documents an empty DSN.
 */
@SpringBootTest(properties = ["sentry.dsn="])
class SentryEmptyDsnTest {
    @Test
    fun aBlankDsnStartsTheServerWithSentryOff() {
        assertFalse(Sentry.isEnabled())
    }
}
