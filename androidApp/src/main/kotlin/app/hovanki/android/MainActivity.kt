package app.hovanki.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.hovanki.client.App
import app.hovanki.client.di.offerLaunchOptions
import app.hovanki.client.di.onAppStart

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // UI automation hooks exist in debug builds only (src/debug); in release both calls do nothing.
        // Also after the system recreated the activity in a new process: that one resumes the saved game too.
        onAppStart(if (savedInstanceState == null) readLaunchOptions(intent) else null)
        setContent { AutomationRoot { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readLaunchOptions(intent)?.let(::offerLaunchOptions)
    }
}
