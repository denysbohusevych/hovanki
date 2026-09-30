@file:OptIn(ExperimentalForeignApi::class)

package app.hovanki.radar.host

import app.hovanki.radar.AdPart
import app.hovanki.radar.AdPlan
import app.hovanki.radar.BleUuid
import app.hovanki.radar.RadarChannel
import app.hovanki.radar.RadarRole
import app.hovanki.radar.RadarTrace
import app.hovanki.shared.protocol.Platform
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBUUID
import platform.CoreLocation.CLBeaconRegion
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUUID
import platform.Foundation.allKeys
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.darwin.NSObject

/**
 * Advertises the [channels]' parts for the latest token in [role] ([AdPlan] for iOS) for as long as it is set,
 * whatever the adapter does meanwhile. iOS takes `startAdvertising` only once the peripheral manager says it is
 * powered on, which comes a moment after it is made (and again after Bluetooth was switched off and on): a call before
 * that is dropped without a word, so the token waits here and goes out from [peripheralManagerDidUpdateState].
 *
 * Since iOS 14 an app in the background can neither start an advertisement nor change it: a restart there (the token's
 * five-minute slot changing while the phone is locked) would stop the one on the air and start nothing, and the phone
 * would be gone from the radar until it is unlocked. So in the background the advertisement on the air stays
 * (`skipped_background`); the new token waits for the app to come back ([UIApplicationDidBecomeActiveNotification]).
 * In the background iOS sends neither the name nor the iBeacon frame anyway, only the service UUIDs as bits of the
 * overflow area (docs/adr/0016-iphone-overflow-radar.md), so an old token there costs nothing.
 *
 * The delegate of its manager, which holds it only weakly: whoever runs it keeps a reference until [close].
 */
internal class IosAdvertiser(
    private val channels: List<RadarChannel>,
    private val role: RadarRole,
    private val trace: RadarTrace,
) : NSObject(),
    CBPeripheralManagerDelegateProtocol {
    /** The advertisement asked for [token]; an empty [plan] (everything dropped) puts nothing on the air. */
    private class Advertised(val token: String, val plan: AdPlan)

    private val manager = CBPeripheralManager()
    private var wanted: String? = null

    /** What is on the air; null: nothing. */
    private var advertised: Advertised? = null

    private val becameActive = NSNotificationCenter.defaultCenter.addObserverForName(
        UIApplicationDidBecomeActiveNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ -> restart() }

    init {
        manager.delegate = this
    }

    fun advertise(token: String?) {
        wanted = token
        restart()
    }

    fun close() {
        NSNotificationCenter.defaultCenter.removeObserver(becameActive)
        wanted = null
        restart()
        manager.delegate = null
    }

    override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
        // Bluetooth off (or not yet on) ends any advertisement: nothing is on the air until it is started again.
        if (peripheral.state != CBManagerStatePoweredOn) advertised = null
        restart()
    }

    override fun peripheralManagerDidStartAdvertising(peripheral: CBPeripheralManager, error: NSError?) {
        val current = advertised ?: return
        if (error != null) current.plan.trace(trace, "failed", current.token, error.localizedDescription)
    }

    private fun restart() {
        if (manager.state != CBManagerStatePoweredOn) return
        val token = wanted
        val current = advertised
        if (token == current?.token) return
        // Stopping works anywhere; a new token waits for the screen while one is on the air (see above).
        if (token != null && current != null && !current.plan.isEmpty && inBackground()) {
            planFor(token).trace(trace, "skipped_background", token)
            return
        }
        manager.stopAdvertising()
        if (current != null && !current.plan.isEmpty) current.plan.trace(trace, "stop", current.token)
        advertised = null
        if (token == null) return
        val plan = planFor(token)
        if (plan.isEmpty) {
            plan.traceDropped(trace, token)
        } else {
            manager.startAdvertising(advertisementOf(plan))
            plan.trace(trace, "start", token)
        }
        advertised = Advertised(token, plan)
    }

    private fun planFor(token: String): AdPlan = AdPlan.of(channels, token, role, Platform.IOS)
}

/**
 * The dictionary for `startAdvertising`: every service UUID (the overflow table's too: iOS moves what doesn't fit, and
 * everything in the background, into the overflow area itself), the local name, and an iBeacon frame as
 * `CLBeaconRegion.peripheralDataWithMeasuredPower` gives it ([AdPlan] for iOS leaves it alone).
 */
private fun advertisementOf(plan: AdPlan): Map<Any?, *> {
    val parts = plan.adParts
    val data = mutableMapOf<Any?, Any?>()
    parts.filterIsInstance<AdPart.IBeacon>().firstOrNull()?.let { data += beaconData(it) }
    val uuids = parts.filterIsInstance<AdPart.ServiceUuid>().map { CBUUID.UUIDWithString(BleUuid.normalize(it.uuid)) }
    if (uuids.isNotEmpty()) data[CBAdvertisementDataServiceUUIDsKey] = uuids
    parts.filterIsInstance<AdPart.LocalName>().firstOrNull()?.let { data[CBAdvertisementDataLocalNameKey] = it.name }
    return data
}

private fun beaconData(beacon: AdPart.IBeacon): Map<Any?, Any?> {
    val region = CLBeaconRegion(
        uUID = NSUUID(beacon.uuid),
        major = beacon.major.toUShort(),
        minor = beacon.minor.toUShort(),
        identifier = BEACON_REGION_ID,
    )
    val dictionary = region.peripheralDataWithMeasuredPower(NSNumber(int = beacon.measuredPower))
    return dictionary.allKeys.associateWith { dictionary.objectForKey(it) }
}
