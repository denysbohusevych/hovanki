package app.hovanki.client

import androidx.compose.ui.window.ComposeUIViewController
import app.hovanki.client.di.initKoin
import app.hovanki.client.di.onAppStart
import app.hovanki.client.ui.common.IosSafeArea
import platform.UIKit.UIViewController

/** iOS entry point. Swift calls it as `MainViewControllerKt.mainViewController()` (framework `ComposeApp`). */
fun mainViewController(): UIViewController {
    initKoin()
    onAppStart(readLaunchOptions())
    // The safe area is measured on the window as well: Compose alone can lose it (see `appSafeDrawing`).
    return ComposeUIViewController { App() }.also(IosSafeArea::attach)
}
