package app.hovanki.radar.link

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.hovanki.radar.RADAR_PERMISSIONS
import app.hovanki.radar.RadarService
import app.hovanki.radar.RadioApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * `gatt.link` on Android ([GattLink]; docs/radar-run.md §5.2) on `android.bluetooth`: a GATT server with the link
 * service and its own connectable advertisement next to the game's (an advertiser runs several sets) in
 * [AndroidLinkServer], and a scan for the service with a GATT client per peer in [AndroidLinkClient]. The OS calls
 * both back on its binder threads: each side locks its own state; the loops (writes, RSSI, reconnects) run in the
 * collector's scope.
 *
 * The RSSI is read on the client side only: `BluetoothGattServer` has no way to read it for a peer connected to it.
 * The permissions are the radar's ([RADAR_PERMISSIONS]: connecting needs `BLUETOOTH_CONNECT`); without them, or
 * with Bluetooth off, [run] traces `failed` and emits nothing.
 */
class AndroidGattLink(private val context: Context) : GattLink {
    private val manager: BluetoothManager? = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    override val isSupported: Boolean
        get() = manager?.adapter != null &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    override fun run(token: StateFlow<String?>, trace: LinkTrace): Flow<LinkReading> = callbackFlow {
        val manager = manager
        val adapter = manager?.adapter
        val problem = when {
            manager == null || adapter == null || !isSupported -> "no Bluetooth LE"
            !hasPermissions() -> "no permission"
            adapter?.isEnabled != true -> "bluetooth off"
            else -> null
        }
        if (manager == null || adapter == null || problem != null) {
            trace.link("failed", error = problem)
            awaitClose()
            return@callbackFlow
        }
        val emit = { reading: LinkReading ->
            trySend(reading)
            Unit
        }
        val server = AndroidLinkServer(context, manager, adapter, trace, emit)
        val client = AndroidLinkClient(context, adapter, trace, emit, this)
        server.start(token.value)
        client.start()
        // A new token: the subscribed peers hear it at once; the ones this phone connected to at the next write.
        val tokenJob = token.onEach(server::tokenChanged).launchIn(this)
        val writes = launch {
            while (isActive) {
                delay(GattLinkRules.WRITE_MILLIS)
                token.value?.let(client::writeAll)
            }
        }
        val rssi = launch {
            while (isActive) {
                delay(GattLinkRules.RSSI_MILLIS)
                client.readRssiAll()
            }
        }
        awaitClose {
            tokenJob.cancel()
            writes.cancel()
            rssi.cancel()
            client.stop()
            server.stop()
            trace.link("stop")
        }
    }

    private fun hasPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RADAR_PERMISSIONS.toList()
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
}

/** What both sides of the Android link share. */
internal object AndroidLink {
    val SERVICE: UUID = UUID.fromString(RadarService.LINK_UUID)
    val TOKEN: UUID = UUID.fromString(RadarService.LINK_TOKEN_UUID)

    /** The Client Characteristic Configuration descriptor: a client writes it to turn notifications on. */
    val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val API = RadioApi.ANDROID_LE

    fun now(): Long = System.currentTimeMillis()

    /** The characteristic's value for [token]; empty without one. */
    fun bytesOf(token: String?): ByteArray =
        token?.let { runCatching { GattLinkRules.tokenBytes(it) }.getOrNull() } ?: ByteArray(0)

    /** A GATT status as the trace's error; null for success. */
    fun statusError(status: Int): String? = if (status == 0) null else "status $status"
}
