package app.hovanki.client.session

import app.hovanki.client.location.LocationProvider
import app.hovanki.client.tracking.BackgroundTracker
import app.hovanki.shared.protocol.LocationSample
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

// The phone around GameSessionManager in tests: GPS that never reports and a background tracker that remembers.

class FakeLocationProvider : LocationProvider {
    var collectors = 0

    override fun hasPermission() = true

    override fun locationUpdates(intervalMillis: Long): Flow<LocationSample> = flow {
        collectors++
        try {
            awaitCancellation()
        } finally {
            collectors--
        }
    }
}

class FakeBackgroundTracker : BackgroundTracker {
    var running = false

    override fun start() {
        running = true
    }

    override fun stop() {
        running = false
    }
}
