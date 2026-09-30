package app.hovanki.radar.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The client side of the Android link: a scan filtered on the link service, and a GATT connection per peer it finds
 * (by address, at most [GattLinkRules.MAX_LINKS], [LinkPeers]). Each connection discovers the service, reads the
 * peer's token, subscribes to its notifications, writes this phone's token ([writeAll]) and reads the RSSI
 * ([readRssiAll]), one operation at a time ([LinkOperations]: Android drops a second one started before the first
 * one's callback); after a drop it connects again in [GattLinkRules.RECONNECT_MILLIS] (a plain `connectGatt`, not
 * `autoConnect`: the peer's address may have rotated, and the scan finds it again under the new one) for as long as
 * the link runs. The OS calls back on its binder threads: the state is under [lock]; [scope] is the collector's.
 */
@SuppressLint("MissingPermission")
internal class AndroidLinkClient(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val trace: LinkTrace,
    private val emit: (LinkReading) -> Unit,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val peers = LinkPeers()
    private val connections = mutableMapOf<String, Connection>()
    private var running = false
    private var scanner: BluetoothLeScanner? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            result.device?.let(::found)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (result in results) result.device?.let(::found)
        }

        override fun onScanFailed(errorCode: Int) {
            trace.link("scan_failed", error = "code $errorCode")
        }
    }

    fun start() {
        synchronized(lock) { running = true }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            trace.link("scan_failed", error = "no scanner")
            return
        }
        try {
            scanner.startScan(listOf(FILTER), SETTINGS, scanCallback)
            this.scanner = scanner
            trace.link("scan_start")
        } catch (e: SecurityException) {
            trace.link("scan_failed", error = e.message ?: "no permission")
        } catch (e: IllegalStateException) {
            trace.link("scan_failed", error = e.message ?: "adapter off")
        }
    }

    fun stop() {
        val open = synchronized(lock) {
            running = false
            peers.clear()
            connections.values.toList().also { connections.clear() }
        }
        open.forEach { it.close() }
        scanner?.let { runCatching { it.stopScan(scanCallback) } }
        scanner = null
    }

    fun writeAll(token: String) {
        val bytes = AndroidLink.bytesOf(token)
        if (bytes.isEmpty()) return
        connectionsNow().forEach { it.write(bytes) }
    }

    /** Also gives up the operations that waited too long: this runs every few seconds anyway. */
    fun readRssiAll() {
        connectionsNow().forEach {
            it.tick()
            it.readRssi()
        }
    }

    private fun connectionsNow(): List<Connection> = synchronized(lock) { connections.values.toList() }

    private fun found(device: BluetoothDevice) {
        val address = device.address ?: return
        var forgotten: Connection? = null
        val connection = synchronized(lock) {
            if (!running) return
            when (val found = peers.found(address, AndroidLink.now())) {
                LinkPeers.Found.Known, LinkPeers.Found.Full -> return
                LinkPeers.Found.Connect -> Unit
                is LinkPeers.Found.Replace -> forgotten = connections.remove(found.forgotten)
            }
            Connection(device, address).also { connections[address] = it }
        }
        forgotten?.let {
            it.close()
            trace.link("forget", it.address)
        }
        connection.connect(again = false)
    }

    /** [address] read [token]: a silent peer with the same token was this one under its old address. */
    private fun tokenRead(address: String, token: String) {
        val (old, connection) = synchronized(lock) {
            val old = peers.token(address, token)
            old to old?.let { connections.remove(it) }
        }
        if (old == null) return
        connection?.close()
        trace.link("identifier_changed", old, token)
    }

    private fun forget(connection: Connection) {
        synchronized(lock) {
            peers.remove(connection.address)
            if (connections[connection.address] === connection) connections.remove(connection.address)
        }
        connection.close()
        trace.link("forget", connection.address)
    }

    /** One peer's GATT connection; its callbacks come on the OS's binder threads. */
    private inner class Connection(private val device: BluetoothDevice, val address: String) : BluetoothGattCallback() {
        private var gatt: BluetoothGatt? = null
        private var characteristic: BluetoothGattCharacteristic? = null
        private var closed = false

        /** The operation's name is the trace's action when it fails: `read`, `subscribed`, `wrote`, `read_rssi`. */
        private val operations = LinkOperations(refused = { name -> trace.link(name, address, error = "not started") })

        fun connect(again: Boolean) {
            synchronized(lock) { if (closed || !running) return }
            trace.link(if (again) "reconnect" else "connect", address)
            val opened = try {
                device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
            } catch (e: SecurityException) {
                trace.link("disconnected", address, error = e.message ?: "no permission")
                null
            }
            if (opened == null) {
                reconnectLater()
                return
            }
            val late = synchronized(lock) {
                if (closed) {
                    true
                } else {
                    gatt = opened
                    false
                }
            }
            if (late) runCatching { opened.close() }
        }

        fun close() {
            val open = synchronized(lock) {
                closed = true
                operations.clear()
                characteristic = null
                gatt.also { gatt = null }
            }
            runCatching {
                open?.disconnect()
                open?.close()
            }
        }

        fun write(bytes: ByteArray) {
            synchronized(lock) {
                val gatt = gatt ?: return
                val characteristic = characteristic ?: return
                operations.add("wrote", AndroidLink.now()) { writeCharacteristic(gatt, characteristic, bytes) }
            }
        }

        fun readRssi() {
            synchronized(lock) {
                val gatt = gatt ?: return
                if (characteristic == null) return
                operations.add("read_rssi", AndroidLink.now()) { gatt.readRemoteRssi() }
            }
        }

        fun tick() {
            val timedOut = synchronized(lock) { operations.tick(AndroidLink.now()) } ?: return
            trace.link(timedOut, address, error = "timeout")
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    synchronized(lock) { peers.connected(address, AndroidLink.now()) }
                    trace.link("connected", address)
                    // 4 bytes fit the default MTU: no MTU request.
                    if (!gatt.discoverServices()) {
                        trace.link("services", address, error = "discoverServices refused")
                        gatt.disconnect()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    runCatching { gatt.close() }
                    val wasClosed = synchronized(lock) {
                        if (this.gatt === gatt) this.gatt = null
                        characteristic = null
                        operations.clear()
                        peers.disconnected(address, AndroidLink.now())
                        closed
                    }
                    if (wasClosed) return
                    trace.link("disconnected", address, error = AndroidLink.statusError(status))
                    reconnectLater()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val found = gatt.getService(AndroidLink.SERVICE)?.getCharacteristic(AndroidLink.TOKEN)
            if (status != BluetoothGatt.GATT_SUCCESS || found == null) {
                trace.link("services", address, error = AndroidLink.statusError(status) ?: "no link service")
                forget(this)
                return
            }
            trace.link("services", address)
            synchronized(lock) {
                if (closed) return
                characteristic = found
                val now = AndroidLink.now()
                operations.add("read", now) { gatt.readCharacteristic(found) }
                operations.add("subscribed", now) { subscribe(gatt, found) }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            read(value, status)
        }

        /** Before Android 13 the value is on the characteristic; from 13 on the one above is called instead. */
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) read(characteristic.value, status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            notified(value)
        }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) notified(characteristic.value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            synchronized(lock) { operations.done(AndroidLink.now()) }
            trace.link("wrote", address, error = AndroidLink.statusError(status))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            synchronized(lock) { operations.done(AndroidLink.now()) }
            trace.link("subscribed", address, error = AndroidLink.statusError(status))
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            val token = synchronized(lock) {
                operations.done(AndroidLink.now())
                peers.tokenOf(address)
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                trace.link("read_rssi", address, error = AndroidLink.statusError(status))
                return
            }
            emit(LinkReading(address, token, rssi, AndroidLink.now(), AndroidLink.API))
        }

        private fun read(value: ByteArray?, status: Int) {
            synchronized(lock) { operations.done(AndroidLink.now()) }
            val token = GattLinkRules.tokenOf(value)
            if (status != BluetoothGatt.GATT_SUCCESS || token == null) {
                trace.link("read", address, error = AndroidLink.statusError(status) ?: "not a token")
                return
            }
            tokenRead(address, token)
            emit(LinkReading(address, token, rssi = null, AndroidLink.now(), AndroidLink.API))
        }

        private fun notified(value: ByteArray?) {
            val token = GattLinkRules.tokenOf(value) ?: return
            tokenRead(address, token)
            trace.link("notified", address, token)
            emit(LinkReading(address, token, rssi = null, AndroidLink.now(), AndroidLink.API))
        }

        private fun reconnectLater() {
            scope.launch {
                delay(GattLinkRules.RECONNECT_MILLIS)
                val still = synchronized(lock) { running && !closed && connections[address] === this@Connection }
                if (still) connect(again = true)
            }
        }
    }

    /** Notifications on: the local switch, then the CCC descriptor on the peer (its callback ends the operation). */
    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val ccc = characteristic.getDescriptor(AndroidLink.CCC) ?: return false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(ccc, value) == BLUETOOTH_SUCCESS
        } else {
            writeDescriptorBefore33(gatt, ccc, value)
        }
    }

    /**
     * With a response (`WRITE_TYPE_DEFAULT`): its callback ends the operation and says whether the peer took it. A
     * write of either kind wakes a peer's iOS app in the background.
     */
    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
            BLUETOOTH_SUCCESS
    } else {
        writeCharacteristicBefore33(gatt, characteristic, bytes)
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptorBefore33(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray) =
        descriptor.setValue(value) && gatt.writeDescriptor(descriptor)

    @Suppress("DEPRECATION")
    private fun writeCharacteristicBefore33(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean {
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return characteristic.setValue(bytes) && gatt.writeCharacteristic(characteristic)
    }

    private companion object {
        /** `BluetoothStatusCodes.SUCCESS` (API 31), inlined. */
        const val BLUETOOTH_SUCCESS = 0

        val FILTER: ScanFilter = ScanFilter.Builder().setServiceUuid(ParcelUuid(AndroidLink.SERVICE)).build()

        val SETTINGS: ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
    }
}
