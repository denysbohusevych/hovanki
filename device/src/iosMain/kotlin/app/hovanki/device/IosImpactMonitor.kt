package app.hovanki.device

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSDate
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.timeIntervalSince1970
import kotlin.math.sqrt

/**
 * The accelerometer about a hundred times a second while collected (`CMMotionManager`, the app on the screen: iOS
 * gives no accelerometer to a suspended app), its lone jolts found by [ImpactDetector]. The reading's time (since
 * boot) is turned into the device's clock.
 */
@OptIn(ExperimentalForeignApi::class)
class IosImpactMonitor : ImpactMonitor {
    override fun impacts(): Flow<Impact> = callbackFlow {
        val motion = CMMotionManager()
        if (!motion.accelerometerAvailable) {
            close()
            return@callbackFlow
        }
        val detector = ImpactDetector()
        motion.accelerometerUpdateInterval = ImpactDetector.SAMPLING_MILLIS / 1000.0
        motion.startAccelerometerUpdatesToQueue(NSOperationQueue.mainQueue) { data, _ ->
            val reading = data ?: return@startAccelerometerUpdatesToQueue
            val magnitude = reading.acceleration.useContents { sqrt(x * x + y * y + z * z) }
            val sinceSeconds = (NSProcessInfo.processInfo.systemUptime - reading.timestamp).coerceAtLeast(0.0)
            val atMillis = ((NSDate().timeIntervalSince1970 - sinceSeconds) * 1000).toLong()
            detector.add(atMillis, magnitude)?.let { trySend(it) }
        }
        awaitClose { motion.stopAccelerometerUpdates() }
    }
}
