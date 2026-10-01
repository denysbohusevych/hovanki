package app.hovanki.client.errors

/**
 * Where a caught error goes beyond the log (docs/adr/0018-field-test-build.md §7). The field test build (`preview`)
 * reports to Sentry; every other build has [NoopErrorReporter]. This module has no platform code and no Sentry: the
 * app binds the real one (`CrashReporter` in `:composeApp`).
 *
 * Only a class and a place should ever be worth sending; whatever the exception's text holds is scrubbed on its way
 * out (`SentryScrubber` in `:shared`), but never write a nickname, a chat message or a position into one.
 */
fun interface ErrorReporter {
    /**
     * Reports [t] and returns the id the report got (Sentry's event id), so the field journal's `err` event can carry
     * it. `null`: nothing was sent (this build does not report, or the reporter is off).
     */
    fun capture(t: Throwable): String?
}

/** Reports nothing: every build but the field test one. */
object NoopErrorReporter : ErrorReporter {
    override fun capture(t: Throwable): String? = null
}
