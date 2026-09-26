package app.hovanki.client

/**
 * The one server the app talks to. Debug builds: the development machine as the emulator or simulator sees it (UI
 * automation may point them elsewhere, see [app.hovanki.client.automation.LaunchOptions.serverUrl]). Other builds
 * (preview, TestFlight, release): the `hovanki.serverUrl` Gradle property baked in at build time (https only; the
 * build fails without it).
 */
fun defaultServerUrl(build: BuildInfo): String =
    if (build.isDebug) developmentServerUrl() else BuildConstants.SERVER_URL

/** The development machine as seen from the Android emulator or the iOS simulator. */
expect fun developmentServerUrl(): String
