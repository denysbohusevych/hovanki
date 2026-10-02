@file:OptIn(ExperimentalNativeApi::class)

package app.hovanki.client

import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/** The iOS simulator shares the network with the host machine. */
actual fun developmentServerUrl(): String = "http://localhost:8080"

/** Version and build number from Info.plist (Config.xcconfig; CI sets the build number to the run number). */
internal fun iosBuildInfo(): BuildInfo {
    val info = NSBundle.mainBundle.infoDictionary
    return BuildInfo(
        version = info?.get("CFBundleShortVersionString") as? String ?: "",
        buildNumber = info?.get("CFBundleVersion") as? String ?: "",
        commit = BuildConstants.COMMIT,
        isDebug = Platform.isDebugBinary,
        // The simulator runs the app with its SIMULATOR_* environment; a real iPhone has none.
        isEmulator = NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null,
        // The same bundle id serves the field test build and the App Store one: the build's channel tells them apart.
        isPreview = BuildConstants.CHANNEL == BuildInfo.CHANNEL_PREVIEW,
    )
}
