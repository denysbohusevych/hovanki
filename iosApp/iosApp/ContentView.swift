import ComposeApp
import SwiftUI
import UIKit

/// Hosts the Compose Multiplatform UI: the whole app lives in the `ComposeApp` Kotlin framework.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // Also initializes DI on the Kotlin side.
        MainViewControllerKt.mainViewController()
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
