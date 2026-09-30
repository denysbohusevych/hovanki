package app.hovanki.client.lab

import app.hovanki.device.LiveActivityHost

/**
 * What the Swift side implements with ActivityKit, which Kotlin/Native can't see (docs/radar-run.md §5.3): the class
 * `HovankiLiveActivityHost` in `iosApp/iosApp/LiveActivity/`. The app installs it at start through
 * [installLiveActivityHost], looked up by name so that the app builds and runs without it. Called on the main thread.
 */
interface LiveActivityBridgeHost {
    /** Starts the activity; false when iOS refuses (Live Activities off, the app not on the screen, iOS < 16.2). */
    fun start(title: String, text: String): Boolean

    fun update(text: String, band: Int, detail: String, endsAtMillis: Long)

    /** An update with an alert; [sound]: a sound file's name (the app's bundle or `Library/Sounds`), null: the default. */
    fun alert(title: String, text: String, sound: String?): Boolean

    fun end()
}

/** The Swift host, once the app has installed one. */
object LiveActivityBridge {
    var host: LiveActivityBridgeHost? = null
}

/** Swift: `LiveActivityBridgeKt.installLiveActivityHost(host:)`, before `mainViewController()`. */
fun installLiveActivityHost(host: LiveActivityBridgeHost) {
    LiveActivityBridge.host = host
}

/**
 * `:device`'s [LiveActivityHost] over the Swift host: unavailable while none is installed (the owner hasn't added the
 * widget extension `HovankiLive` and its files in Xcode yet), and then `mode.live_activity` says so in the lab's log.
 */
class BridgedLiveActivityHost : LiveActivityHost {
    override val isAvailable: Boolean get() = LiveActivityBridge.host != null

    override fun start(title: String, text: String): Boolean = LiveActivityBridge.host?.start(title, text) ?: false

    override fun update(text: String, band: Int, detail: String, endsAtMillis: Long) {
        LiveActivityBridge.host?.update(text, band, detail, endsAtMillis)
    }

    override fun alert(title: String, text: String, silent: Boolean): Boolean =
        LiveActivityBridge.host?.alert(title, text, if (silent) SILENT_SOUND else null) ?: false

    override fun end() {
        LiveActivityBridge.host?.end()
    }

    private companion object {
        /** The lab's half second of silence, written by `IosLabHaptics` into `Library/Sounds`. */
        const val SILENT_SOUND = "hovanki-silent.wav"
    }
}
