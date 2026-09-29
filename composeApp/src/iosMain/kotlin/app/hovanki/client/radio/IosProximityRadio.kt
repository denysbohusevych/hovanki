@file:OptIn(ExperimentalForeignApi::class)

package app.hovanki.client.radio

import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.rules.RadarToken
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataServiceDataKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationRestricted
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBUUID
import platform.CoreLocation.CLBeacon
import platform.CoreLocation.CLBeaconIdentityConstraint
import platform.CoreLocation.CLBeaconRegion
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUUID
import platform.Foundation.allKeys
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * The radar over Bluetooth LE on iOS (docs/adr/0012-nearby-radar.md, section 2.2). A hider's phone advertises the
 * game's service UUID with the token as its name (iOS lets a third-party app advertise nothing else, and keeps only 8
 * characters of the name next to a 128-bit UUID; in the background only the UUID goes out, in the overflow area
 * other iPhones see). A seeker's phone advertises an
 * iBeacon frame with the token as major and minor, which works on the screen only, where a seeker is anyway. Every
 * phone scans through CoreBluetooth for the hiders' service (an Android's service data, an iPhone's name) and ranges
 * the seekers' iBeacon through CoreLocation, which keeps giving the signal once a second from a pocket, screen off,
 * while the round's location updates keep the app alive («Пульс»); the beacon region also wakes the app when a
 * seeker comes near.
 *
 * Reading the token of an iPhone hider in the background (a connection and a characteristic) is not done yet: the
 * spike on real phones decides. Written without an iOS build at hand: the first run on a device is part of the spike.
 */
class IosProximityRadio(private val trace: RadioTrace = RadioTrace.None) : ProximityRadio {
    private val mutableState = MutableStateFlow(BluetoothState.UNSUPPORTED)
    override val state: StateFlow<BluetoothState> = mutableState

    /** Watches the adapter for [state] without scanning; created on the first use, which also asks the user. */
    private var watcher: CBCentralManager? = null
    private val watcherDelegate = object : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            mutableState.value = stateOf(central)
        }
    }

    /** Starts watching the adapter (the system asks for Bluetooth the first time). */
    override fun refresh() {
        if (watcher == null) watcher = CBCentralManager(watcherDelegate, null)
        watcher?.let { mutableState.value = stateOf(it) }
    }

    override fun run(tokens: StateFlow<String?>, asSeeker: Boolean): Flow<RadioSighting> = callbackFlow {
        val listener = Listener(
            onState = { central -> mutableState.value = stateOf(central) },
            onHeard = { token, rssi, api, via, peer ->
                if (RadarToken.isWellFormed(token)) {
                    val atMillis = (NSDate().timeIntervalSince1970 * 1000).toLong()
                    trySend(RadioSighting(token, rssi, atMillis, api, via, peer))
                }
            },
            trace = trace,
        )
        val advertiser = Advertiser(if (asSeeker) "ibeacon" else "hider_name", trace) { token ->
            if (asSeeker) {
                val (major, minor) = RadarToken.toMajorMinor(token)
                val beacon = CLBeaconRegion(
                    uUID = NSUUID(SERVICE_UUID),
                    major = major.toUShort(),
                    minor = minor.toUShort(),
                    identifier = BEACON_REGION_ID,
                )
                val dictionary = beacon.peripheralDataWithMeasuredPower(null)
                dictionary.allKeys.associateWith<Any?, Any?> { dictionary.objectForKey(it) }
            } else {
                // The bare token: next to a 128-bit service iOS keeps 8 characters of the name, which is exactly it.
                mapOf(
                    CBAdvertisementDataServiceUUIDsKey to listOf(CBUUID.UUIDWithString(SERVICE_UUID)),
                    CBAdvertisementDataLocalNameKey to token,
                )
            }
        }
        val tokenJob = tokens.onEach { advertiser.advertise(it) }.launchIn(this)
        // Both are the delegates of their managers, which hold them only weakly: referenced here, they live as long
        // as the radio runs (a delegate the garbage collector took would leave the phone deaf without a word).
        awaitClose {
            tokenJob.cancel()
            advertiser.close()
            listener.close()
        }
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

    companion object {
        /** The same as on Android: the phones of both kinds hear each other by it. */
        const val SERVICE_UUID = "7A0B8D2E-4C1F-4E6A-9B3D-2F5E8C1A7D10"

        /**
         * An iPhone hider on the screen carries its token as its name (iOS advertises no data); the first apps put
         * this before it, which iOS cut off with the token's end, so it is only still read.
         */
        const val NAME_PREFIX = "hv"
    }
}

private const val BEACON_REGION_ID = "app.hovanki.radar"

/** Anything weaker than this is noise. */
private const val MIN_RSSI = -110

/** A real reading: CoreLocation reports 0 for a beacon it lost, CoreBluetooth 127 for none; weaker is noise. */
private fun isReading(rssi: Int): Boolean = rssi in MIN_RSSI..-1

private fun NSData.toHex(): String {
    val bytes = ByteArray(length.toInt())
    if (bytes.isNotEmpty()) {
        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), this.bytes, length) }
    }
    return bytes.joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
}

/**
 * Listens while the radio runs: scans through CoreBluetooth for the hiders' service (an Android's service data, an
 * iPhone's name) and ranges the seekers' iBeacon through CoreLocation (CoreBluetooth never shows iBeacon frames to an
 * app). The delegate of both managers, which hold it only weakly: whoever runs it keeps a reference until [close].
 */
