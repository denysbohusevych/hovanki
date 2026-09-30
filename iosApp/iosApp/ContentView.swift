import ComposeApp
import SwiftUI
import UIKit

/// Hosts the Compose Multiplatform UI: the whole app lives in the `ComposeApp` Kotlin framework.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        installLiveActivityHost()
        // Also initializes DI on the Kotlin side.
        return MainViewControllerKt.mainViewController()
    }

    /// The radio lab's Live Activity (docs/radar-run.md §5.3): ActivityKit is Swift only, so the Swift host is handed to
    /// Kotlin before it starts. Looked up by name: `LiveActivity/HovankiLiveActivityHost.swift` is added to this target
    /// by hand in Xcode (with the widget extension `HovankiLive`), and without it the app builds and runs, only
    /// `mode.live_activity` is unavailable.
    private func installLiveActivityHost() {
        guard let type = NSClassFromString("HovankiLiveActivityHost") as? NSObject.Type,
              let host = type.init() as? LiveActivityBridgeHost
        else { return }
        LiveActivityBridgeKt.installLiveActivityHost(host: host)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        // Compose draws edge to edge and handles safe-area and keyboard insets itself.
        ComposeView()
            .ignoresSafeArea()
    }
}
