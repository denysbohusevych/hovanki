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

    /// An update that alerts: on the lock screen iOS shows it like a notification and plays `sound` — and the sound's
    /// haptic, which is the one vibration a locked iPhone gives an app whose Core Haptics engine is stopped. `sound` is
    /// a file's name in the app's bundle or in `Library/Sounds` (the lab's half second of silence: vibration only),
    /// nil the default sound. False without a running activity.
    func alert(title: String, text: String, sound: String?) -> Bool {
        guard #available(iOS 16.2, *), let activity = current as? Activity<HovankiLiveAttributes> else { return false }
        let content = ActivityContent(
            state: HovankiLiveAttributes.ContentState(text: text, updatedAt: Date()),
            staleDate: nil
        )
        let alertSound: AlertConfiguration.AlertSound
        if let sound, Self.soundExists(sound) {
            alertSound = .named(sound)
        } else {
            if let sound { NSLog("HovankiLive: sound \(sound) not found, the default plays") }
            alertSound = .default
        }
        let configuration = AlertConfiguration(
            title: LocalizedStringResource(stringLiteral: title),
            body: LocalizedStringResource(stringLiteral: text),
            sound: alertSound
        )
        Task {
            await activity.update(content, alertConfiguration: configuration)
        }
        return true
    }

    /// Where iOS looks for a named sound: the app's bundle, then `Library/Sounds`.
    private static func soundExists(_ name: String) -> Bool {
        if Bundle.main.url(forResource: name, withExtension: nil) != nil { return true }
        let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first
        guard let file = library?.appendingPathComponent("Sounds").appendingPathComponent(name) else { return false }
        return FileManager.default.fileExists(atPath: file.path)
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
