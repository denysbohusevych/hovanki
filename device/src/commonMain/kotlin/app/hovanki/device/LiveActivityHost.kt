package app.hovanki.device

/**
 * The round's Live Activity (docs/adr/0017-radar-techniques-and-big-run.md, §2.3 `mode.live_activity`): a card on the
 * lock screen and in the Dynamic Island that also lets Nearby Interaction range in the background. ActivityKit is
 * Swift only, so Kotlin/Native can't call it: iOS implements this through a bridge the app installs at start, and
 * without the app's widget extension there is none ([isAvailable] false).
 */
interface LiveActivityHost {
    /** Whether the app has the Swift side (the host class and the widget extension `HovankiLive`). */
    val isAvailable: Boolean

    /**
     * Starts the activity with [title] and [text]; false when iOS refuses (Live Activities off in the settings, the app
     * not on the screen) or there is no host. At most one: a second start replaces the first.
     */
    fun start(title: String, text: String): Boolean

    /** A new [text] on the running activity; nothing when none runs. */
    fun update(text: String)

    /**
     * An update with an alert (`Activity.update(_:alertConfiguration:)`): iOS shows it on the lock screen like a
     * notification and plays its sound, and the sound's haptic is the one vibration a locked iPhone gives an app whose
     * Core Haptics engine is stopped (`pulse.live_activity`, docs/radar-run.md §5.1). [silent]: the lab's file of
     * silence (`Library/Sounds/hovanki-silent.wav`) instead of the default sound, so only the vibration is left. False
     * when no activity runs (start it first) or there is no host.
     */
    fun alert(title: String, text: String, silent: Boolean): Boolean

    /** Ends the activity and takes it off the lock screen at once. */
    fun end()
}

/** No Live Activities: Android, the JVM bots, and an iOS build without the widget extension. */
class NoopLiveActivityHost : LiveActivityHost {
    override val isAvailable: Boolean = false

    override fun start(title: String, text: String): Boolean = false

    override fun update(text: String) = Unit

    override fun alert(title: String, text: String, silent: Boolean): Boolean = false

    override fun end() = Unit
}
