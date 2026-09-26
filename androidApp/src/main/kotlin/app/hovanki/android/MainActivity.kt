package app.hovanki.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.hovanki.client.App
import app.hovanki.client.di.offerLaunchOptions

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // UI automation hooks exist in debug builds only (src/debug); in release both calls do nothing.
        if (savedInstanceState == null) readLaunchOptions(intent)?.let(::offerLaunchOptions)
        setContent { AutomationRoot { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readLaunchOptions(intent)?.let(::offerLaunchOptions)
    }
}
