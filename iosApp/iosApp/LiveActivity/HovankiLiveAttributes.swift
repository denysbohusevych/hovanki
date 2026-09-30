import ActivityKit
import Foundation

/// The radio lab's Live Activity (docs/radar-run.md §5.3): what the app starts and the widget extension draws.
/// In BOTH targets (the app `iosApp` and the extension `HovankiLive`): ActivityKit matches them by this type.
/// Imports nothing of `ComposeApp`: the extension doesn't link the Kotlin framework.
@available(iOS 16.1, *)
struct HovankiLiveAttributes: ActivityAttributes {
    /// What changes while the activity runs.
    struct ContentState: Codable, Hashable {
        var text: String
        var updatedAt: Date
    }

    /// Fixed for the activity's life.
    var title: String
}
