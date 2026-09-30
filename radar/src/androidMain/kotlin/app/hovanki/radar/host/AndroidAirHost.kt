package app.hovanki.radar.host

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
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
import app.hovanki.radar.AdPart
import app.hovanki.radar.AdPlan
import app.hovanki.radar.AirFrame
import app.hovanki.radar.AirHost
import app.hovanki.radar.AirTally
import app.hovanki.radar.BleUuid
import app.hovanki.radar.IBeaconBytes
import app.hovanki.radar.RADAR_PERMISSIONS
import app.hovanki.radar.RadarCaps
import app.hovanki.radar.RadarChannel
import app.hovanki.radar.RadarRole
import app.hovanki.radar.RadarService
import app.hovanki.radar.RadarTrace
import app.hovanki.radar.RadioApi
import app.hovanki.radar.ScanInterest
import app.hovanki.radar.inWords
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Platform
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The radar's air on Android (docs/adr/0017-radar-techniques-and-big-run.md, section 2.2) on `android.bluetooth.le`:
 * one legacy advertisement assembled from the channels' parts ([AdPlan]: no local name, both packets within 31 bytes),
 * its scan response for the parts that ask for it, and one scan with the union of the channels' interests as filters.
 * Every frame heard goes out whole ([AirFrame], the raw record too), the channels read it. A filter per interest also
 * keeps the scan alive with the screen off, where Android stops an unfiltered one.
 *
 * An iPhone in the background puts its service UUIDs into Apple's overflow area; this phone hears it raw, as Apple's
 * manufacturer data (`0x01` + the mask), when a channel asks for it ([RadarCaps.canReadOverflow]). It can't
 * advertise one. [caps] follows the adapter and the permissions.
 */
class AndroidAirHost(private val context: Context) : AirHost {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val mutableCaps = MutableStateFlow(currentCaps())
    override val caps: StateFlow<RadarCaps> = mutableCaps

