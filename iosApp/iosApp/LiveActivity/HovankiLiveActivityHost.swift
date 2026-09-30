import ActivityKit
import ComposeApp
import Foundation

/// The Swift side of `LiveActivityHost` (`:device`) for the radio lab's `mode.live_activity` (docs/radar-run.md §5.3):
/// Kotlin can't call ActivityKit, so it calls this through the protocol `LiveActivityBridgeHost` of `ComposeApp`.
/// App target only. `ContentView` finds it by its Objective-C name, so the app builds without this file too.
///
/// The app's deployment target is iOS 16.0; `Activity.request(attributes:content:pushType:)` is 16.2, so every call
/// checks the version, and the activity is kept as `Any` (a stored property can't be marked `@available`).
/// Kotlin calls it on the main thread.
@objc(HovankiLiveActivityHost)
final class HovankiLiveActivityHost: NSObject, LiveActivityBridgeHost {
    /// The running `Activity<HovankiLiveAttributes>`, or nil.
    private var current: Any?

    @objc override init() {
        super.init()
    }

    func start(title: String, text: String) -> Bool {
        guard #available(iOS 16.2, *) else { return false }
        // Off in Settings → Hovanki → Live Activities, or for the whole phone.
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            NSLog("HovankiLive: Live Activities are off")
            return false
        }
        // One at a time: whatever an earlier start (or an earlier run of the app) left on the lock screen goes.
        endAll()
        let content = ActivityContent(
            state: HovankiLiveAttributes.ContentState(text: text, updatedAt: Date()),
            staleDate: nil
        )
        do {
            current = try Activity.request(
                attributes: HovankiLiveAttributes(title: title),
                content: content,
                pushType: nil
            )
            return true
        } catch {
            // E.g. the app is no longer on the screen (ActivityAuthorizationError.visibility).
            NSLog("HovankiLive: request failed: \(error)")
            return false
        }
    }

    func update(text: String) {
        guard #available(iOS 16.2, *), let activity = current as? Activity<HovankiLiveAttributes> else { return }
        let content = ActivityContent(
            state: HovankiLiveAttributes.ContentState(text: text, updatedAt: Date()),
            staleDate: nil
        )
        Task {
            await activity.update(content)
        }
    }

    func end() {
        guard #available(iOS 16.2, *) else { return }
        endAll()
    }

    @available(iOS 16.2, *)
    private func endAll() {
        current = nil
        for activity in Activity<HovankiLiveAttributes>.activities {
            Task {
                await activity.end(nil, dismissalPolicy: .immediate)
            }
        }
    }
}
