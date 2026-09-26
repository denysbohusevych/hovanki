package app.hovanki.client

import androidx.compose.ui.window.ComposeUIViewController
import app.hovanki.client.di.initKoin
import app.hovanki.client.di.onAppStart
import platform.UIKit.UIViewController

/** iOS entry point. Swift calls it as `MainViewControllerKt.mainViewController()` (framework `ComposeApp`). */
fun mainViewController(): UIViewController {
    initKoin()
    onAppStart(readLaunchOptions())
    return ComposeUIViewController { App() }
}
