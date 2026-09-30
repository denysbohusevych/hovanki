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

    /** Ends the activity and takes it off the lock screen at once. */
    fun end()
}

/** No Live Activities: Android, the JVM bots, and an iOS build without the widget extension. */
class NoopLiveActivityHost : LiveActivityHost {
    override val isAvailable: Boolean = false

    override fun start(title: String, text: String): Boolean = false

    override fun update(text: String) = Unit

    override fun end() = Unit
}
