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
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.Foundation.allKeys
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * The radar over Bluetooth LE on iOS (docs/adr/0010-nearby-radar.md, section 2.2). A hider's phone advertises the
 * game's service UUID with the token in its name (iOS lets a third-party app advertise nothing else; in the
 * background only the UUID goes out, in the overflow area other iPhones see). A seeker's phone advertises an
 * iBeacon frame with the token as major and minor, which works on the screen only, where a seeker is anyway. Every
 * phone scans through CoreBluetooth for the hiders' service (an Android's service data, an iPhone's name) and ranges
 * the seekers' iBeacon through CoreLocation, which keeps giving the signal once a second from a pocket, screen off,
 * while the round's location updates keep the app alive («Пульс»); the beacon region also wakes the app when a
 * seeker comes near.
 *
 * Reading the token of an iPhone hider in the background (a connection and a characteristic) is not done yet: the
 * spike on real phones decides. Written without an iOS build at hand: the first run on a device is part of the spike.
 */
class IosProximityRadio : ProximityRadio {
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
        val serviceUuid = CBUUID.UUIDWithString(SERVICE_UUID)
        fun now(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
        fun heard(token: String, rssi: Int) {
            if (RadarToken.isWellFormed(token)) trySend(RadioSighting(token, rssi, now()))
        }

        // The hiders: their service, by CoreBluetooth.
        val centralDelegate = object : NSObject(), CBCentralManagerDelegateProtocol {
            override fun centralManagerDidUpdateState(central: CBCentralManager) {
                mutableState.value = stateOf(central)
                if (central.state == CBManagerStatePoweredOn) {
                    central.scanForPeripheralsWithServices(
                        listOf(serviceUuid),
                        mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to true),
                    )
                }
            }

            override fun centralManager(
                central: CBCentralManager,
                didDiscoverPeripheral: CBPeripheral,
                advertisementData: Map<Any?, *>,
                RSSI: NSNumber,
            ) {
                val rssi = RSSI.intValue
                if (rssi == 0 || rssi < MIN_RSSI) return
                @Suppress("UNCHECKED_CAST")
                val serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? Map<Any?, *>
                val data = serviceData?.entries?.firstOrNull { (key, _) ->
                    (key as? CBUUID)?.UUIDString.equals(SERVICE_UUID, ignoreCase = true)
                }?.value as? NSData
                if (data != null) {
                    heard(data.toHex(), rssi)
                    return
                }
                val name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?: return
                if (name.startsWith(NAME_PREFIX)) heard(name.removePrefix(NAME_PREFIX), rssi)
            }
        }
        val central = CBCentralManager(centralDelegate, null)

        // The seekers: their iBeacon, by CoreLocation (CoreBluetooth never shows iBeacon frames to an app).
        val constraint = CLBeaconIdentityConstraint(NSUUID(SERVICE_UUID))
        val ranger = CLLocationManager()
        val rangerDelegate = object : NSObject(), CLLocationManagerDelegateProtocol {
            override fun locationManager(
                manager: CLLocationManager,
                didRangeBeacons: List<*>,
                satisfyingConstraint: CLBeaconIdentityConstraint,
            ) {
                for (beacon in didRangeBeacons) {
                    val found = beacon as? CLBeacon ?: continue
                    val rssi = found.rssi.toInt()
                    if (rssi == 0 || rssi < MIN_RSSI) continue
                    heard(RadarToken.fromMajorMinor(found.major.intValue, found.minor.intValue), rssi)
                }
            }
        }
        ranger.delegate = rangerDelegate
        val region = CLBeaconRegion(constraint, BEACON_REGION_ID)
        region.notifyEntryStateOnDisplay = false
        ranger.startMonitoringForRegion(region)
        ranger.startRangingBeaconsSatisfyingConstraint(constraint)

        val peripheralDelegate = object : NSObject(), CBPeripheralManagerDelegateProtocol {
            override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) = Unit
        }
        val peripheral = CBPeripheralManager(peripheralDelegate, null)
        var advertising = false
        fun advertise(token: String?) {
            if (advertising) {
                peripheral.stopAdvertising()
                advertising = false
            }
            if (token == null) return
            val data: Map<Any?, *> = if (asSeeker) {
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
                mapOf(
                    CBAdvertisementDataServiceUUIDsKey to listOf(serviceUuid),
                    CBAdvertisementDataLocalNameKey to NAME_PREFIX + token,
                )
            }
            peripheral.startAdvertising(data)
            advertising = true
        }
        val tokenJob = tokens.onEach { advertise(it) }.launchIn(this)
        awaitClose {
            tokenJob.cancel()
            if (advertising) peripheral.stopAdvertising()
            ranger.stopRangingBeaconsSatisfyingConstraint(constraint)
            ranger.stopMonitoringForRegion(region)
            ranger.delegate = null
            central.stopScan()
            central.delegate = null
            peripheral.delegate = null
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

    private fun NSData.toHex(): String {
        val bytes = ByteArray(length.toInt())
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), this.bytes, length) }
        }
        return bytes.joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
    }

    companion object {
        /** The same as on Android: the phones of both kinds hear each other by it. */
        const val SERVICE_UUID = "7A0B8D2E-4C1F-4E6A-9B3D-2F5E8C1A7D10"

        /** An iPhone hider on the screen carries its token in its name (iOS advertises no data). */
        const val NAME_PREFIX = "hv"
        private const val BEACON_REGION_ID = "app.hovanki.radar"

        /** CoreLocation reports 0 for a beacon it lost; anything weaker than this is noise. */
        private const val MIN_RSSI = -110
    }
}
