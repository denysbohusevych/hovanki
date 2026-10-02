package app.hovanki.android

import android.app.Application

/**
 * No crash reporting in debug and release builds (docs/adr/0018-field-test-build.md §7): the Sentry SDK is a dependency
 * of the `preview` build type only, whose twin of this function (`src/preview`) starts it.
 */
@Suppress("UNUSED_PARAMETER")
internal fun installCrashReporting(app: Application) = Unit
