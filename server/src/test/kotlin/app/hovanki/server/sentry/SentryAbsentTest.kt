package app.hovanki.server.sentry

import io.sentry.IScopes
import io.sentry.Sentry
import io.sentry.SentryOptions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Production sets no `sentry.dsn`: the server starts as before, with no Sentry client and none of its beans. */
@SpringBootTest
class SentryAbsentTest(@Autowired private val context: ApplicationContext) {
    @Test
    fun noSentryWithoutADsn() {
        assertFalse(Sentry.isEnabled())
        assertTrue(context.getBeansOfType(SentryOptions::class.java).isEmpty())
        assertTrue(context.getBeansOfType(IScopes::class.java).isEmpty())
        assertTrue(context.getBeansOfType(SentryConfig::class.java).isEmpty())
        assertTrue(context.getBeansOfType(SentryOptions.BeforeSendCallback::class.java).isEmpty())
    }
}
