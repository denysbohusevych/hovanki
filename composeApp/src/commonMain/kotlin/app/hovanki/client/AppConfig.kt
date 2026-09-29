package app.hovanki.client

/**
 * The one server the app talks to: the `hovanki.serverUrl` Gradle property baked in at build time (https only; the
 * build fails without it). Release, preview and TestFlight builds, and a debug build on a real phone: a debug build
 * installed on a phone plays on the same server as everybody else, with its diagnostics on top (docs/ci-cd.md). A debug
 * build on an emulator or a simulator: the development machine, where a local server runs. A debug build's launch
 * parameter may point it elsewhere (UI automation, a tunnel; [app.hovanki.client.automation.LaunchOptions.serverUrl]).
 */
fun defaultServerUrl(build: BuildInfo): String =
    if (build.isDebug && build.isEmulator) developmentServerUrl() else BuildConstants.SERVER_URL

/** The development machine as seen from the Android emulator or the iOS simulator. */
expect fun developmentServerUrl(): String
