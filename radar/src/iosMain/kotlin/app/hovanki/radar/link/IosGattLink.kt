@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.hovanki.radar.link

import app.hovanki.radar.RadarService
import app.hovanki.radar.RadioApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.posix.memcpy

/**
 * `gatt.link` on an iPhone ([GattLink]; docs/radar-run.md §5.2) on CoreBluetooth: the link service in a
 * `CBPeripheralManager`, advertised (in the background iOS moves the service UUID into the overflow area, which is
 * what the run measures) in [IosLinkServer]; a `CBCentralManager` scanning for it and connecting to every peer in
 * [IosLinkClient], with a delegate per peer ([IosPeerLink]).
 *
 * Everything runs on the main thread: the flow is collected there and the managers call their delegates on the main
 * queue. The managers and the peripherals hold their delegates only weakly: the run keeps every one referenced until
 * it ends. The loops (writes every [GattLinkRules.WRITE_MILLIS], RSSI every [GattLinkRules.RSSI_MILLIS]) are
 * coroutines on the main thread: while iOS keeps the app suspended in the background they wait, and they run again
 * whenever something wakes it (a write or a notification from a peer, the audio mode). A dropped link is asked for
 * again at once: iOS keeps a connection request pending without a timeout, which is what a reconnect in the
 * background needs (a timer would not fire there).
 *
 * The RSSI is read on the client side only: `CBPeripheralManager` can't read the signal of a central connected to
 * it. The OS's ids are `identifier.UUIDString` of the peripheral (client side) or the central (server side).
 *
 * Written without an iOS build at hand: the first run on a device is part of the spike (docs/radar-run.md §5.4).
 */
class IosGattLink : GattLink {
    /** Every iPhone the app runs on has Bluetooth LE; whether it is on and allowed the managers say in [run]. */
    override val isSupported: Boolean = true

    override fun run(token: StateFlow<String?>, trace: LinkTrace): Flow<LinkReading> = callbackFlow {
        val emit = { reading: LinkReading ->
            trySend(reading)
            Unit
        }
        val server = IosLinkServer(token.value, trace, emit)
        val client = IosLinkClient(trace, emit, this)
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
            client.close()
            server.close()
            trace.link("stop")
        }
    }.flowOn(Dispatchers.Main)
}

/** What both sides of the iOS link share. */
internal object IosLink {
    val SERVICE: CBUUID = CBUUID.UUIDWithString(RadarService.LINK_UUID)
    val TOKEN: CBUUID = CBUUID.UUIDWithString(RadarService.LINK_TOKEN_UUID)
    val API = RadioApi.COREBLUETOOTH

    fun now(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

    /** The characteristic's value for [token]; empty without one. */
    fun bytesOf(token: String?): ByteArray =
        token?.let { runCatching { GattLinkRules.tokenBytes(it) }.getOrNull() } ?: ByteArray(0)

    fun errorOf(error: NSError?): String? = error?.localizedDescription
}

internal fun ByteArray.toNSData(): NSData = if (isEmpty()) {
    NSData()
} else {
    usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
}

internal fun NSData.toByteArray(): ByteArray {
    val bytes = ByteArray(length.toInt())
    if (bytes.isNotEmpty()) {
        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), this.bytes, length) }
    }
    return bytes
}
