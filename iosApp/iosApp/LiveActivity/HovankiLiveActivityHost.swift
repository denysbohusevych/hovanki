import ActivityKit
import ComposeApp
import Foundation

/// The Swift side of `LiveActivityHost` (`:device`) for the radio lab's `mode.live_activity` (docs/radar-run.md §5.3)
/// and the field build's round (`RoundLiveActivity`: the role, the radar's band, the hider's pulse by alerts):
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
    /// What the card shows now (the last start or update): an alert keeps it, only the alert's own text is new.
    private var text = ""
    private var band = 0
    private var detail = ""
    private var endsAt: Date?

    @objc override init() {
        super.init()
        // A card an earlier run of the app left on the lock screen (killed in a round, crashed): nothing drives it
        // any more, so it goes at the app's start.
        if #available(iOS 16.2, *) {
            endAll()
        }
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
        self.text = text
        band = 0
        detail = ""
        endsAt = nil
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

    func update(text: String, band: Int32, detail: String, endsAtMillis: Int64) {
        guard #available(iOS 16.2, *), let activity = running() else { return }
        self.text = text
        self.band = Int(band)
        self.detail = detail
        endsAt = endsAtMillis > 0 ? Date(timeIntervalSince1970: TimeInterval(endsAtMillis) / 1000) : nil
        let content = currentContent()
        Task {
            await activity.update(content)
        }
    }

    /// An update that alerts: on the lock screen iOS shows it like a notification and plays `sound` — and the sound's
    /// haptic, which is the one vibration a locked iPhone gives an app whose Core Haptics engine is stopped. `sound` is
    /// a file's name in the app's bundle or in `Library/Sounds` (the lab's half second of silence: vibration only),
    /// nil the default sound. False without a running, active activity. The card keeps what it shows (the role and the band
    /// of the field build's round, the lab's step): `title` and `text` are the alert's own.
    func alert(title: String, text: String, sound: String?) -> Bool {
        // A card the player swiped away, turned off in Settings or iOS ended: false, so Kotlin notifies instead.
        guard #available(iOS 16.2, *), let activity = running() else { return false }
        let content = currentContent()
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

    /// The running activity, nil when there is none or it is no longer active (the player swiped it away, turned Live
    /// Activities off, or iOS ended it); then it is forgotten.
    @available(iOS 16.2, *)
    private func running() -> Activity<HovankiLiveAttributes>? {
        guard let activity = current as? Activity<HovankiLiveAttributes> else { return nil }
        guard activity.activityState == .active else {
            NSLog("HovankiLive: the activity is no longer active")
            current = nil
            return nil
        }
        return activity
    }

    /// The card as it is now, stamped with this moment.
    @available(iOS 16.2, *)
    private func currentContent() -> ActivityContent<HovankiLiveAttributes.ContentState> {
        ActivityContent(
            state: HovankiLiveAttributes.ContentState(
                text: text, updatedAt: Date(), band: band, detail: detail, endsAt: endsAt
            ),
            staleDate: nil
        )
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
