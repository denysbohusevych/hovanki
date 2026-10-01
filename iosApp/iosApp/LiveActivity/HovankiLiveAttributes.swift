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
        /// The radar's band: 0 none, 1 warm, 2 hot, 3 burning (the card's colour).
        var band: Int = 0
        /// A second line: the role and the techniques of the step.
        var detail: String = ""
        /// The step's end, for a countdown the widget runs itself; nil: none.
        var endsAt: Date? = nil
    }

    /// Fixed for the activity's life.
    var title: String
}
