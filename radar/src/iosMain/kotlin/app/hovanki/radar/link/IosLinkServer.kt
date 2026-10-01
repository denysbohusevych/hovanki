@file:OptIn(ExperimentalForeignApi::class)

package app.hovanki.radar.link

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.CoreBluetooth.CBATTErrorInvalidAttributeValueLength
import platform.CoreBluetooth.CBATTErrorInvalidOffset
import platform.CoreBluetooth.CBATTErrorReadNotPermitted
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBAttributePermissionsReadable
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.Foundation.NSError
import platform.darwin.NSObject

/**
 * The server side of the iOS link: the link service in a `CBPeripheralManager` ([IosLink.TOKEN]: read, write, notify;
 * no fixed value, so every read comes here and gets the current token), advertised once added. A token change is
 * sent to every subscribed central (again when iOS says its queue has room); a central's write of its token is a
 * reading (`notified`).
 *
 * iOS takes `addService` and `startAdvertising` only once the manager is powered on, which comes a moment after it is
 * made: both wait for [peripheralManagerDidUpdateState]. Bluetooth off removes the service: it is added again when
 * it is on. An app in the background can't start an advertisement: the one started on screen stays on the air (in
 * the overflow area). The delegate of its manager, which holds it only weakly: the link keeps it until [close].
 */
internal class IosLinkServer(token: String?, private val trace: LinkTrace, private val emit: (LinkReading) -> Unit) :
    NSObject(),
    CBPeripheralManagerDelegateProtocol {
    private val manager = CBPeripheralManager()
    private val characteristic = CBMutableCharacteristic(
        type = IosLink.TOKEN,
        properties = CBCharacteristicPropertyRead or CBCharacteristicPropertyWrite or CBCharacteristicPropertyNotify,
        value = null,
        permissions = CBAttributePermissionsReadable or CBAttributePermissionsWriteable,
    )
    private val service = CBMutableService(type = IosLink.SERVICE, primary = true).also {
        it.setCharacteristics(listOf(characteristic))
    }
    private var value: ByteArray = IosLink.bytesOf(token)
    private var added = false
    private var closed = false

    /** A notification iOS had no room for: sent again from [peripheralManagerIsReadyToUpdateSubscribers]. */
    private var pending = false

    /** The centrals subscribed to the token, by identifier (the manager's `onSubscribedCentrals: nil` sends to all). */
    private val subscribers = mutableSetOf<String>()

    init {
        manager.delegate = this
    }

    fun tokenChanged(token: String?) {
        val bytes = IosLink.bytesOf(token)
        if (bytes.contentEquals(value)) return
        value = bytes
        pending = bytes.isNotEmpty() && subscribers.isNotEmpty()
        sendPending()
    }

    fun close() {
        closed = true
        if (manager.state == CBManagerStatePoweredOn) {
            manager.stopAdvertising()
            manager.removeAllServices()
        }
        subscribers.clear()
        manager.delegate = null
    }

    override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
        if (closed) return
        if (peripheral.state != CBManagerStatePoweredOn) {
            // The service and the advertisement are gone with the adapter; the client side traces the state.
            added = false
            subscribers.clear()
            return
        }
        if (added) return
        added = true
        peripheral.addService(service)
    }

    override fun peripheralManager(peripheral: CBPeripheralManager, didAddService: CBService, error: NSError?) {
        if (closed) return
        if (error != null) {
            added = false
            trace.link("server_failed", error = IosLink.errorOf(error))
            return
        }
        trace.link("server_start")
        peripheral.startAdvertising(mapOf<Any?, Any?>(CBAdvertisementDataServiceUUIDsKey to listOf(IosLink.SERVICE)))
    }

    override fun peripheralManagerDidStartAdvertising(peripheral: CBPeripheralManager, error: NSError?) {
        if (closed) return
        if (error != null) {
            trace.link("advertise_failed", error = IosLink.errorOf(error))
        } else {
            trace.link("advertise_start")
        }
    }

    @ObjCSignatureOverride
    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        central: CBCentral,
        didSubscribeToCharacteristic: CBCharacteristic,
    ) {
        val id = central.identifier.UUIDString
        subscribers += id
        trace.link("central_subscribed", id)
    }

    @ObjCSignatureOverride
    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        central: CBCentral,
        didUnsubscribeFromCharacteristic: CBCharacteristic,
    ) {
        val id = central.identifier.UUIDString
        subscribers -= id
        trace.link("central_unsubscribed", id)
    }

    override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveReadRequest: CBATTRequest) {
        val request = didReceiveReadRequest
        val offset = request.offset.toInt()
        val bytes = value
        when {
            request.characteristic.UUID != IosLink.TOKEN ->
                peripheral.respondToRequest(request, withResult = CBATTErrorReadNotPermitted)

            offset > bytes.size -> peripheral.respondToRequest(request, withResult = CBATTErrorInvalidOffset)

            else -> {
                request.value = bytes.copyOfRange(offset, bytes.size).toNSData()
                peripheral.respondToRequest(request, withResult = CBATTErrorSuccess)
            }
        }
    }

    /** Several writes may come at once; iOS wants one answer, to the first. */
    override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveWriteRequests: List<*>) {
        val requests = didReceiveWriteRequests.filterIsInstance<CBATTRequest>()
        val first = requests.firstOrNull() ?: return
        var valid = true
        val tokens = mutableListOf<Pair<String, String>>()
        for (request in requests) {
            val token = if (request.characteristic.UUID == IosLink.TOKEN && request.offset.toInt() == 0) {
                request.value?.toByteArray()?.let(GattLinkRules::tokenOf)
            } else {
                null
            }
            if (token == null) valid = false else tokens += request.central.identifier.UUIDString to token
        }
        peripheral.respondToRequest(
            first,
            withResult = if (valid) CBATTErrorSuccess else CBATTErrorInvalidAttributeValueLength,
        )
        for ((peer, token) in tokens) {
            trace.link("notified", peer, token)
            emit(LinkReading(peer, token, rssi = null, IosLink.now(), IosLink.API))
        }
    }

    override fun peripheralManagerIsReadyToUpdateSubscribers(peripheral: CBPeripheralManager) {
        sendPending()
    }

    private fun sendPending() {
        if (!pending || closed || manager.state != CBManagerStatePoweredOn) return
        pending =
            !manager.updateValue(value.toNSData(), forCharacteristic = characteristic, onSubscribedCentrals = null)
    }
}
