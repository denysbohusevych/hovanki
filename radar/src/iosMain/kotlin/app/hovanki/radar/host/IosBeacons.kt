@file:OptIn(ExperimentalForeignApi::class)

package app.hovanki.radar.host

import app.hovanki.radar.AirFrame
import app.hovanki.radar.BleUuid
import app.hovanki.radar.RadarService
import app.hovanki.radar.RadarTrace
import app.hovanki.radar.RadioApi
import app.hovanki.radar.RegionEvent
import app.hovanki.radar.ScanInterest
import app.hovanki.radar.inWords
import app.hovanki.shared.rules.IBeaconFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.CoreLocation.CLBeacon
import platform.CoreLocation.CLBeaconIdentityConstraint
import platform.CoreLocation.CLBeaconRegion
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.CLRegion
import platform.Foundation.NSError
import platform.Foundation.NSUUID
import platform.darwin.NSObject

/**
 * CoreLocation's part of a run (CoreBluetooth never shows an app iBeacon frames): ranging of the beacons of every
 * [ScanInterest.BeaconRanging] (once a second, also from a pocket, screen off, while the round's location updates keep
 * the app alive), each beacon an [AirFrame] with [AirFrame.iBeacon]; and monitoring of the beacon region of every
 * [ScanInterest.BeaconRegion], its enter and exit frames with [AirFrame.regionEvent]. Nothing happens without an
 * interest of either kind. [onAuthorization]: the location permission, for the host's caps.
 *
 * The delegate of its manager, which holds it only weakly: whoever runs it keeps a reference until [close].
 */
internal class IosBeacons(
    interests: List<ScanInterest>,
    private val trace: RadarTrace,
    private val onAuthorization: (CLLocationManager) -> Unit,
    private val onFrame: (AirFrame) -> Unit,
) : NSObject(),
    CLLocationManagerDelegateProtocol {
    private val ranged = interests.filterIsInstance<ScanInterest.BeaconRanging>()
    private val monitored = interests.filterIsInstance<ScanInterest.BeaconRegion>()
    private val constraints = ranged.map { BleUuid.normalize(it.uuid) }.distinct().map(::constraintOf)
    private val regions = monitored.map { BleUuid.normalize(it.uuid) }.distinct().map { uuid ->
        CLBeaconRegion(constraintOf(uuid), regionIdOf(uuid)).apply { notifyEntryStateOnDisplay = false }
    }
    private val regionIds = regions.map { it.identifier }.toSet()
    private val manager: CLLocationManager? = if (ranged.isEmpty() && monitored.isEmpty()) null else CLLocationManager()

    init {
        manager?.let { manager ->
            manager.delegate = this
            regions.forEach(manager::startMonitoringForRegion)
            if (regions.isNotEmpty()) trace.scan("start", RadioApi.CORELOCATION_REGION, monitored.inWords())
            constraints.forEach(manager::startRangingBeaconsSatisfyingConstraint)
            if (constraints.isNotEmpty()) trace.scan("start", RadioApi.CORELOCATION_RANGING, ranged.inWords())
        }
    }

    fun close() {
        val manager = manager ?: return
        constraints.forEach(manager::stopRangingBeaconsSatisfyingConstraint)
        regions.forEach(manager::stopMonitoringForRegion)
        manager.delegate = null
        if (constraints.isNotEmpty()) trace.scan("stop", RadioApi.CORELOCATION_RANGING)
        if (regions.isNotEmpty()) trace.scan("stop", RadioApi.CORELOCATION_REGION)
    }

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) = onAuthorization(manager)

    override fun locationManager(
        manager: CLLocationManager,
        didRangeBeacons: List<*>,
        satisfyingConstraint: CLBeaconIdentityConstraint,
    ) {
        val atMillis = nowMillis()
        for (beacon in didRangeBeacons.filterIsInstance<CLBeacon>()) {
            val rssi = beacon.rssi.toInt()
            if (!isReading(rssi)) continue
            val frame = IBeaconFrame(BleUuid.hex(beacon.UUID.UUIDString), beacon.major.intValue, beacon.minor.intValue)
            onFrame(AirFrame(atMillis, rssi, RadioApi.CORELOCATION_RANGING, peer = null, iBeacon = frame))
        }
    }

    override fun locationManager(
        manager: CLLocationManager,
        didFailRangingBeaconsForConstraint: CLBeaconIdentityConstraint,
        error: NSError,
    ) {
        trace.scan("failed", RadioApi.CORELOCATION_RANGING, error = error.localizedDescription)
    }

    @ObjCSignatureOverride
    override fun locationManager(manager: CLLocationManager, didEnterRegion: CLRegion) =
        region(didEnterRegion, RegionEvent.ENTER)

    @ObjCSignatureOverride
    override fun locationManager(manager: CLLocationManager, didExitRegion: CLRegion) =
        region(didExitRegion, RegionEvent.EXIT)

    override fun locationManager(
        manager: CLLocationManager,
        monitoringDidFailForRegion: CLRegion?,
        withError: NSError,
    ) {
        trace.scan("failed", RadioApi.CORELOCATION_REGION, error = withError.localizedDescription)
    }

    /** Only this run's regions: iOS tells about every region the app monitors. */
    private fun region(region: CLRegion, event: RegionEvent) {
        if (region.identifier !in regionIds) return
        onFrame(AirFrame(nowMillis(), rssi = 0, api = RadioApi.CORELOCATION_REGION, peer = null, regionEvent = event))
    }
}

private fun constraintOf(uuid: String) = CLBeaconIdentityConstraint(NSUUID(uuid))

/** The game's region keeps the identifier the radio always used (iOS keeps monitored regions by it). */
private fun regionIdOf(uuid: String): String =
    if (uuid == BleUuid.normalize(RadarService.UUID)) BEACON_REGION_ID else "$BEACON_REGION_ID.$uuid"
