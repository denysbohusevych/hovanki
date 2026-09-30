import ActivityKit
import SwiftUI
import WidgetKit

/// How the radio lab's Live Activity looks (docs/radar-run.md §5.3): the step the run is on, the radar's band as a
/// colour and a word, the role and techniques of the step, and a countdown to the step's end that the widget runs
/// itself (no update needed for it). The app sends an update only when something changed: iOS budgets them.
struct HovankiLiveWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: HovankiLiveAttributes.self) { context in
            // The lock screen and the banner.
            HStack(alignment: .center, spacing: 12) {
                BandDot(band: context.state.band, size: 28)
                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        Text(context.attributes.title)
                            .font(.headline)
                        Spacer()
                        if let endsAt = context.state.endsAt, endsAt > Date() {
                            Text(timerInterval: Date()...endsAt, countsDown: true)
                                .font(.headline.monospacedDigit())
                                .frame(maxWidth: 64, alignment: .trailing)
                        }
                    }
                    Text(context.state.text)
                        .font(.subheadline)
                        .lineLimit(2)
                    HStack {
                        Text(bandWord(context.state.band))
                            .font(.caption.weight(.semibold))
                            .foregroundColor(bandColor(context.state.band))
                        if !context.state.detail.isEmpty {
                            Text("· \(context.state.detail)")
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .lineLimit(1)
                        }
                        Spacer()
                        Text(context.state.updatedAt, style: .time)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }
            .padding()
            .activityBackgroundTint(bandColor(context.state.band).opacity(0.18))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    HStack(spacing: 8) {
                        BandDot(band: context.state.band, size: 20)
                        Text(context.attributes.title)
                            .font(.headline)
                    }
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if let endsAt = context.state.endsAt, endsAt > Date() {
                        Text(timerInterval: Date()...endsAt, countsDown: true)
                            .font(.headline.monospacedDigit())
                            .frame(maxWidth: 64, alignment: .trailing)
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(context.state.text)
                            .font(.subheadline)
                        Text("\(bandWord(context.state.band)) \(context.state.detail)")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            } compactLeading: {
                BandDot(band: context.state.band, size: 14)
            } compactTrailing: {
                Text(bandWord(context.state.band))
                    .font(.caption2)
                    .foregroundColor(bandColor(context.state.band))
            } minimal: {
                BandDot(band: context.state.band, size: 14)
            }
        }
    }
}

/// The band as a colour: grey none, yellow warm, orange hot, red burning (docs/design.md, the radar's colours).
func bandColor(_ band: Int) -> Color {
    switch band {
    case 1: return .yellow
    case 2: return .orange
    case 3: return .red
    default: return .gray
    }
}

func bandWord(_ band: Int) -> String {
    switch band {
    case 1: return "warm"
    case 2: return "hot"
    case 3: return "burning"
    default: return "cold"
    }
}

/// A filled circle in the band's colour with a soft ring, the radar's glow in small.
struct BandDot: View {
    let band: Int
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle()
                .fill(bandColor(band).opacity(0.25))
                .frame(width: size * 1.6, height: size * 1.6)
            Circle()
                .fill(bandColor(band))
                .frame(width: size, height: size)
        }
        .frame(width: size * 1.6, height: size * 1.6)
    }
}
