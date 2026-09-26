package app.hovanki.client

/**
 * Server the app talks to until the player enters another address on the start screen.
 * Debug builds: the development machine as the emulator or simulator sees it. Other builds (preview, TestFlight,
 * release): the `hovanki.serverUrl` Gradle property baked in at build time; while it is empty, players type the
 * address, e.g. a tunnel to the development machine (docs/ci-cd.md).
 */
fun defaultServerUrl(build: BuildInfo): String =
    if (build.isDebug) developmentServerUrl() else BuildConstants.SERVER_URL

/** The development machine as seen from the Android emulator or the iOS simulator. */
expect fun developmentServerUrl(): String
