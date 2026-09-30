import ActivityKit
import SwiftUI
import WidgetKit

/// How the radio lab's Live Activity looks: the title and the text the app sends, on the lock screen and in the
/// Dynamic Island. Minimal on purpose: the run measures whether the activity keeps Nearby Interaction ranging in the
/// background, not how it looks.
struct HovankiLiveWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: HovankiLiveAttributes.self) { context in
            // The lock screen and the banner.
            VStack(alignment: .leading, spacing: 4) {
                Text(context.attributes.title)
                    .font(.headline)
                Text(context.state.text)
                    .font(.subheadline)
                Text(context.state.updatedAt, style: .time)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Text(context.attributes.title)
                        .font(.headline)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text(context.state.text)
                        .font(.subheadline)
                }
            } compactLeading: {
                Image(systemName: "dot.radiowaves.left.and.right")
            } compactTrailing: {
                Text("lab")
                    .font(.caption2)
            } minimal: {
                Image(systemName: "dot.radiowaves.left.and.right")
            }
        }
    }
}
