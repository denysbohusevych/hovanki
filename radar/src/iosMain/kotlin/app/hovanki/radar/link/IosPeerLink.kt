@file:OptIn(ExperimentalForeignApi::class)

package app.hovanki.radar.link

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBService
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject

/**
 * One peer the iOS link connected to: the peripheral (kept here: CoreBluetooth drops a peripheral nobody holds) and
 * its delegate, which the peripheral holds only weakly. Once connected ([discover]) it finds the link service and
 * characteristic, reads the peer's token and subscribes to its notifications; [write] and [readRssi] come from the
 * link's loops. CoreBluetooth queues the operations itself.
 *
 * A read's answer and a notification both come as `didUpdateValueForCharacteristic`: the ones after a read asked
 * for count as the read ([reads]), the others as `notified`. Main thread only.
 */
internal class IosPeerLink(
    val peripheral: CBPeripheral,
    val id: String,
    private val trace: LinkTrace,
    private val emit: (LinkReading) -> Unit,
    private val onToken: (id: String, token: String) -> Unit,
    private val onNoService: (IosPeerLink) -> Unit,
) : NSObject(),
    CBPeripheralDelegateProtocol {
    private var characteristic: CBCharacteristic? = null

    /** The peer's token as last read or notified: the RSSI readings carry it. */
    private var lastToken: String? = null
    private var reads = 0
    private var closed = false

    init {
        peripheral.delegate = this
    }

    fun discover() {
        characteristic = null
        reads = 0
        peripheral.discoverServices(listOf(IosLink.SERVICE))
    }

    fun disconnected() {
        characteristic = null
        reads = 0
    }

    fun write(data: NSData) {
        val characteristic = ready() ?: return
        peripheral.writeValue(data, forCharacteristic = characteristic, type = CBCharacteristicWriteWithResponse)
    }

    fun readRssi() {
        ready() ?: return
        peripheral.readRSSI()
    }

    fun close() {
        closed = true
        characteristic = null
        peripheral.delegate = null
    }

    override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
        if (closed) return
        val service = peripheral.services.orEmpty().filterIsInstance<CBService>().firstOrNull {
            it.UUID == IosLink.SERVICE
        }
        if (didDiscoverServices != null || service == null) {
            trace.link("services", id, error = IosLink.errorOf(didDiscoverServices) ?: "no link service")
            onNoService(this)
            return
        }
        peripheral.discoverCharacteristics(listOf(IosLink.TOKEN), forService = service)
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didDiscoverCharacteristicsForService: CBService,
        error: NSError?,
    ) {
        if (closed) return
        val found = didDiscoverCharacteristicsForService.characteristics.orEmpty()
            .filterIsInstance<CBCharacteristic>()
            .firstOrNull { it.UUID == IosLink.TOKEN }
        if (error != null || found == null) {
            trace.link("services", id, error = IosLink.errorOf(error) ?: "no link characteristic")
            onNoService(this)
            return
        }
        trace.link("services", id)
        characteristic = found
        reads++
        peripheral.readValueForCharacteristic(found)
        peripheral.setNotifyValue(true, forCharacteristic = found)
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didUpdateValueForCharacteristic: CBCharacteristic,
        error: NSError?,
    ) {
        if (closed || didUpdateValueForCharacteristic.UUID != IosLink.TOKEN) return
        val asRead = reads > 0
        if (asRead) reads--
        val token = didUpdateValueForCharacteristic.value?.toByteArray()?.let(GattLinkRules::tokenOf)
        if (error != null || token == null) {
            trace.link(if (asRead) "read" else "notified", id, error = IosLink.errorOf(error) ?: "not a token")
            return
        }
        lastToken = token
        onToken(id, token)
        if (!asRead) trace.link("notified", id, token)
        emit(LinkReading(id, token, rssi = null, IosLink.now(), IosLink.API))
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didWriteValueForCharacteristic: CBCharacteristic,
        error: NSError?,
    ) {
        if (closed) return
        trace.link("wrote", id, error = IosLink.errorOf(error))
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didUpdateNotificationStateForCharacteristic: CBCharacteristic,
        error: NSError?,
    ) {
        if (closed) return
        trace.link("subscribed", id, error = IosLink.errorOf(error))
    }

    override fun peripheral(peripheral: CBPeripheral, didReadRSSI: NSNumber, error: NSError?) {
        if (closed) return
        if (error != null) {
            trace.link("read_rssi", id, error = IosLink.errorOf(error))
            return
        }
        emit(LinkReading(id, lastToken, didReadRSSI.intValue, IosLink.now(), IosLink.API))
    }

    private fun ready(): CBCharacteristic? =
        characteristic?.takeIf { !closed && peripheral.state == CBPeripheralStateConnected }
}
