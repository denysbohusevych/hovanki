package app.hovanki.android

import android.content.Intent
import androidx.compose.runtime.Composable
import app.hovanki.client.automation.LaunchOptions

// Release builds: no UI automation hooks (see the debug source set).

@Suppress("UNUSED_PARAMETER")
internal fun readLaunchOptions(intent: Intent?): LaunchOptions? = null

@Composable
internal fun AutomationRoot(content: @Composable () -> Unit) = content()
