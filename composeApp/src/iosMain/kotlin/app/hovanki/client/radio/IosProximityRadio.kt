@file:OptIn(ExperimentalForeignApi::class)

package app.hovanki.client.radio

import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.rules.RadarToken
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSNumber
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * The radar over Bluetooth LE on iOS (docs/adr/0010-nearby-radar.md, section 2.2): CoreBluetooth advertises the
 * radar token as the service data of the game's service UUID and scans for the same service. In the background iOS
 * advertises only the service UUID (in the overflow area, seen by other iPhones scanning for it) and no data: such a
 * phone is heard but not identified until the connect-and-read step of the spike is done. Written without an iOS
 * build at hand: the first run on a device is part of the spike.
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

    override fun run(tokens: StateFlow<String?>): Flow<RadioSighting> = callbackFlow {
        val serviceUuid = CBUUID.UUIDWithString(SERVICE_UUID)
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
                @Suppress("UNCHECKED_CAST")
                val serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? Map<Any?, *> ?: return
                val data = serviceData.entries.firstOrNull { (key, _) ->
                    (key as? CBUUID)?.UUIDString.equals(SERVICE_UUID, ignoreCase = true)
                }?.value as? NSData ?: return
                val token = data.toHex()
                if (!RadarToken.isWellFormed(token)) return
                val now = (NSDate().timeIntervalSince1970 * 1000).toLong()
                trySend(RadioSighting(token, RSSI.intValue, now))
            }
        }
        val central = CBCentralManager(centralDelegate, null)
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
            // iOS ignores service data in the foreground advertisement of third-party apps on some versions; the
            // service UUID is always there, the data when the system allows it (the spike tells).
            peripheral.startAdvertising(
                mapOf(
                    CBAdvertisementDataServiceUUIDsKey to listOf(serviceUuid),
                    CBAdvertisementDataServiceDataKey to mapOf(serviceUuid to token.hexToNSData()),
                ),
            )
            advertising = true
        }
        val tokenJob = tokens.onEach { advertise(it) }.launchIn(this)
        awaitClose {
            tokenJob.cancel()
            if (advertising) peripheral.stopAdvertising()
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

    private fun String.hexToNSData(): NSData {
        val bytes = ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
        return bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert()) }
    }

    companion object {
        /** The same as on Android: the phones of both kinds hear each other by it. */
        const val SERVICE_UUID = "7A0B8D2E-4C1F-4E6A-9B3D-2F5E8C1A7D10"
    }
}
