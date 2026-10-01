@file:OptIn(ExperimentalForeignApi::class)

package app.hovanki.radar.link

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBManagerState
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateResetting
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnknown
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBPeripheralStateConnecting
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject

/**
 * The client side of the iOS link: a `CBCentralManager` scanning for the link service, connecting to every peer it
 * finds (by identifier, at most [GattLinkRules.MAX_LINKS], [LinkPeers]) and keeping each peer's [IosPeerLink] (the
 * peripheral and its delegate) referenced. A drop is answered with `connectPeripheral` at once: iOS keeps the
 * request pending until the peer is back, also in the background (`reconnect`); a failed attempt is asked again in
 * [GattLinkRules.RECONNECT_MILLIS]. Bluetooth off traces `failed`; on again, the scan and the links start again.
 *
 * The delegate of its manager, which holds it only weakly: the link keeps it until [close]. Main thread only.
 */
internal class IosLinkClient(
    private val trace: LinkTrace,
    private val emit: (LinkReading) -> Unit,
    private val scope: CoroutineScope,
) : NSObject(),
    CBCentralManagerDelegateProtocol {
    private val central = CBCentralManager()
    private val peers = LinkPeers()
    private val links = mutableMapOf<String, IosPeerLink>()
    private var scanning = false
    private var closed = false

    /** The last state other than on that was traced, so each is said once. */
    private var reported: CBManagerState? = null

    init {
        central.delegate = this
    }

    fun writeAll(token: String) {
        val bytes = IosLink.bytesOf(token)
        if (bytes.isEmpty()) return
        val data = bytes.toNSData()
        links.values.forEach { it.write(data) }
    }

    fun readRssiAll() {
        links.values.forEach { it.readRssi() }
    }

    fun close() {
        closed = true
        if (central.state == CBManagerStatePoweredOn) {
            central.stopScan()
            links.values.forEach { central.cancelPeripheralConnection(it.peripheral) }
        }
        links.values.forEach { it.close() }
        links.clear()
        peers.clear()
        scanning = false
        central.delegate = null
    }

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        if (closed) return
        val state = central.state
        if (state == CBManagerStatePoweredOn) {
            reported = null
            if (!scanning) {
                // No duplicates: a peer is found once, then the link carries it.
                central.scanForPeripheralsWithServices(listOf(IosLink.SERVICE), options = null)
                scanning = true
                trace.link("scan_start")
            }
            // Back from off: the links went with the adapter.
            links.values.filter { !it.isConnecting }.forEach { connect(it, again = true) }
        } else if (state != CBManagerStateUnknown && state != reported) {
            scanning = false
            reported = state
            trace.link("failed", error = "bluetooth ${nameOf(state)}")
        }
    }

    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber,
    ) {
        if (closed) return
        val peripheral = didDiscoverPeripheral
        val id = peripheral.identifier.UUIDString
        when (val found = peers.found(id, IosLink.now())) {
            LinkPeers.Found.Known, LinkPeers.Found.Full -> return

            LinkPeers.Found.Connect -> Unit

            is LinkPeers.Found.Replace -> links.remove(found.forgotten)?.let {
                forget(it)
                trace.link("forget", found.forgotten)
            }
        }
        val link = IosPeerLink(peripheral, id, trace, emit, ::tokenRead, ::noService)
        links[id] = link
        connect(link, again = false)
    }

    @ObjCSignatureOverride
    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
        val id = didConnectPeripheral.identifier.UUIDString
        val link = links[id] ?: return
        peers.connected(id, IosLink.now())
        trace.link("connected", id)
        link.discover()
    }

    @ObjCSignatureOverride
    override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
        val id = didFailToConnectPeripheral.identifier.UUIDString
        val link = links[id] ?: return
        trace.link("disconnected", id, error = IosLink.errorOf(error) ?: "failed to connect")
        scope.launch {
            delay(GattLinkRules.RECONNECT_MILLIS)
            if (!closed && links[id] === link) connect(link, again = true)
        }
    }

    @ObjCSignatureOverride
    override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
        val id = didDisconnectPeripheral.identifier.UUIDString
        val link = links[id] ?: return
        link.disconnected()
        peers.disconnected(id, IosLink.now())
        trace.link("disconnected", id, error = IosLink.errorOf(error))
        if (!closed) connect(link, again = true)
    }

    private fun connect(link: IosPeerLink, again: Boolean) {
        if (closed || central.state != CBManagerStatePoweredOn) return
        trace.link(if (again) "reconnect" else "connect", link.id)
        central.connectPeripheral(link.peripheral, options = null)
    }

    /** [id] read [token]: a silent peer with the same token was this one under its old identifier. */
    private fun tokenRead(id: String, token: String) {
        val old = peers.token(id, token) ?: return
        links.remove(old)?.let(::forget)
        trace.link("identifier_changed", old, token)
    }

    /** The peer has no link characteristic: nothing to do with it. */
    private fun noService(link: IosPeerLink) {
        peers.remove(link.id)
        if (links[link.id] === link) links.remove(link.id)
        forget(link)
        trace.link("forget", link.id)
    }

    /** Stops asking for [link]'s connection (a pending request too) and lets its delegate go. */
    private fun forget(link: IosPeerLink) {
        if (central.state == CBManagerStatePoweredOn) central.cancelPeripheralConnection(link.peripheral)
        link.close()
    }

    private val IosPeerLink.isConnecting: Boolean
        get() = peripheral.state == CBPeripheralStateConnected || peripheral.state == CBPeripheralStateConnecting
}

private fun nameOf(state: CBManagerState): String = when (state) {
    CBManagerStatePoweredOff -> "off"
    CBManagerStateUnauthorized -> "unauthorized"
    CBManagerStateUnsupported -> "unsupported"
    CBManagerStateResetting -> "resetting"
    else -> "state $state"
}
