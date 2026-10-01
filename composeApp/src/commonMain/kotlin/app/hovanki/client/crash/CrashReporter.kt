package app.hovanki.client.crash

import app.hovanki.client.BuildConstants
import app.hovanki.client.errors.ErrorReporter
import app.hovanki.shared.crash.SentryScrubber
import kotlin.concurrent.Volatile

/**
 * Crash reports of the field test build (docs/adr/0018-field-test-build.md §7): Sentry, in the `preview` build on
 * Android and in the TestFlight build on iOS; every other build has [NoopCrashReporter]. Uncaught crashes the
 * platform's SDK reports on its own; this is for what the app catches and for the trail of screens before a crash.
 *
 * Self-contained on purpose (not an [ErrorReporter]): iOS implements it in Swift, and Swift sees the `ComposeApp`
 * framework's own types best. [asErrorReporter] hands the same reporter to `:clientCore`.
 */
interface CrashReporter {
    /** Reports [t] and returns the id the report got (Sentry's event id); `null`: nothing was sent. */
    fun capture(t: Throwable): String?

    /**
     * A screen was shown: the trail before a crash. [screen] is a [SentryScrubber.screenName], nothing else;
     * implementations keep it as a breadcrumb of category [SentryScrubber.SCREEN_CATEGORY] and nothing more.
     */
    fun breadcrumb(screen: String)
}

/** Reports nothing: every build but the field test one, and the field test one without a DSN. */
object NoopCrashReporter : CrashReporter {
    override fun capture(t: Throwable): String? = null

    override fun breadcrumb(screen: String) = Unit
}

/**
 * Whatever [CrashReporting] has installed at the moment of the call: what Koin binds, so that an entry point that
 * installs its reporter after Koin started (iOS: Swift, before the first screen) is still heard. Nothing while the
 * tester's consent isn't there ([CrashReporting.isAllowed]).
 */
internal object CurrentCrashReporter : CrashReporter {
    override fun capture(t: Throwable): String? =
        if (CrashReporting.isAllowed) CrashReporting.reporter.capture(t) else null

    override fun breadcrumb(screen: String) {
        if (CrashReporting.isAllowed) CrashReporting.reporter.breadcrumb(screen)
    }
}

/** This reporter as the seam of `:clientCore` (the field journal's `err` event carries the id [capture] returns). */
fun CrashReporter.asErrorReporter(): ErrorReporter = ErrorReporter { capture(it) }

/**
 * The one place the platform entry points meet the crash reporter. The entry point starts the SDK as early as it can
 * (before Koin, so a crash in start-up is reported) and installs its [CrashReporter] here, but only in the field test
 * build and only with a [dsn]; Koin binds [CurrentCrashReporter], which forwards to whatever is installed, or to
 * [NoopCrashReporter].
 *
 * iOS: Swift calls `CrashReporting.shared` (see docs/field-test.md, step 7). The scrubbing is here too, for the Swift
 * glue's `beforeSend` and `beforeBreadcrumb`: `:shared` is not part of the framework's API.
 */
object CrashReporting {
    /** Gradle property `hovanki.sentryDsn` of this build; empty: the build does not report. */
    val dsn: String get() = BuildConstants.SENTRY_DSN

    /** The installed reporter, [NoopCrashReporter] until [install]. */
    @Volatile
    var reporter: CrashReporter = NoopCrashReporter
        private set

    /**
     * Whether reports may go out: only while the tester's consent to the field test build stands
     * (docs/adr/0018-field-test-build.md §3.4, §7). False until the app knows ([allow]: at Koin's start from the stored
     * consent, then with every change of it), so a crash before that, or after the consent was taken back, is not
     * sent. The platform glue's `beforeSend` drops every event while it is false (the SDK's own crash reports too).
     */
    @Volatile
    var isAllowed: Boolean = false
        private set

    fun allow(allowed: Boolean) {
        isAllowed = allowed
    }

    /** Category of the breadcrumbs the app adds itself (the screens); the glue's `beforeBreadcrumb` keeps these only. */
    val screenCategory: String get() = SentryScrubber.SCREEN_CATEGORY

    fun install(reporter: CrashReporter) {
        this.reporter = reporter
    }

    /** [text] without tokens, email addresses and coordinates, cut short ([SentryScrubber.text]). */
    fun scrub(text: String): String = SentryScrubber.text(text)

    /** [scrub] for a text that may be missing. */
    fun scrubOrNull(text: String?): String? = SentryScrubber.textOrNull(text)

    /** [raw] if it is a screen's name, else `null` ([SentryScrubber.screenName]). */
    fun screenName(raw: String): String? = SentryScrubber.screenName(raw)
}
