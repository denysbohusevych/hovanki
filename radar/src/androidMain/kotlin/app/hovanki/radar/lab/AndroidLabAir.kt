package app.hovanki.radar.lab

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import app.hovanki.radar.AndroidProximityRadio
import app.hovanki.radar.RadioApi
import app.hovanki.radar.SightingVia
import app.hovanki.shared.rules.AppleData
import app.hovanki.shared.rules.OverflowArea
import app.hovanki.shared.rules.RadarToken
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * «Listen to everything» on Android (docs/radio-lab.md §5): every frame with Apple's manufacturer data, the overflow
 * masks (`0x01` + 16 bytes, raw) and the game's iBeacon frames. A filter by company id alone keeps the scan alive with
 * the screen off. Android can't advertise an overflow area: no probe here. Debug builds only.
 */
class AndroidLabAir(context: Context) : LabAir {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override val canListen: Boolean get() = adapter?.bluetoothLeScanner != null
    override val canProbe: Boolean = false

    @SuppressLint("MissingPermission")
    override fun listen(): Flow<AirFrame> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("no Bluetooth LE scanner"))
            return@callbackFlow
        }
        val serviceHex = AndroidProximityRadio.SERVICE_UUID.toString().replace("-", "")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getManufacturerSpecificData(APPLE_COMPANY_ID) ?: return
                val ageMillis = (SystemClock.elapsedRealtimeNanos() - result.timestampNanos) / 1_000_000
                val atMillis = System.currentTimeMillis() - ageMillis.coerceAtLeast(0)
                val peer = result.device?.address
                AppleData.overflowMask(data)?.let { mask ->
                    val hex = mask.joinToString("") { "%02x".format(it) }
                    trySend(AirFrame.Mask(OverflowArea.bitsOf(mask), hex, result.rssi, peer, atMillis, API))
                }
                AppleData.iBeacon(data)?.takeIf { it.uuidHex == serviceHex }?.let { beacon ->
                    val token = RadarToken.fromMajorMinor(beacon.major, beacon.minor)
                    trySend(AirFrame.Token(token, SightingVia.IBEACON, result.rssi, peer, atMillis, API))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("scan failed: $errorCode"))
            }
        }
        val filters = listOf(ScanFilter.Builder().setManufacturerData(APPLE_COMPANY_ID, ByteArray(0)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        try {
            scanner.startScan(filters, settings, callback)
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        }
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    private companion object {
        const val APPLE_COMPANY_ID = 0x004C
        val API = RadioApi.ANDROID_LE
    }
}
