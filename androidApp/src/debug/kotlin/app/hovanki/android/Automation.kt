package app.hovanki.android

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import app.hovanki.client.automation.LaunchOptions

// Debug builds only (the release source set has no-op twins): hooks for UI automation, see docs/e2e.md.

/**
 * Start parameters from intent extras (`adb shell am start ... --es hovanki.server http://10.0.2.2:8080`,
 * Maestro `launchApp.arguments`) or from the deep link `hovanki://join?server=...&name=...&joinCode=...`.
 */
internal fun readLaunchOptions(intent: Intent?): LaunchOptions? {
    if (intent == null) return null
    val link = intent.data?.takeIf { it.scheme == DEEP_LINK_SCHEME }
    val extras = intent.extras
    return LaunchOptions.read { key -> link?.getQueryParameter(key) ?: extras?.valueOf(LaunchOptions.KEY_PREFIX + key) }
}

/** Extras may be strings or numbers: Maestro passes typed values. */
@Suppress("DEPRECATION")
private fun Bundle.valueOf(key: String): String? = get(key)?.toString()

/** Exposes Compose test tags as resource ids, so Maestro and UiAutomator find elements by id. */
@Composable
internal fun AutomationRoot(content: @Composable () -> Unit) {
    Box(modifier = Modifier.semantics { testTagsAsResourceId = true }) {
        content()
    }
}

private const val DEEP_LINK_SCHEME = "hovanki"
