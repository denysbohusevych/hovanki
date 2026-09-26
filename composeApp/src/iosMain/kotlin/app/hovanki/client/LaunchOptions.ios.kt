@file:OptIn(ExperimentalNativeApi::class)

package app.hovanki.client

import app.hovanki.client.automation.LaunchOptions
import platform.Foundation.NSUserDefaults
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/**
 * Debug binaries only: launch arguments such as `-hovanki.server http://localhost:8080` (`xcrun simctl launch`,
 * Xcode scheme, Maestro) land in the argument domain of the standard user defaults. Release builds ignore them.
 */
internal fun readLaunchOptions(): LaunchOptions? {
    if (!Platform.isDebugBinary) return null
    val defaults = NSUserDefaults.standardUserDefaults
    return LaunchOptions.read { key -> defaults.stringForKey(LaunchOptions.KEY_PREFIX + key) }
}