private class Listener(
    private val onState: (CBCentralManager) -> Unit,
    private val onHeard: (token: String, rssi: Int, api: RadioApi, via: SightingVia, peer: String?) -> Unit,
    private val trace: RadioTrace,
) : NSObject(),
    CBCentralManagerDelegateProtocol,
    CLLocationManagerDelegateProtocol {
    private val serviceUuid = CBUUID.UUIDWithString(IosProximityRadio.SERVICE_UUID)
    private val central = CBCentralManager()
    private val ranger = CLLocationManager()
    private val constraint = CLBeaconIdentityConstraint(NSUUID(IosProximityRadio.SERVICE_UUID))
    private val region = CLBeaconRegion(constraint, BEACON_REGION_ID)

    init {
        central.delegate = this
        ranger.delegate = this
        region.notifyEntryStateOnDisplay = false
        ranger.startMonitoringForRegion(region)
        ranger.startRangingBeaconsSatisfyingConstraint(constraint)
        trace.scan("start", RadioApi.CORELOCATION_RANGING, "iBeacon of the game")
    }

    fun close() {
        ranger.stopRangingBeaconsSatisfyingConstraint(constraint)
        ranger.stopMonitoringForRegion(region)
        ranger.delegate = null
        if (central.state == CBManagerStatePoweredOn) central.stopScan()
        central.delegate = null
        trace.scan("stop", RadioApi.COREBLUETOOTH)
        trace.scan("stop", RadioApi.CORELOCATION_RANGING)
    }

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        onState(central)
        if (central.state == CBManagerStatePoweredOn) {
            central.scanForPeripheralsWithServices(
                listOf(serviceUuid),
                mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to true),
            )
            trace.scan("start", RadioApi.COREBLUETOOTH, "game service")
        }
    }

    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber,
    ) {
        val rssi = RSSI.intValue
        if (!isReading(rssi)) return
        @Suppress("UNCHECKED_CAST")
        val serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? Map<Any?, *>
        val data = serviceData?.entries?.firstOrNull { (key, _) ->
            (key as? CBUUID)?.UUIDString.equals(IosProximityRadio.SERVICE_UUID, ignoreCase = true)
        }?.value as? NSData
        val peer = didDiscoverPeripheral.identifier.UUIDString
        if (data != null) {
            onHeard(data.toHex(), rssi, RadioApi.COREBLUETOOTH, SightingVia.SERVICE_DATA, peer)
            return
        }
        // Only the game's service is scanned for: its name is a token, bare or after the first apps' prefix.
        val name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?: return
        val token = name.removePrefix(IosProximityRadio.NAME_PREFIX)
        onHeard(token, rssi, RadioApi.COREBLUETOOTH, SightingVia.NAME, peer)
    }

    override fun locationManager(
        manager: CLLocationManager,
        didRangeBeacons: List<*>,
        satisfyingConstraint: CLBeaconIdentityConstraint,
    ) {
        for (beacon in didRangeBeacons) {
            val found = beacon as? CLBeacon ?: continue
            val rssi = found.rssi.toInt()
            if (!isReading(rssi)) continue
            val token = RadarToken.fromMajorMinor(found.major.intValue, found.minor.intValue)
            onHeard(token, rssi, RadioApi.CORELOCATION_RANGING, SightingVia.IBEACON, null)
        }
    }
}

/**
 * Advertises the latest token for as long as it is set, whatever the adapter does meanwhile. iOS takes
 * `startAdvertising` only once the peripheral manager says it is powered on, which comes a moment after it is made
 * (and again after Bluetooth was switched off and on): a call before that is dropped without a word, so the token
 * waits here and goes out from [peripheralManagerDidUpdateState].
 *
 * Since iOS 14 an app in the background can neither start an advertisement nor change it: a restart there (the token's
 * five-minute slot changing while the phone is locked) would stop the one on the air and start nothing, and the phone
 * would be gone from the radar until it is unlocked. So in the background the advertisement on the air stays; the new
 * token waits for the app to come back ([UIApplicationDidBecomeActiveNotification]). In the background iOS sends neither
 * the name nor the iBeacon frame anyway, at most the service's bit (docs/adr/0016-iphone-overflow-radar.md), so an old
 * token there costs nothing.
 */
private class Advertiser(
    private val mode: String,
    private val trace: RadioTrace,
    private val data: (token: String) -> Map<Any?, *>,
) : NSObject(),
    CBPeripheralManagerDelegateProtocol {
    private val manager = CBPeripheralManager()
    private var token: String? = null

    /** The token on the air; null: nothing is. */
    private var advertised: String? = null

    private val becameActive = NSNotificationCenter.defaultCenter.addObserverForName(
        UIApplicationDidBecomeActiveNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ -> restart() }

    init {
        manager.delegate = this
    }

    fun advertise(token: String?) {
        this.token = token
        restart()
    }

    fun close() {
        NSNotificationCenter.defaultCenter.removeObserver(becameActive)
        token = null
        restart()
        manager.delegate = null
    }

    override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
        // Bluetooth off (or not yet on) ends any advertisement: nothing is on the air until it is started again.
        if (peripheral.state != CBManagerStatePoweredOn) advertised = null
        restart()
    }

    private fun restart() {
        if (manager.state != CBManagerStatePoweredOn) return
        val wanted = token
        if (wanted == advertised) return
        // Stopping works anywhere; a new token waits for the screen while one is on the air (see above).
        val inBackground =
            UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateBackground
        if (wanted != null && advertised != null && inBackground) {
            trace.advertise("skipped_background", mode, wanted)
            return
        }
        manager.stopAdvertising()
        if (advertised != null) trace.advertise("stop", mode, advertised)
        advertised = null
        if (wanted != null) {
            manager.startAdvertising(data(wanted))
            advertised = wanted
            trace.advertise("start", mode, wanted)
        }
    }

    override fun peripheralManagerDidStartAdvertising(peripheral: CBPeripheralManager, error: NSError?) {
        if (error != null) trace.advertise("failed", mode, advertised, error.localizedDescription)
    }
}
