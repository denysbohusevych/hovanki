package app.hovanki.android

import app.hovanki.client.crash.CrashReporting
import io.sentry.Breadcrumb
import io.sentry.SentryEvent

/**
 * What of a Sentry event leaves the phone (docs/adr/0018-field-test-build.md §7), as `beforeSend` and
 * `beforeBreadcrumb`. Only plain JVM Sentry types, so it runs in unit tests.
 *
 * Anything a pattern can not find (a nickname, a chat message) must not be there to begin with, so the event keeps the
 * bare minimum: what went wrong (exception types, messages scrubbed and cut short, stack frames), on what device
 * (the SDK's device, OS and app contexts) and the trail of screens. The user, the request, extras, tags, the host name
 * and the install id are dropped, and so is every breadcrumb the app did not add itself.
 */
internal object SentryEventScrubber {
    /** The SDK's own contexts that describe the phone and the build; custom ones may carry anything. */
    private val keptContexts = setOf("app", "device", "os", "runtime", "gpu", "culture", "trace")

    fun scrub(event: SentryEvent): SentryEvent {
        event.user = null
        event.request = null
        event.extras = null
        event.tags = null
        event.serverName = null
        event.transaction = CrashReporting.scrubOrNull(event.transaction)
        event.logger = CrashReporting.scrubOrNull(event.logger)
        event.breadcrumbs = event.breadcrumbs?.mapNotNull(::scrubBreadcrumb)
        event.message?.let { message ->
            message.message = CrashReporting.scrubOrNull(message.message)
            message.formatted = CrashReporting.scrubOrNull(message.formatted)
            message.params = null
        }
        event.exceptions?.forEach { it.value = CrashReporting.scrubOrNull(it.value) }
        // The device context's id is the install id, the same one as the user's.
        event.contexts.device?.let {
            it.id = null
            it.name = null
        }
        event.contexts.keys().toList().filterNot { it in keptContexts }.forEach { event.contexts.remove(it) }
        return event
    }

    /** The screens the app adds are kept as a fresh breadcrumb with nothing but the name; anything else is dropped. */
    fun scrubBreadcrumb(breadcrumb: Breadcrumb): Breadcrumb? {
        if (breadcrumb.category != CrashReporting.screenCategory) return null
        val name = CrashReporting.screenName(breadcrumb.message ?: return null) ?: return null
        return Breadcrumb(breadcrumb.timestamp).also {
            it.category = CrashReporting.screenCategory
            it.type = "navigation"
            it.level = breadcrumb.level
            it.message = name
        }
    }
}
