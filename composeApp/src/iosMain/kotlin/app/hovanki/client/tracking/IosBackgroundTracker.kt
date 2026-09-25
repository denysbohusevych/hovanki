package app.hovanki.client.tracking

/**
 * Nothing to do on iOS: CLLocationManager with `allowsBackgroundLocationUpdates` (UIBackgroundModes = location)
 * keeps the app running in the background while location updates are active.
 */
class IosBackgroundTracker : BackgroundTracker {
    override fun start() = Unit

    override fun stop() = Unit
}
