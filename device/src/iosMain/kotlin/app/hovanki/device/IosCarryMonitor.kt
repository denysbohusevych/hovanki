@file:OptIn(ExperimentalForeignApi::class)

package app.hovanki.device

import app.hovanki.shared.protocol.Activity
import app.hovanki.shared.protocol.Carry
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UIKit.UIDevice
import kotlin.math.sqrt

/**
 * Where the phone is (docs/adr/0012-nearby-radar.md, «Карман») on iOS: in the hand while the app is active; in the
 * pocket while the phone is locked (its protected data unavailable) and carried, by the accelerometer; unknown
 * otherwise. iOS gives an app neither the proximity sensor nor the light in the background, so the lock is the
 * screen-off gate here; except while the field build's round turned the screen off by the proximity sensor
 * (docs/adr/0018-field-test-build.md, wave 4): the app stays active in the pocket, the covered sensor is the gate
 * then ([IosCarryRules]). Told every [POLL_MILLIS]; nothing but the state leaves the phone.
 */
class IosCarryMonitor : CarryMonitor {
    override fun carry(): Flow<Carry> = callbackFlow {
        val motion = CMMotionManager()
        val classifier = ActivityClassifier()
        if (motion.accelerometerAvailable) {
            motion.accelerometerUpdateInterval = UPDATE_INTERVAL_SECONDS
            motion.startAccelerometerUpdatesToQueue(NSOperationQueue.mainQueue) { data, _ ->
                val reading = data ?: return@startAccelerometerUpdatesToQueue
                val magnitude = reading.acceleration.useContents { sqrt(x * x + y * y + z * z) } * GRAVITY
                classifier.add((reading.timestamp * 1000).toLong(), magnitude)
            }
        }
        val poll = launch {
            while (isActive) {
                val application = UIApplication.sharedApplication
                val carried = classifier.classify().let { it != Activity.STILL && it != Activity.UNKNOWN }
                val device = UIDevice.currentDevice
                val state = IosCarryRules.state(
                    active = application.applicationState == UIApplicationState.UIApplicationStateActive,
                    proximityOn = device.proximityMonitoringEnabled,
                    near = device.proximityMonitoringEnabled && device.proximityState,
                    protectedDataAvailable = application.protectedDataAvailable,
                    carried = carried,
                )
                trySend(state)
                delay(POLL_MILLIS)
            }
        }
        awaitClose {
            poll.cancel()
            motion.stopAccelerometerUpdates()
        }
    }.distinctUntilChanged()

    private companion object {
        const val UPDATE_INTERVAL_SECONDS = 0.1
        const val GRAVITY = 9.81
        const val POLL_MILLIS = 2_000L
    }
}
