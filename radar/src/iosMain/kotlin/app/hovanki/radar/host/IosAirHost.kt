@file:OptIn(ExperimentalForeignApi::class)

package app.hovanki.radar.host

import app.hovanki.radar.AirFrame
import app.hovanki.radar.AirHost
import app.hovanki.radar.AirTally
import app.hovanki.radar.RadarCaps
import app.hovanki.radar.RadarChannel
import app.hovanki.radar.RadarRole
import app.hovanki.radar.RadarTrace
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Platform
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationRestricted
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.Foundation.NSDate
import platform.Foundation.NSThread
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The host of an iPhone (docs/adr/0017-radar-techniques-and-big-run.md, section 2.2): one advertisement of the
 * channels' parts through CoreBluetooth ([IosAdvertiser]: service UUIDs, the overflow table's too, the local name, or
 * an iBeacon frame alone; an app can send neither service data nor manufacturer data), one CoreBluetooth scan for
 * their services ([IosScanner]) and CoreLocation for the iBeacons: ranging, also from a pocket while the round's
 * location updates keep the app alive («Пульс»), and region monitoring ([IosBeacons]).
 *
 * Everything runs on the main thread: the flow is collected there, and the managers call their delegates on the main
 * queue. Each manager holds its delegate only weakly: the run keeps every one of them referenced until it ends (a
 * delegate the garbage collector took would leave the phone deaf and mute without a word).
 *
 * Written without an iOS build at hand: the first run on a device is part of the spike (docs/radar-run.md).
 */
class IosAirHost : AirHost {
    private val mutableCaps = MutableStateFlow(
        RadarCaps(
            platform = Platform.IOS,
            bluetooth = BluetoothState.UNSUPPORTED,
            canScanResponse = false,
            canRangeBeacons = false,
            canReadOverflow = true,
            canAdvertiseOverflow = true,
        ),
    )
    override val caps: StateFlow<RadarCaps> = mutableCaps

    /** Watch the adapter and the location permission for [caps] without scanning; made on the first [refresh]. */
    private var bluetoothWatcher: CBCentralManager? = null
    private var locationWatcher: CLLocationManager? = null
    private val watcherDelegate = object :
        NSObject(),
        CBCentralManagerDelegateProtocol,
        CLLocationManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) = onBluetooth(central)

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) = onLocation(manager)
    }

    /**
     * The first call starts watching (the system asks for Bluetooth then); later ones read the states again. On the
     * main thread: a location manager calls its delegate on the run loop of the thread that made it.
     */
    override fun refresh() {
        if (NSThread.isMainThread) watch() else dispatch_async(dispatch_get_main_queue()) { watch() }
    }

    private fun watch() {
        val central = bluetoothWatcher ?: CBCentralManager(watcherDelegate, null).also { bluetoothWatcher = it }
        val location = locationWatcher ?: CLLocationManager().also {
            it.delegate = watcherDelegate
            locationWatcher = it
        }
        onBluetooth(central)
        onLocation(location)
    }

    override fun run(
        channels: List<RadarChannel>,
        token: StateFlow<String?>,
        role: RadarRole,
        trace: RadarTrace,
    ): Flow<AirFrame> = callbackFlow {
        val tally = AirTally(trace, channels)
        val onFrame = { frame: AirFrame ->
            tally.heard(frame)
            trySend(frame)
            Unit
        }
        val interests = channels.flatMap { it.interests() }.distinct()
        val scanner = IosScanner(interests, trace, ::onBluetooth, onFrame)
        val beacons = IosBeacons(interests, trace, ::onLocation, onFrame)
        val advertiser = IosAdvertiser(channels, role, trace)
        val tokenJob = token.onEach(advertiser::advertise).launchIn(this)
        awaitClose {
            tokenJob.cancel()
            advertiser.close()
            beacons.close()
            scanner.close()
            tally.flush()
        }
    }.flowOn(Dispatchers.Main)

    private fun onBluetooth(central: CBCentralManager) {
        mutableCaps.update { it.copy(bluetooth = stateOf(central)) }
    }

    private fun onLocation(manager: CLLocationManager) {
        val status = manager.authorizationStatus
        val authorized = status == kCLAuthorizationStatusAuthorizedWhenInUse ||
            status == kCLAuthorizationStatusAuthorizedAlways
        mutableCaps.update { it.copy(canRangeBeacons = authorized) }
    }

    private fun stateOf(central: CBCentralManager): BluetoothState = when {
        central.state == CBManagerStateUnsupported -> BluetoothState.UNSUPPORTED

        central.state == CBManagerStateUnauthorized -> BluetoothState.DENIED

        CBManager.authorization == CBManagerAuthorizationDenied ||
            CBManager.authorization == CBManagerAuthorizationRestricted -> BluetoothState.DENIED

        central.state == CBManagerStatePoweredOn -> BluetoothState.ON

        CBManager.authorization != CBManagerAuthorizationAllowedAlways -> BluetoothState.DENIED

        else -> BluetoothState.OFF
    }
}

/** The region of the game's beacon (monitored; also the identifier of the seeker's advertised one). */
internal const val BEACON_REGION_ID = "app.hovanki.radar"

/** Anything weaker than this is noise. */
private const val MIN_RSSI = -110

/** A real reading: CoreLocation reports 0 for a beacon it lost, CoreBluetooth 127 for none; weaker is noise. */
internal fun isReading(rssi: Int): Boolean = rssi in MIN_RSSI..-1

/** The device's clock, as [AirFrame.atMillis] wants it. */
internal fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

internal fun inBackground(): Boolean =
    UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateBackground
