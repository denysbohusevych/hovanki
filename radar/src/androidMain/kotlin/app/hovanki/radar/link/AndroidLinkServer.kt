package app.hovanki.radar.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid

/**
 * The server side of the Android link: the link service in a `BluetoothGattServer` ([AndroidLink.TOKEN]: read,
 * write, notify, with the CCC descriptor a client writes to subscribe), advertised connectable once the OS added it.
 * Reads get the current token; a token change is notified to every subscribed peer; a peer's write of its token is
 * a reading (`notified`). The OS calls back on its binder threads: the state is under [lock].
 *
 * Android's server callback also reports the connections this phone opened as a client (one stack, one list): a
 * `central_connected` may be this phone's own link to that peer.
 */
@SuppressLint("MissingPermission")
internal class AndroidLinkServer(
    private val context: Context,
    private val manager: BluetoothManager,
    private val adapter: BluetoothAdapter,
    private val trace: LinkTrace,
    private val emit: (LinkReading) -> Unit,
) {
    private val lock = Any()

    @Volatile private var server: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null

    /** The peers subscribed to the token's notifications, by address. */
    private val subscribers = mutableMapOf<String, BluetoothDevice>()

    @Volatile private var value: ByteArray = ByteArray(0)
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertisement: AdvertiseCallback? = null
    private var stopped = false

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> trace.link("central_connected", address)

                BluetoothProfile.STATE_DISCONNECTED -> {
                    synchronized(lock) { subscribers.remove(address) }
                    trace.link("central_disconnected", address, error = AndroidLink.statusError(status))
                }
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                trace.link("server_failed", error = AndroidLink.statusError(status))
                return
            }
            trace.link("server_start")
            advertise()
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val server = server ?: return
            val bytes = value
            when {
                characteristic.uuid != AndroidLink.TOKEN ->
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, ByteArray(0))

                offset > bytes.size ->
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, ByteArray(0))

                else -> server.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    bytes.copyOfRange(offset, bytes.size),
                )
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val token = if (characteristic.uuid == AndroidLink.TOKEN && !preparedWrite && offset == 0) {
                GattLinkRules.tokenOf(value)
            } else {
                null
            }
            if (responseNeeded) {
                val status = if (token != null) {
                    BluetoothGatt.GATT_SUCCESS
                } else {
                    BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                }
                server?.sendResponse(device, requestId, status, 0, ByteArray(0))
            }
            if (token == null) return
            trace.link("notified", device.address, token)
            emit(LinkReading(device.address, token, rssi = null, AndroidLink.now(), AndroidLink.API))
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val subscribed = synchronized(lock) { device.address in subscribers }
            val bytes = if (subscribed) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, bytes)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val isCcc = descriptor.uuid == AndroidLink.CCC
            if (responseNeeded) {
                val status = if (isCcc) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                server?.sendResponse(device, requestId, status, 0, ByteArray(0))
            }
            if (!isCcc) return
            val on = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            synchronized(lock) {
                if (on) subscribers[device.address] = device else subscribers.remove(device.address)
            }
            trace.link(if (on) "central_subscribed" else "central_unsubscribed", device.address)
        }
    }

    /** Opens the GATT server and adds the service; the advertisement starts when the OS says it is added. */
    fun start(token: String?) {
        value = AndroidLink.bytesOf(token)
        val gattServer = try {
            manager.openGattServer(context, callback)
        } catch (e: SecurityException) {
            trace.link("server_failed", error = e.message ?: "no permission")
            return
        }
        if (gattServer == null) {
            trace.link("server_failed", error = "no GATT server")
            return
        }
        val characteristic = BluetoothGattCharacteristic(
            AndroidLink.TOKEN,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        characteristic.addDescriptor(
            BluetoothGattDescriptor(
                AndroidLink.CCC,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        val service = BluetoothGattService(AndroidLink.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        synchronized(lock) {
            server = gattServer
            this.characteristic = characteristic
        }
        if (!gattServer.addService(service)) trace.link("server_failed", error = "addService refused")
    }

    /** The token changed: reads get it, and every subscribed peer is notified. */
    fun tokenChanged(token: String?) {
        val bytes = AndroidLink.bytesOf(token)
        if (bytes.contentEquals(value)) return
        value = bytes
        if (bytes.isEmpty()) return
        val (server, characteristic, devices) = synchronized(lock) {
            Triple(server, characteristic, subscribers.values.toList())
        }
        if (server == null || characteristic == null) return
        for (device in devices) {
            val sent = runCatching { notify(server, device, characteristic, bytes) }.getOrDefault(false)
            if (!sent) trace.link("failed", device.address, error = "notify refused")
        }
    }

    fun stop() {
        val (server, advertiser, advertisement) = synchronized(lock) {
            stopped = true
            subscribers.clear()
            Triple(server, advertiser, advertisement).also {
                this.server = null
                this.advertiser = null
                this.advertisement = null
            }
        }
        if (advertisement != null) runCatching { advertiser?.stopAdvertising(advertisement) }
        runCatching {
            server?.clearServices()
            server?.close()
        }
    }

    private fun advertise() {
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            trace.link("advertise_failed", error = "no advertiser")
            return
        }
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                trace.link("advertise_start")
            }

            override fun onStartFailure(errorCode: Int) {
                trace.link("advertise_failed", error = "code $errorCode")
            }
        }
        synchronized(lock) {
            if (stopped) return
            this.advertiser = advertiser
            advertisement = callback
        }
        try {
            advertiser.startAdvertising(SETTINGS, DATA, callback)
        } catch (e: SecurityException) {
            trace.link("advertise_failed", error = e.message ?: "no permission")
        } catch (e: IllegalStateException) {
            trace.link("advertise_failed", error = e.message ?: "adapter off")
        }
    }

    /** Android 13 takes the value with the call; before, the characteristic carries it. */
    private fun notify(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        server.notifyCharacteristicChanged(device, characteristic, false, bytes) == BLUETOOTH_SUCCESS
    } else {
        notifyBefore33(server, device, characteristic, bytes)
    }

    @Suppress("DEPRECATION")
    private fun notifyBefore33(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean {
        characteristic.setValue(bytes)
        return server.notifyCharacteristicChanged(device, characteristic, false)
    }

    private companion object {
        /** `BluetoothStatusCodes.SUCCESS` (API 31), inlined: the class is read only on Android 13+ anyway. */
        const val BLUETOOTH_SUCCESS = 0

        /** Connectable: the whole point; the name stays off (18 bytes of UUID + flags fit the 31). */
        val SETTINGS: AdvertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val DATA: AdvertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(AndroidLink.SERVICE))
            .build()
    }
}
