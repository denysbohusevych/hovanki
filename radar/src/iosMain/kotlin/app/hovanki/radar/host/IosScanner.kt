@file:OptIn(ExperimentalForeignApi::class)

package app.hovanki.radar.host

import app.hovanki.radar.AirFrame
import app.hovanki.radar.BleUuid
import app.hovanki.radar.RadarTrace
import app.hovanki.radar.RadioApi
import app.hovanki.radar.ScanInterest
import app.hovanki.radar.inWords
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreBluetooth.CBAdvertisementDataIsConnectable
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataManufacturerDataKey
import platform.CoreBluetooth.CBAdvertisementDataOverflowServiceUUIDsKey
import platform.CoreBluetooth.CBAdvertisementDataServiceDataKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBAdvertisementDataTxPowerLevelKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBManagerState
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateResetting
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnknown
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * The CoreBluetooth scan of a run: one `scanForPeripheralsWithServices` for the UUIDs of every [ScanInterest.Service]
 * and [ScanInterest.OverflowUuids] (CoreBluetooth lists another iPhone's overflow bits only for the UUIDs scanned
 * for, and only while this phone's screen is on), duplicates allowed, so every advertisement is a reading. An iPhone
 * can't filter by manufacturer data: with only [ScanInterest.Manufacturer] interests it scans without a filter (on
 * screen only; iOS never shows an app Apple's own frames). Every advertisement heard is an [AirFrame] for [onFrame].
 * [onState]: the adapter's state, for the host's caps.
 *
 * The delegate of its manager, which holds it only weakly: whoever runs it keeps a reference until [close].
 */
internal class IosScanner(
    interests: List<ScanInterest>,
    private val trace: RadarTrace,
    private val onState: (CBCentralManager) -> Unit,
    private val onFrame: (AirFrame) -> Unit,
) : NSObject(),
    CBCentralManagerDelegateProtocol {
    private val scanned = interests.filter {
        it is ScanInterest.Service || it is ScanInterest.OverflowUuids || it is ScanInterest.Manufacturer
    }

    /** The UUIDs to scan for; null: no filter. */
    private val services: List<CBUUID>? = scanned
        .flatMap {
            when (it) {
                is ScanInterest.Service -> listOf(it.uuid)
                is ScanInterest.OverflowUuids -> it.uuids
                else -> emptyList()
            }
        }
        .map(BleUuid::normalize)
        .distinct()
        .map { CBUUID.UUIDWithString(it) }
        .ifEmpty { null }

    private val central = CBCentralManager()
    private var scanning = false

    /** The last state other than on that was traced as `failed`, so each is said once. */
    private var reported: CBManagerState? = null

    init {
        central.delegate = this
    }

    fun close() {
        if (central.state == CBManagerStatePoweredOn) central.stopScan()
        if (scanning) trace.scan("stop", RadioApi.COREBLUETOOTH)
        scanning = false
        central.delegate = null
    }

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        onState(central)
        if (scanned.isEmpty()) return
        val state = central.state
        if (state == CBManagerStatePoweredOn) {
            reported = null
            if (scanning) return
            central.scanForPeripheralsWithServices(
                services,
                mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to true),
            )
            scanning = true
            val filters = scanned.inWords()
            trace.scan("start", RadioApi.COREBLUETOOTH, if (services == null) "no filter: $filters" else filters)
        } else if (state != CBManagerStateUnknown && state != reported) {
            // Off, unauthorized or unsupported: the scan is over; it starts again when Bluetooth is on.
            scanning = false
            reported = state
            trace.scan("failed", RadioApi.COREBLUETOOTH, error = "bluetooth ${nameOf(state)}")
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
        onFrame(frameOf(advertisementData, rssi, didDiscoverPeripheral.identifier.UUIDString, nowMillis()))
    }
}

/** What CoreBluetooth gives of an advertisement (and its scan response, merged), as the channels read it. */
internal fun frameOf(data: Map<Any?, *>, rssi: Int, peer: String, atMillis: Long): AirFrame = AirFrame(
    atMillis = atMillis,
    rssi = rssi,
    api = RadioApi.COREBLUETOOTH,
    peer = peer,
    name = data[CBAdvertisementDataLocalNameKey] as? String,
    serviceUuids = uuidsOf(data[CBAdvertisementDataServiceUUIDsKey]),
    overflowUuids = uuidsOf(data[CBAdvertisementDataOverflowServiceUUIDsKey]),
    serviceData = serviceDataOf(data[CBAdvertisementDataServiceDataKey]),
    manufacturerData = manufacturerDataOf(data[CBAdvertisementDataManufacturerDataKey]),
    txPower = intOf(data[CBAdvertisementDataTxPowerLevelKey]),
    connectable = booleanOf(data[CBAdvertisementDataIsConnectable]),
)

private fun uuidsOf(value: Any?): List<String> =
    (value as? List<*>).orEmpty().mapNotNull { (it as? CBUUID)?.UUIDString?.let(BleUuid::normalize) }

private fun serviceDataOf(value: Any?): Map<String, ByteArray> = buildMap {
    for ((key, data) in (value as? Map<*, *>).orEmpty()) {
        val uuid = (key as? CBUUID)?.UUIDString ?: continue
        val bytes = (data as? NSData)?.toByteArray() ?: continue
        put(BleUuid.normalize(uuid), bytes)
    }
}

/** The company id is the first two bytes, least significant first; the rest is its data. */
private fun manufacturerDataOf(value: Any?): Map<Int, ByteArray> {
    val bytes = (value as? NSData)?.toByteArray() ?: return emptyMap()
    if (bytes.size < 2) return emptyMap()
    val companyId = (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8)
    return mapOf(companyId to bytes.copyOfRange(2, bytes.size))
}

private fun intOf(value: Any?): Int? = when (value) {
    is NSNumber -> value.intValue
    is Number -> value.toInt()
    else -> null
}

private fun booleanOf(value: Any?): Boolean? = when (value) {
    is NSNumber -> value.boolValue
    is Boolean -> value
    else -> null
}

private fun NSData.toByteArray(): ByteArray {
    val bytes = ByteArray(length.toInt())
    if (bytes.isNotEmpty()) {
        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), this.bytes, length) }
    }
    return bytes
}

private fun nameOf(state: CBManagerState): String = when (state) {
    CBManagerStatePoweredOff -> "off"
    CBManagerStateUnauthorized -> "unauthorized"
    CBManagerStateUnsupported -> "unsupported"
    CBManagerStateResetting -> "resetting"
    else -> "state $state"
}
