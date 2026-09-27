package app.hovanki.client.tracking

/**
 * Keeps the app allowed to receive location updates while it is in the background during a round.
 * Location itself is collected by [app.hovanki.client.session.GameSessionManager]; this only tells the OS
 * that the app is doing user-visible work.
 */
interface BackgroundTracker {
    fun start()

    fun stop()

    /**
     * Time to vibrate for a hider's [alert] (when is decided by [AlertRepeats]). The platform shows it as a
     * notification that vibrates while the app is not on screen; on screen the round's own UI alerts.
     */
    fun alert(alert: HiderAlert) = Unit

    /** The alert of [kind] is over: its notification goes. */
    fun endAlert(kind: AlertKind) = Unit
}
