@file:OptIn(ExperimentalForeignApi::class)

package app.hovanki.client.tracking

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
import kotlin.math.sqrt

/**
 * Where the phone is (docs/adr/0010-nearby-radar.md, «Карман») on iOS: in the hand while the app is active; in the
 * pocket while the phone is locked (its protected data unavailable) and carried, by the accelerometer; unknown
 * otherwise. iOS gives an app neither the proximity sensor nor the light in the background, so the lock is the
 * screen-off gate here. Told every [POLL_MILLIS]; nothing but the state leaves the phone.
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
                val state = when {
                    application.applicationState == UIApplicationState.UIApplicationStateActive -> Carry.IN_HAND
                    !application.protectedDataAvailable && carried -> Carry.IN_POCKET
                    else -> Carry.UNKNOWN
                }
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