    init {
        // The adapter switched on or off: the caps follow.
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    refresh()
                }
            },
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
        )
    }

    override fun refresh() {
        mutableCaps.value = currentCaps()
    }

    @SuppressLint("MissingPermission")
    override fun run(
        channels: List<RadarChannel>,
        token: StateFlow<String?>,
        role: RadarRole,
        trace: RadarTrace,
    ): Flow<AirFrame> = callbackFlow {
        refresh()
        val bluetooth = adapter
        if (bluetooth == null || mutableCaps.value.bluetooth != BluetoothState.ON) {
            awaitClose()
            return@callbackFlow
        }
        val tally = AirTally(trace, channels)
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val frame = frameOf(result)
                // The scanner calls back on the main thread, the flush comes from the collector's.
                synchronized(tally) { tally.heard(frame) }
                trySend(frame)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Scan failed: $errorCode")
                trace.scan("failed", API, error = "code $errorCode")
            }
        }
        val interests = channels.flatMap { it.interests() }.distinct()
        val filters = interests.flatMap(::filtersOf)
        val scanner = bluetooth.bluetoothLeScanner
        val scanning = when {
            filters.isEmpty() -> false

            scanner == null -> {
                trace.scan("failed", API, interests.inWords(), error = "no scanner")
                false
            }

            else -> try {
                scanner.startScan(filters, SCAN_SETTINGS, scanCallback)
                trace.scan("start", API, interests.inWords())
                true
            } catch (e: SecurityException) {
                trace.scan("failed", API, interests.inWords(), error = e.message ?: "no permission")
                false
            } catch (e: IllegalStateException) {
                // The adapter went off between the check and the call.
                trace.scan("failed", API, interests.inWords(), error = e.message ?: "adapter off")
                false
            }
        }

        val advertiser: BluetoothLeAdvertiser? = bluetooth.bluetoothLeAdvertiser
        var current: Advertisement? = null

        fun advertise(next: String?) {
            current?.stop(advertiser)
            current = null
            if (next == null) return
            val plan = AdPlan.of(channels, next, role, Platform.ANDROID)
            if (plan.isEmpty) {
                // Nothing of these channels can go on the air here: only what was left out, and why.
                plan.traceDropped(trace, next)
                return
            }
            if (advertiser == null) {
                plan.trace(trace, "failed", next, error = "no advertiser")
                return
            }
            val advertisement = Advertisement(plan, next, trace)
            val parts = plan.adParts
            val scanResponse = if (parts.any { it is AdPart.ServiceData && it.inScanResponse }) {
                advertiseData(parts, scanResponse = true)
            } else {
                null
            }
            try {
                advertiser.startAdvertising(
                    ADVERTISE_SETTINGS,
                    advertiseData(parts, scanResponse = false),
                    scanResponse,
                    advertisement,
                )
                current = advertisement
            } catch (e: SecurityException) {
                plan.trace(trace, "failed", next, error = e.message ?: "no permission")
            } catch (e: IllegalStateException) {
                plan.trace(trace, "failed", next, error = e.message ?: "adapter off")
            }
        }

        // The token changes every few minutes: advertise the current one.
        val tokenJob = token.onEach { advertise(it) }.launchIn(this)
        awaitClose {
            tokenJob.cancel()
            current?.stop(advertiser)
            current = null
            if (scanning) {
                runCatching { scanner?.stopScan(scanCallback) }
                trace.scan("stop", API)
            }
            synchronized(tally) { tally.flush() }
        }
    }

    /**
     * One advertisement on the air and its callback (the key `stopAdvertising` wants): traced `start` when the OS
     * says it started, `failed` with its error code, `stop` when it had started. The OS calls back on the main
     * thread, the stop comes from the collector's.
     */
    private class Advertisement(private val plan: AdPlan, private val token: String, private val trace: RadarTrace) :
        AdvertiseCallback() {
        private var started = false
        private var stopped = false

        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            synchronized(this) {
                if (stopped) return
                started = true
                plan.trace(trace, "start", token)
            }
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Advertising failed: $errorCode")
            synchronized(this) {
                if (stopped) return
                plan.trace(trace, "failed", token, error = "code $errorCode")
            }
        }

        @SuppressLint("MissingPermission")
        fun stop(advertiser: BluetoothLeAdvertiser?) {
            synchronized(this) {
                if (stopped) return
                stopped = true
                runCatching { advertiser?.stopAdvertising(this) }
                if (started) plan.trace(trace, "stop", token)
            }
        }
    }

    /** The packet of [parts] ([scanResponse]: the scan response's, else the advertisement's). */
    private fun advertiseData(parts: List<AdPart>, scanResponse: Boolean): AdvertiseData {
        val builder = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
        for (part in parts) {
            when (part) {
                is AdPart.ServiceUuid -> if (!scanResponse) builder.addServiceUuid(parcelOf(part.uuid))

                is AdPart.ServiceData -> if (part.inScanResponse == scanResponse) {
                    builder.addServiceData(parcelOf(part.uuid), part.data)
                }

                is AdPart.ManufacturerData -> if (!scanResponse) builder.addManufacturerData(part.companyId, part.data)

                is AdPart.IBeacon -> if (!scanResponse) {
                    builder.addManufacturerData(RadarService.APPLE_COMPANY_ID, IBeaconBytes.of(part))
                }

                // AdPlan leaves the name out on Android: it can't name one advertisement without renaming the phone.
                is AdPart.LocalName -> Unit
            }
        }
        return builder.build()
    }

    /**
     * The scan filters of [interest]. A service: its UUID in the list, or as the key of service data (Android's
     * service UUID filter doesn't match a frame with the service data alone). CoreLocation's and the overflow
     * table's interests are iOS only: Android reads the overflow area raw through Apple's manufacturer data.
     */
    private fun filtersOf(interest: ScanInterest): List<ScanFilter> = when (interest) {
        is ScanInterest.Service -> {
            val uuid = parcelOf(interest.uuid)
            listOf(
                ScanFilter.Builder().setServiceUuid(uuid).build(),
                ScanFilter.Builder().setServiceData(uuid, ByteArray(0)).build(),
            )
        }

        is ScanInterest.Manufacturer -> {
            val builder = ScanFilter.Builder()
            if (interest.prefix.isEmpty()) {
                builder.setManufacturerData(interest.companyId, ByteArray(0))
            } else {
                val mask = ByteArray(interest.prefix.size) { 0xFF.toByte() }
                builder.setManufacturerData(interest.companyId, interest.prefix, mask)
            }
            listOf(builder.build())
        }

        is ScanInterest.BeaconRanging, is ScanInterest.BeaconRegion, is ScanInterest.OverflowUuids -> emptyList()
    }

    /** [result] whole, its time in device-clock terms from the monotonic stamp. */
    private fun frameOf(result: ScanResult): AirFrame {
        val record: ScanRecord? = result.scanRecord
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - result.timestampNanos) / 1_000_000
        val atMillis = System.currentTimeMillis() - ageMillis.coerceAtLeast(0)
        val manufacturerData = record?.manufacturerSpecificData?.let { data ->
            buildMap<Int, ByteArray> { for (i in 0 until data.size()) put(data.keyAt(i), data.valueAt(i)) }
        }
        return AirFrame(
            atMillis = atMillis,
            rssi = result.rssi,
            api = API,
            peer = result.device?.address,
            name = record?.deviceName,
            serviceUuids = record?.serviceUuids.orEmpty().map { BleUuid.normalize(it.toString()) },
            serviceData = record?.serviceData.orEmpty().entries.associate { (uuid, data) ->
                BleUuid.normalize(uuid.toString()) to data
            },
            manufacturerData = manufacturerData.orEmpty(),
            txPower = record?.txPowerLevel?.takeIf { it != Int.MIN_VALUE },
            connectable = result.isConnectable,
            raw = record?.bytes,
        )
    }

    @SuppressLint("MissingPermission")
    private fun currentCaps(): RadarCaps {
        val bluetooth = currentState()
        // The controller's features read only while the adapter is on (off, Android says false).
        val on = adapter?.takeIf { bluetooth == BluetoothState.ON }
        return RadarCaps(
            platform = Platform.ANDROID,
            bluetooth = bluetooth,
            // No API says how many sets; without multiple advertisement there is no advertiser at all.
            advertisingSets = on?.let { runCatching { it.isMultipleAdvertisementSupported }.getOrNull() }
                ?.let { multiple -> if (multiple) null else 0 },
            leCoded = on?.let { runCatching { it.isLeCodedPhySupported }.getOrNull() },
            canScanResponse = true,
            canRangeBeacons = false,
            canReadOverflow = true,
            canAdvertiseOverflow = false,
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

    private companion object {
        const val TAG = "AirHost"
        val API = RadioApi.ANDROID_LE

        val SCAN_SETTINGS: ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        val ADVERTISE_SETTINGS: AdvertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()

        /** [uuid] as Android wants it: 128-bit, a short one on the Bluetooth base UUID. */
        fun parcelOf(uuid: String): ParcelUuid {
            val normalized = BleUuid.normalize(uuid)
            val full = when (normalized.length) {
                4 -> "0000$normalized$BASE_SUFFIX"
                8 -> "$normalized$BASE_SUFFIX"
                else -> normalized
            }
            return ParcelUuid.fromString(full)
        }

        const val BASE_SUFFIX = "-0000-1000-8000-00805F9B34FB"
    }
}
