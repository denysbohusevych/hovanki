@file:OptIn(ExperimentalForeignApi::class)

package app.hovanki.client.tracking

import app.hovanki.shared.protocol.Activity
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSOperationQueue
import kotlin.math.sqrt

/**
 * The accelerometer into an [ActivityClassifier] (docs/adr/0011-quests-sparks-and-sensors.md, section 4) through
 * CoreMotion, 50 readings a second while collected. In g: converted to m/s² for the classifier's thresholds.
 */
class IosActivityMonitor : ActivityMonitor {
    override fun activity(): Flow<Activity> = callbackFlow {
        val manager = CMMotionManager()
        if (!manager.accelerometerAvailable) {
            close()
            return@callbackFlow
        }
        val classifier = ActivityClassifier()
        manager.accelerometerUpdateInterval = UPDATE_INTERVAL_SECONDS
        manager.startAccelerometerUpdatesToQueue(NSOperationQueue.mainQueue) { data, _ ->
            val reading = data ?: return@startAccelerometerUpdatesToQueue
            val magnitude = reading.acceleration.useContents { sqrt(x * x + y * y + z * z) } * GRAVITY
            trySend(classifier.add((reading.timestamp * 1000).toLong(), magnitude))
        }
        awaitClose { manager.stopAccelerometerUpdates() }
    }.distinctUntilChanged()

    private companion object {
        const val UPDATE_INTERVAL_SECONDS = 0.02
        const val GRAVITY = 9.81
    }
}
