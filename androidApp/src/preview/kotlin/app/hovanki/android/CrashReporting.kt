package app.hovanki.android

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import app.hovanki.client.crash.CrashReporter
import app.hovanki.client.crash.CrashReporting
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.protocol.SentryId

/**
 * Crash reports of the field test build (docs/adr/0018-field-test-build.md §7). The Sentry SDK is a dependency of the
 * `preview` build type only; debug and release have a no-op twin of this function (`src/withoutSentry`) and no SDK.
 *
 * Starts the SDK when the build carries a DSN (`hovanki.sentryDsn`, CI's secret) and installs the reporter for Koin.
 * Called first in [HovankiApplication.onCreate], so a crash during start-up is reported too. The SDK's own start by
 * the manifest is off (`src/preview/AndroidManifest.xml`).
 */
internal fun installCrashReporting(app: Application) {
    val dsn = CrashReporting.dsn
    if (dsn.isBlank()) return
    SentryAndroid.init(app) { options -> configureSentry(options, dsn, release = releaseName(app)) }
    CrashReporting.install(SentryCrashReporter)
}

private fun configureSentry(options: SentryAndroidOptions, dsn: String, release: String) {
    options.dsn = dsn
    options.environment = "staging"
    options.release = release
    options.isDebug = false
    // No user, no IP address, no request data: nothing the SDK adds on its own about a person. The event goes through
    // SentryEventScrubber as well, which is the real filter.
    @Suppress("DEPRECATION")
    options.isSendDefaultPii = false
    options.isAttachScreenshot = false
    options.isAttachViewHierarchy = false
    // Only errors (docs/adr/0018-field-test-build.md §7): no sessions, no performance traces.
    options.isEnableAutoSessionTracking = false
    options.tracesSampleRate = null
    // The trail is the screens the app names itself; the SDK's own breadcrumbs (taps, lifecycle, network state) are off,
    // and beforeBreadcrumb drops whatever else gets in.
    options.isEnableUserInteractionBreadcrumbs = false
    options.isEnableActivityLifecycleBreadcrumbs = false
    options.isEnableAppLifecycleBreadcrumbs = false
    options.isEnableSystemEventBreadcrumbs = false
    options.isEnableAppComponentBreadcrumbs = false
    options.isEnableNetworkEventBreadcrumbs = false
    options.maxBreadcrumbs = MAX_BREADCRUMBS
    options.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> SentryEventScrubber.scrub(event) }
    options.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ ->
        SentryEventScrubber.scrubBreadcrumb(breadcrumb)
    }
}

/** `<version>+<build>`: the version name and the version code the CI run set. */
private fun releaseName(app: Application): String {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        app.packageManager.getPackageInfo(app.packageName, 0)
    }
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    return "${info.versionName.orEmpty()}+$code"
}

private const val MAX_BREADCRUMBS = 30

private object SentryCrashReporter : CrashReporter {
    override fun capture(t: Throwable): String? =
        Sentry.captureException(t).takeIf { it != SentryId.EMPTY_ID }?.toString()

    override fun breadcrumb(screen: String) {
        val name = CrashReporting.screenName(screen) ?: return
        Sentry.addBreadcrumb(
            Breadcrumb().also {
                it.category = CrashReporting.screenCategory
                it.type = "navigation"
                it.level = SentryLevel.INFO
                it.message = name
            },
        )
    }
}
