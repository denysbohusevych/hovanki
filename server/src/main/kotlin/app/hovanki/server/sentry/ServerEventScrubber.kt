package app.hovanki.server.sentry

import app.hovanki.shared.crash.SentryScrubber
import io.sentry.Breadcrumb
import io.sentry.SentryEvent

/**
 * What of a Sentry event leaves the staging server (docs/adr/0018-field-test-build.md §7), as `beforeSend` and
 * `beforeBreadcrumb`: the same filter as the phones' ([SentryScrubber]), and stricter where the server knows more.
 *
 * An event keeps what went wrong (exception types, messages scrubbed and cut short, stack frames) and where (the SDK's
 * OS, runtime and Spring contexts). The user, the request (URL with a game's code, headers, body), extras, tags and the
 * host name are dropped, and so are all breadcrumbs: log lines are free text. The text of a database, mail or SQL
 * exception goes whole, as it quotes the row it did not like (`Key (nickname)=(Anna) already exists`).
 */
object ServerEventScrubber {
    /** The SDK's own contexts; the response's carries headers and sizes. */
    private val keptContexts = setOf("os", "runtime", "spring", "app", "device", "trace")

    /** Packages whose exceptions quote data in their messages. */
    private val dataQuoting = listOf(
        "org.postgresql.",
        "java.sql.",
        "org.springframework.dao.",
        "org.springframework.jdbc.",
        "org.flywaydb.",
        "org.springframework.mail.",
        "jakarta.mail.",
    )

    fun scrub(event: SentryEvent): SentryEvent {
        event.user = null
        event.request = null
        event.extras = null
        event.tags = null
        event.serverName = null
        event.transaction = SentryScrubber.textOrNull(event.transaction)
        event.logger = SentryScrubber.textOrNull(event.logger)
        event.breadcrumbs = null
        event.message?.let { message ->
            message.message = SentryScrubber.textOrNull(message.message)
            message.formatted = SentryScrubber.textOrNull(message.formatted)
            message.params = null
        }
        event.exceptions?.forEach { exception ->
            val module = exception.module.orEmpty()
            val type = exception.type.orEmpty()
            // The type is the full class name when the SDK did not find the class's package.
            val className = if (module.isEmpty()) type else "$module.$type"
            exception.value = if (dataQuoting.any { className.startsWith(it) }) {
                null
            } else {
                SentryScrubber.textOrNull(exception.value)
            }
        }
        event.contexts.keys().toList().filterNot { it in keptContexts }.forEach { event.contexts.remove(it) }
        return event
    }

    /** No breadcrumbs at all: they are the log lines and the requests before the error. */
    @Suppress("UNUSED_PARAMETER")
    fun scrubBreadcrumb(breadcrumb: Breadcrumb): Breadcrumb? = null
}
