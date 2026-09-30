import SwiftUI
import WidgetKit

/// The widget extension `HovankiLive` (docs/radar-run.md §5.3): only the radio lab's Live Activity. The owner makes
/// the target in Xcode (iosApp/README.md, «Радиолаба: Live Activity и фоновые режимы») with a deployment target of
/// iOS 16.2 or later, so nothing here checks the version.
@main
struct HovankiLiveBundle: WidgetBundle {
    var body: some Widget {
        HovankiLiveWidget()
    }
}
