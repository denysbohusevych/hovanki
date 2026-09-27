package app.hovanki.client.session

import app.hovanki.client.location.LocationProvider
import app.hovanki.client.tracking.AlertKind
import app.hovanki.client.tracking.BackgroundTracker
import app.hovanki.client.tracking.HiderAlert
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

    /** Every alert vibrated for, in order, and the kinds that ended. */
    val alerts = mutableListOf<HiderAlert>()
    val endedAlerts = mutableListOf<AlertKind>()

    override fun start() {
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun alert(alert: HiderAlert) {
        alerts += alert
    }

    override fun endAlert(kind: AlertKind) {
        endedAlerts += kind
    }
}
