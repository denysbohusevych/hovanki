package app.hovanki.radar

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
import android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The radar over Bluetooth LE on Android (docs/adr/0012-nearby-radar.md, section 2.2), the Android host of the radar's
 * channels (docs/adr/0017-radar-techniques-and-big-run.md, section 2.2): one legacy advertisement joined from the
 * channels' parts ([RadarCatalog.advert], at most 31 bytes and a scan response), one scan with a filter per channel's
 * interest, and every result read by every channel ([AirDecoder]).
 *
 * A hider's phone advertises its token in one of the service data channel's layouts: `.scan_response` (the game's
 * UUID in the packet, the token in the scan response); with a journal (the field build, the lab) the three take turns
 * by the player's number ([RadarCatalog.hiderLayout]). The first apps put the UUID and the token in one packet: 40
 * bytes, which Android refused (`ADVERTISE_FAILED_DATA_TOO_LARGE`), so nobody ever heard an Android hider. A seeker's
 * phone advertises an iBeacon frame (Apple's manufacturer data) with the token as major and minor, which an iPhone in
 * a pocket hears through CoreLocation («Пульс»). The scan hears all three layouts, an iPhone hider's name and the
 * seekers' iBeacons; with a journal also a locked iPhone's overflow mask (`ble.overflow`, in the shadow: its token
 * goes into the journal only, never to the game).
 *
 * Runs while [run] is collected: every reading of a phone with a well-formed token goes out with the signal strength.
 * [state] follows the adapter and the permissions. The journal is looked at once a second: when it starts or stops,
 * the scan and the advertisement start anew with or without the shadow.
 */
class AndroidProximityRadio(private val context: Context, private val trace: RadioTrace = RadioTrace.None) :
    ProximityRadio {
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
    override fun run(tokens: StateFlow<String?>, asSeeker: Boolean, options: RadioOptions): Flow<RadioSighting> =
        callbackFlow {
            refresh()
            if (mutableState.value != BluetoothState.ON) {
                awaitClose()
                return@callbackFlow
            }
            val adapter = checkNotNull(adapter)
            val scanner: BluetoothLeScanner? = adapter.bluetoothLeScanner
            val advertiser: BluetoothLeAdvertiser? = adapter.bluetoothLeAdvertiser
            val role = if (asSeeker) AirRole.SEEKER else AirRole.HIDER
            val mode = if (asSeeker) "ibeacon" else "hider_service_data"
            val decoder = AirDecoder(trace)
            var shadow = trace.isListening

            val scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val record = result.scanRecord ?: return
                    // The reading's time in device-clock terms, from the monotonic stamp.
                    val ageMillis = (SystemClock.elapsedRealtimeNanos() - result.timestampNanos) / 1_000_000
                    val atMillis = System.currentTimeMillis() - ageMillis.coerceAtLeast(0)
                    for (sighting in decoder.decode(frameOf(record, result, atMillis))) trySend(sighting)
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "Scan failed: $errorCode")
                    trace.scan("failed", RadioApi.ANDROID_LE, error = "code $errorCode")
                }
            }
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()
            var scanning = false
            fun startScan() {
                val interests = RadarCatalog.interests(AirPlatform.ANDROID, shadow)
                val filters = interests.mapNotNull(::filterOf)
                scanner?.startScan(filters, scanSettings, scanCallback)
                scanning = scanner != null
                trace.scan("start", RadioApi.ANDROID_LE, describe(interests))
            }

            fun stopScan() {
                if (!scanning) return
                scanner?.stopScan(scanCallback)
                scanning = false
                trace.scan("stop", RadioApi.ANDROID_LE)
            }

            val advertiseCallback = object : AdvertiseCallback() {
                override fun onStartFailure(errorCode: Int) {
                    Log.w(TAG, "Advertising failed: $errorCode")
                    trace.advertise("failed", mode, tokens.value, "code $errorCode")
                }
            }
            var advertising = false
            fun advertise(token: String?) {
                if (advertising) {
                    advertiser?.stopAdvertising(advertiseCallback)
                    advertising = false
                    trace.advertise("stop", mode, null)
                }
                if (token == null || advertiser == null) return
                val advert = RadarCatalog.advert(token, role, AirPlatform.ANDROID, options, shadow)
                if (advert.isEmpty) {
                    trace.advertise("failed", mode, token, "nothing fits", advert.report())
                    return
                }
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(false)
                    .build()
                val main = advertiseData(advert.main)
                if (advert.scanResponse.isEmpty) {
                    advertiser.startAdvertising(settings, main, advertiseCallback)
                } else {
                    advertiser.startAdvertising(settings, main, advertiseData(advert.scanResponse), advertiseCallback)
                }
                advertising = true
                trace.advertise("start", mode, token, report = advert.report())
            }

            startScan()
            // The token changes every few minutes: advertise the current one.
            val tokenJob = tokens.onEach { advertise(it) }.launchIn(this)
            // The air's counts once a second, and the journal followed: the shadow comes and goes with it.
            val shadowJob = launch {
                while (isActive) {
                    delay(SHADOW_CHECK_MILLIS)
                    decoder.flush(System.currentTimeMillis())
                    val listening = trace.isListening
                    if (listening != shadow) {
                        shadow = listening
                        stopScan()
                        startScan()
                        advertise(tokens.value)
                    }
                }
            }
            awaitClose {
                tokenJob.cancel()
                shadowJob.cancel()
                if (advertising) {
                    advertiser?.stopAdvertising(advertiseCallback)
                    trace.advertise("stop", mode, null)
                }
                stopScan()
                decoder.flush(System.currentTimeMillis(), force = true)
            }
        }

    /** What the scan record shows, for the channels: the packet and the scan response in one, as Android gives them. */
    private fun frameOf(record: ScanRecord, result: ScanResult, atMillis: Long): HeardFrame {
        val manufacturer = record.manufacturerSpecificData
        return HeardFrame(
            rssi = result.rssi,
            atMillis = atMillis,
            api = RadioApi.ANDROID_LE,
            peer = result.device?.address,
            localName = record.deviceName,
            serviceUuids = record.serviceUuids.orEmpty().map { BleUuid.canonical(it.uuid.toString()) },
            serviceData = record.serviceData.orEmpty().entries.associate { (uuid, data) ->
                BleUuid.canonical(uuid.uuid.toString()) to AirHex.of(data)
            },
            manufacturerData = buildMap {
                if (manufacturer != null) {
                    for (i in 0 until manufacturer.size()) {
                        put(
                            manufacturer.keyAt(i),
                            AirHex.of(manufacturer.valueAt(i)),
                        )
                    }
                }
            },
            txPower = record.txPowerLevel.takeIf { it != Int.MIN_VALUE },
            connectable = result.isConnectable,
            // The record's bytes only for the journal: the packet and the scan response, the zeros after them cut.
            rawHex = if (trace.isListening) record.bytes?.let(AirHex::recordFields)?.let(AirHex::of) else null,
        )
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

    companion object {
        private const val TAG = "ProximityRadio"

        /** The journal is looked at this often; the air's counts go out as often. */
        private const val SHADOW_CHECK_MILLIS = 1_000L

        /** The game's own 128-bit UUID: the service of a hider's frame and the proximity UUID of a seeker's iBeacon. */
        val SERVICE_UUID: UUID = UUID.fromString(GameAir.SERVICE_UUID)

        /** The scan filter of a channel's interest; null: not one Android filters by (iOS's own). */
        internal fun filterOf(interest: ScanInterest): ScanFilter? = when (interest) {
            is ScanInterest.ServiceUuid -> ScanFilter.Builder().setServiceUuid(
                ParcelUuid.fromString(interest.uuid),
            ).build()

            // Any data of the service: an empty prefix matches whenever the service's data is there.
            is ScanInterest.ServiceData ->
                ScanFilter.Builder().setServiceData(ParcelUuid.fromString(interest.uuid), ByteArray(0)).build()

            is ScanInterest.Manufacturer -> {
                val prefix = AirHex.bytes(interest.prefixHex)
                ScanFilter.Builder()
                    .setManufacturerData(interest.companyId, prefix, ByteArray(prefix.size) { 0xFF.toByte() })
                    .build()
            }

            is ScanInterest.OverflowUuids, is ScanInterest.IBeaconRanging, is ScanInterest.IBeaconRegion -> null
        }

        /** The advertisement's data as Android takes it. No name: Android can't advertise a token as its name. */
        internal fun advertiseData(data: AdData): AdvertiseData = AdvertiseData.Builder().apply {
            for (uuid in data.serviceUuids) addServiceUuid(ParcelUuid.fromString(uuid))
            for ((uuid, hex) in data.serviceData) addServiceData(ParcelUuid.fromString(uuid), AirHex.bytes(hex))
            for ((company, hex) in data.manufacturerData) addManufacturerData(company, AirHex.bytes(hex))
            setIncludeDeviceName(false)
            setIncludeTxPowerLevel(data.includeTxPower)
        }.build()

        /** The scan's filters in words, for the journal's `scan`. */
        private fun describe(interests: List<ScanInterest>): String = interests.joinToString(", ") { interest ->
            when (interest) {
                is ScanInterest.ServiceUuid -> "service ${interest.uuid.take(8)}"

                is ScanInterest.ServiceData -> "service data ${interest.uuid.take(8)}"

                is ScanInterest.Manufacturer -> "manufacturer %04x %s".format(
                    interest.companyId,
                    interest.prefixHex.take(8),
                )

                is ScanInterest.OverflowUuids -> "overflow"

                is ScanInterest.IBeaconRanging -> "ranging"

                is ScanInterest.IBeaconRegion -> "region"
            }
        }
    }
}

/**
 * Android 12+: scanning («never for location»: the manifest says so) and advertising; connecting for the name. Empty
 * before Android 12, where Bluetooth LE needs only the location permission. The radio checks them; the app asks for
 * them (`rememberBluetoothPermissionRequester` in `:composeApp`).
 */
val RADAR_PERMISSIONS: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
    )
} else {
    emptyArray()
}
