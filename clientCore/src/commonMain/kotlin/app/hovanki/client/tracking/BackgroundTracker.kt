package app.hovanki.client.tracking

/**
 * Keeps the app allowed to receive location updates while it is in the background during a round.
 * Location itself is collected by [app.hovanki.client.session.GameSessionManager]; this only tells the OS
 * that the app is doing user-visible work.
 */
interface BackgroundTracker {
    fun start()

    fun stop()
}
