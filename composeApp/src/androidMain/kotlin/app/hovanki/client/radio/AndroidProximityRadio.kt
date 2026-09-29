package app.hovanki.client.radio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.rules.RadarToken
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.nio.ByteBuffer
import java.util.UUID

/**
 * The radar over Bluetooth LE on Android (docs/adr/0012-nearby-radar.md, section 2.2). A hider's phone advertises
 * the radar token as the service data of the game's service UUID; a seeker's advertises an iBeacon frame (Apple's
 * manufacturer data) with the token as major and minor, which an iPhone in a pocket hears through CoreLocation
 * («Пульс»). Every phone scans for both, and for an iPhone hider on the screen, whose token is in its name.
 *
 * An iPhone in the background advertises the service UUID in an overflow area instead of its data, so this phone
 * would have to connect to it and read the token from a characteristic; that part is left for the spike on real
 * phones (the ADR's open question) and is not done yet: such iPhones are heard, not identified. It matters little:
 * the iPhone in the pocket hears this phone's frame and reports it itself.
 *
 * Runs while [run] is collected: every reading of a phone with a well-formed token goes out with the signal strength.
 * [state] follows the adapter and the permissions.
 */
class AndroidProximityRadio(private val context: Context) : ProximityRadio {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val mutableState = MutableStateFlow(currentState())
    override val state: StateFlow<BluetoothState> = mutableState

    init {
        // The adapter switched on or off: the state follows.
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    mutableState.value = currentState()
                }
            },
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
        )
    }

    override fun refresh() {
        mutableState.value = currentState()
    }

    @SuppressLint("MissingPermission")
    override fun run(tokens: StateFlow<String?>, asSeeker: Boolean): Flow<RadioSighting> = callbackFlow {
        refresh()
        if (mutableState.value != BluetoothState.ON) {
            awaitClose()
            return@callbackFlow
        }
        val adapter = checkNotNull(adapter)
        val scanner: BluetoothLeScanner? = adapter.bluetoothLeScanner
        val advertiser: BluetoothLeAdvertiser? = adapter.bluetoothLeAdvertiser
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val token = result.scanRecord?.let(::tokenOf) ?: return
                if (!RadarToken.isWellFormed(token)) return
                // The reading's time in device-clock terms, from the monotonic stamp.
                val ageMillis = (SystemClock.elapsedRealtimeNanos() - result.timestampNanos) / 1_000_000
                trySend(RadioSighting(token, result.rssi, System.currentTimeMillis() - ageMillis.coerceAtLeast(0)))
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Scan failed: $errorCode")
            }
        }
        val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "Advertising failed: $errorCode")
            }
        }
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(SERVICE_PARCEL).build(),
            ScanFilter.Builder().setManufacturerData(APPLE_COMPANY_ID, BEACON_PREFIX, BEACON_PREFIX_MASK).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        scanner?.startScan(filters, settings, scanCallback)

        var advertising = false
        fun advertise(token: String?) {
            if (advertising) {
                advertiser?.stopAdvertising(advertiseCallback)
                advertising = false
            }
            if (token == null || advertiser == null) return
            val data = if (asSeeker) {
                AdvertiseData.Builder()
                    .addManufacturerData(APPLE_COMPANY_ID, beaconFrame(token))
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .build()
            } else {
                AdvertiseData.Builder()
                    .addServiceUuid(SERVICE_PARCEL)
                    .addServiceData(SERVICE_PARCEL, token.hexToBytes())
                    .setIncludeDeviceName(false)
                    .build()
            }
            val advertiseSettings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false)
                .build()
            advertiser.startAdvertising(advertiseSettings, data, advertiseCallback)
            advertising = true
        }
        // The token changes every few minutes: advertise the current one.
        val tokenJob = tokens.onEach { advertise(it) }.launchIn(this)
        awaitClose {
            tokenJob.cancel()
            if (advertising) advertiser?.stopAdvertising(advertiseCallback)
            scanner?.stopScan(scanCallback)
        }
    }

    /** The token in a scan record: a hider's service data, a seeker's iBeacon frame, or an iPhone hider's name. */
    private fun tokenOf(record: ScanRecord): String? {
        record.getServiceData(SERVICE_PARCEL)?.let { return it.toHex() }
        record.getManufacturerSpecificData(APPLE_COMPANY_ID)?.let { frame ->
            if (frame.size >= BEACON_FRAME_LENGTH - 1 &&
                frame.copyOf(BEACON_PREFIX.size).contentEquals(BEACON_PREFIX)
            ) {
                val buffer = ByteBuffer.wrap(frame, BEACON_PREFIX.size, 4)
                val major = buffer.short.toInt() and 0xFFFF
                val minor = buffer.short.toInt() and 0xFFFF
                return RadarToken.fromMajorMinor(major, minor)
            }
        }
        // An iPhone hider: the token as its name, bare (iOS keeps 8 characters next to the service) or after the first
        // apps' prefix. Only the game's service and iBeacon frames pass the scan filters.
        val name = record.deviceName ?: return null
        return name.removePrefix(NAME_PREFIX)
    }

    private fun currentState(): BluetoothState {
        val adapter = adapter ?: return BluetoothState.UNSUPPORTED
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return BluetoothState.UNSUPPORTED
        }
        if (!hasPermissions()) return BluetoothState.DENIED
        return if (adapter.isEnabled) BluetoothState.ON else BluetoothState.OFF
    }

    private fun hasPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RADAR_PERMISSIONS.toList()
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { i ->
        substring(2 * i, 2 * i + 2).toInt(16).toByte()
    }

    companion object {
        private const val TAG = "ProximityRadio"

        /** The game's own 128-bit UUID: the service of a hider's frame and the proximity UUID of a seeker's iBeacon. */
        val SERVICE_UUID: UUID = UUID.fromString("7a0b8d2e-4c1f-4e6a-9b3d-2f5e8c1a7d10")
        private val SERVICE_PARCEL = ParcelUuid(SERVICE_UUID)

        /** An iPhone hider on the screen carries its token in its name (iOS advertises no data). */
        const val NAME_PREFIX = "hv"

        /** Apple's company id in manufacturer data, where an iBeacon frame lives. */
        private const val APPLE_COMPANY_ID = 0x004C

        /** An iBeacon frame after the company id: type 2, length 21, the UUID, major, minor, the measured power. */
        private const val BEACON_FRAME_LENGTH = 23
        private val BEACON_PREFIX: ByteArray = ByteBuffer.allocate(2 + 16)
            .put(0x02).put(0x15)
            .putLong(SERVICE_UUID.mostSignificantBits).putLong(SERVICE_UUID.leastSignificantBits)
            .array()
        private val BEACON_PREFIX_MASK = ByteArray(BEACON_PREFIX.size) { 0xFF.toByte() }

        /** The signal at one metre, as iBeacons say it: -59 dBm, a typical phone. */
        private const val MEASURED_POWER: Byte = 0xC5.toByte()

        internal fun beaconFrame(token: String): ByteArray {
            val (major, minor) = RadarToken.toMajorMinor(token)
            return ByteBuffer.allocate(BEACON_FRAME_LENGTH)
                .put(BEACON_PREFIX)
                .putShort(major.toShort())
                .putShort(minor.toShort())
                .put(MEASURED_POWER)
                .array()
        }
    }
}
