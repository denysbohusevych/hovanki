package app.hovanki.radar.lab

import app.hovanki.radar.IosProximityRadio
import app.hovanki.radar.RadioApi
import app.hovanki.shared.rules.OverflowArea
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import platform.CoreBluetooth.CBAdvertisementDataOverflowServiceUUIDsKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.darwin.NSObject

/**
 * The radio lab's own Bluetooth on an iPhone (docs/radio-lab.md §5), beside the game's [IosProximityRadio]:
 *
 * - listening: a scan for the 128 UUIDs of the overflow table and the game's service; CoreBluetooth lists the table's
 *   UUIDs whose bits stand in a locked iPhone's overflow area (`CBAdvertisementDataOverflowServiceUUIDsKey`). Only
 *   while this phone's screen is on.
 * - the probe: advertising the game's service and the table's UUIDs for the chosen bits; locked, iOS moves them into
 *   the overflow area. A change of the bits in the background is skipped, as the game's advertiser does, and applied
 *   when the app is active again.
 *
 * Debug builds only; written without an iOS build at hand.
 */
class IosLabAir : LabAir {
    override val canListen: Boolean = true
    override val canProbe: Boolean = true

    override fun listen(): Flow<AirFrame> = callbackFlow {
        val scanner = OverflowScanner { frame -> trySend(frame) }
        awaitClose { scanner.close() }
    }

    override fun probe(bits: StateFlow<Set<Int>>): Flow<ProbeEvent> = callbackFlow {
        val probe = OverflowProbe { event -> trySend(event) }
        val job = bits.onEach { probe.advertise(it) }.launchIn(this)
        awaitClose {
            job.cancel()
            probe.close()
        }
    }
}

/** Anything weaker than this is noise, as in the game's radio. */
private const val MIN_RSSI = -110

/** Scans for the table's UUIDs; the delegate of its manager, kept by whoever runs it until [close]. */
private class OverflowScanner(private val onFrame: (AirFrame) -> Unit) :
    NSObject(),
    CBCentralManagerDelegateProtocol {
    private val central = CBCentralManager()
    private val services = OverflowArea.UUIDS.map { CBUUID.UUIDWithString(it) } +
        CBUUID.UUIDWithString(IosProximityRadio.SERVICE_UUID)

    init {
        central.delegate = this
    }

    fun close() {
        if (central.state == CBManagerStatePoweredOn) central.stopScan()
        central.delegate = null
    }

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        if (central.state == CBManagerStatePoweredOn) {
            central.scanForPeripheralsWithServices(
                services,
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
        if (rssi !in MIN_RSSI..-1) return
        val listed = advertisementData[CBAdvertisementDataOverflowServiceUUIDsKey] as? List<*> ?: return
        val bits = listed.mapNotNull { uuid -> (uuid as? CBUUID)?.UUIDString?.let(OverflowArea::bitOf) }.toSet()
        if (bits.isEmpty()) return
        val atMillis = (NSDate().timeIntervalSince1970 * 1000).toLong()
        val peer = didDiscoverPeripheral.identifier.UUIDString
        onFrame(AirFrame.Mask(bits, null, rssi, peer, atMillis, RadioApi.COREBLUETOOTH))
    }
}

/**
 * Advertises the game's service and the table's UUIDs for the wanted bits, and says what it did ([ProbeEvent]).
 * Advertising starts only once the manager is powered on; in the background the advertisement on the air stays (iOS
 * can't start another one there) until the app is active again.
 */
private class OverflowProbe(private val onEvent: (ProbeEvent) -> Unit) :
    NSObject(),
    CBPeripheralManagerDelegateProtocol {
    private val manager = CBPeripheralManager()
    private var wanted: Set<Int>? = null
    private var advertised: Set<Int>? = null

    private val becameActive = NSNotificationCenter.defaultCenter.addObserverForName(
        UIApplicationDidBecomeActiveNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ -> restart() }

    init {
        manager.delegate = this
    }

    fun advertise(bits: Set<Int>) {
        wanted = bits
        restart()
    }

    fun close() {
        NSNotificationCenter.defaultCenter.removeObserver(becameActive)
        wanted = null
        restart()
        manager.delegate = null
    }

    override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
        if (peripheral.state != CBManagerStatePoweredOn) advertised = null
        restart()
    }

    override fun peripheralManagerDidStartAdvertising(peripheral: CBPeripheralManager, error: NSError?) {
        if (error != null) onEvent(ProbeEvent("failed", error.localizedDescription))
    }

    private fun restart() {
        if (manager.state != CBManagerStatePoweredOn) return
        val bits = wanted
        if (bits == advertised) return
        val inBackground =
            UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateBackground
        if (bits != null && advertised != null && inBackground) {
            onEvent(ProbeEvent("skipped_background"))
            return
        }
        manager.stopAdvertising()
        if (advertised != null) onEvent(ProbeEvent("stop"))
        advertised = null
        if (bits != null) {
            val uuids = listOf(CBUUID.UUIDWithString(IosProximityRadio.SERVICE_UUID)) +
                bits.sorted().map { CBUUID.UUIDWithString(OverflowArea.uuid(it)) }
            manager.startAdvertising(mapOf(CBAdvertisementDataServiceUUIDsKey to uuids))
            advertised = bits
            onEvent(ProbeEvent("start"))
        }
    }
}
