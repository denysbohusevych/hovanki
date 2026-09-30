@file:OptIn(ExperimentalForeignApi::class)

package app.hovanki.device.lab

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoPowerStateDidChangeNotification
import platform.Foundation.lowPowerModeEnabled
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import platform.UIKit.UIApplicationProtectedDataDidBecomeAvailable
import platform.UIKit.UIApplicationProtectedDataWillBecomeUnavailable
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIApplicationWillTerminateNotification
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryLevelDidChangeNotification
import platform.UIKit.UIDeviceBatteryState
import platform.UIKit.UIDeviceBatteryStateDidChangeNotification
import platform.UIKit.UIDeviceProximityStateDidChangeNotification

/**
 * The radio lab's view of an iPhone (docs/radio-lab.md §4.1): the app's life (active, background, the protected data
 * when the phone locks with a passcode, low power), the motion by `CMDeviceMotion` (gravity apart), the proximity
 * sensor while it is on (only the lab turns it on, [IosLabScreen]) and the battery. iOS has no light sensor for apps.
 * Debug builds only; written without an iOS build at hand.
 */
class IosLabProbes : LabProbes {
    override val os: String = "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion}"

    override fun appState(): String = when (UIApplication.sharedApplication.applicationState) {
        UIApplicationState.UIApplicationStateActive -> "active"
        UIApplicationState.UIApplicationStateInactive -> "inactive"
        else -> "background"
    }

    override fun lifecycle(): Flow<String> = callbackFlow {
        val center = NSNotificationCenter.defaultCenter
        val events = mapOf(
            UIApplicationWillResignActiveNotification to "will_resign",
            UIApplicationDidEnterBackgroundNotification to "did_enter_background",
            UIApplicationWillEnterForegroundNotification to "will_enter_foreground",
            UIApplicationDidBecomeActiveNotification to "did_become_active",
            UIApplicationProtectedDataWillBecomeUnavailable to "protected_data_off",
            UIApplicationProtectedDataDidBecomeAvailable to "protected_data_on",
            UIApplicationDidReceiveMemoryWarningNotification to "memory_warning",
            UIApplicationWillTerminateNotification to "terminate",
        )
        val observers = events.map { (name, event) ->
            center.addObserverForName(name, `object` = null, queue = NSOperationQueue.mainQueue) { _ ->
                trySend(event)
            }
        }
        val power = center.addObserverForName(
            NSProcessInfoPowerStateDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            trySend(if (NSProcessInfo.processInfo.lowPowerModeEnabled) "low_power_on" else "low_power_off")
        }
        awaitClose {
            observers.forEach { center.removeObserver(it) }
            center.removeObserver(power)
        }
    }

    override fun sensors(): Flow<LabSensorReading> = callbackFlow {
        val motion = CMMotionManager()
        if (motion.deviceMotionAvailable) {
            motion.deviceMotionUpdateInterval = MOTION_INTERVAL_SECONDS
            motion.startDeviceMotionUpdatesToQueue(NSOperationQueue.mainQueue) { data, _ ->
                val reading = data ?: return@startDeviceMotionUpdatesToQueue
                // CMDeviceMotion's gravity already points down to the earth, in g: the lab's frame as it is.
                val gravity = reading.gravity.useContents { Gravity(x, y, z) }
                val magnitude = reading.userAcceleration.useContents {
                    MotionWindow.magnitude(x + gravity.x, y + gravity.y, z + gravity.z)
                }
                trySend(LabSensorReading.Motion((reading.timestamp * 1000).toLong(), magnitude, gravity))
            }
        }
        val device = UIDevice.currentDevice
        val proximity = NSNotificationCenter.defaultCenter.addObserverForName(
            UIDeviceProximityStateDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            trySend(LabSensorReading.Proximity(device.proximityState, monitoring = device.proximityMonitoringEnabled))
        }
        awaitClose {
            motion.stopDeviceMotionUpdates()
            NSNotificationCenter.defaultCenter.removeObserver(proximity)
        }
    }

    override fun battery(): Flow<LabBattery> = callbackFlow {
        val device = UIDevice.currentDevice
        device.batteryMonitoringEnabled = true
        fun read(): LabBattery {
            val level = device.batteryLevel.toDouble().takeIf { it >= 0 }
            val state = when (device.batteryState) {
                UIDeviceBatteryState.UIDeviceBatteryStateUnplugged -> "unplugged"
                UIDeviceBatteryState.UIDeviceBatteryStateCharging -> "charging"
                UIDeviceBatteryState.UIDeviceBatteryStateFull -> "full"
                else -> "unknown"
            }
            return LabBattery(level, state, NSProcessInfo.processInfo.lowPowerModeEnabled)
        }
        trySend(read())
        val center = NSNotificationCenter.defaultCenter
        val observers = listOf(
            UIDeviceBatteryLevelDidChangeNotification,
            UIDeviceBatteryStateDidChangeNotification,
            NSProcessInfoPowerStateDidChangeNotification,
        ).map { name ->
            center.addObserverForName(name, `object` = null, queue = NSOperationQueue.mainQueue) { _ ->
                trySend(read())
            }
        }
        val everyMinute = launch {
            while (true) {
                delay(MINUTE_MILLIS)
                trySend(read())
            }
        }
        awaitClose {
            everyMinute.cancel()
            observers.forEach { center.removeObserver(it) }
        }
    }

    private companion object {
        const val MOTION_INTERVAL_SECONDS = 0.1
        const val MINUTE_MILLIS = 60_000L
    }
}
