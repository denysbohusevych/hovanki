package app.hovanki.radar.link

import app.hovanki.radar.Availability
import app.hovanki.radar.RadarCaps
import app.hovanki.radar.RadarService
import app.hovanki.radar.RadioApi
import app.hovanki.radar.Technique
import app.hovanki.radar.TechniqueKind
import app.hovanki.radar.TechniqueStatus
import app.hovanki.shared.rules.RadarToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * `gatt.link` (docs/adr/0017-radar-techniques-and-big-run.md, section 2.3): one GATT connection per pair of phones
 * instead of advertisements. An advertisement of a locked iPhone is almost gone (only overflow bits, heard by an
 * iPhone on screen), but a connection the OS already holds keeps working in the background, and a write to it wakes
 * the peer's app: the radio lab measures whether that is enough for two iPhones in pockets (docs/radar-run.md §5.2).
 *
 * While [run] is collected, the phone does both roles at once:
 * - it hosts one service ([RadarService.LINK_UUID]) with one characteristic ([RadarService.LINK_TOKEN_UUID]: read,
 *   write, notify; its value is the current [token]'s 4 bytes, [GattLinkRules.tokenBytes]), advertises the service
 *   (an iPhone in the background: only as a bit of the overflow area, which is exactly what the run measures),
 *   answers reads with the current token, notifies the subscribed peers when it changes, and takes the peers' writes
 *   as readings;
 * - it scans for the service and connects to every peer found (at most [GattLinkRules.MAX_LINKS]; both phones of a
 *   pair may connect to each other, the lab's report reads both), reads the peer's token, subscribes to its
 *   notifications, writes its own token every [GattLinkRules.WRITE_MILLIS] (the write is what wakes the peer's
 *   app), reads the RSSI every [GattLinkRules.RSSI_MILLIS], and connects again [GattLinkRules.RECONNECT_MILLIS]
 *   after the OS dropped the link, for as long as it is collected.
 *
 * No pairing, no encryption: a lab, and the token is public on the air anyway. The game never runs it.
 */
interface GattLink {
    /** Whether the phone has Bluetooth LE at all; the adapter being on and the permissions are checked by [run]. */
    val isSupported: Boolean

    /**
     * Runs the link while collected (see the class), emitting a [LinkReading] for every token read, notified or
     * written to this phone, and for every RSSI read. [token] null: the characteristic reads empty and nothing is
     * written. Every step goes to [trace].
     */
    fun run(token: StateFlow<String?>, trace: LinkTrace = LinkTrace.None): Flow<LinkReading>
}

/**
 * One reading over a link: [peerToken] when a token came in (read, notified, or written by the peer), [rssi] (dBm)
 * when the RSSI was read (with the peer's last token, if any), at [atMillis] of the device's clock. [peer] is the
 * OS's id of the other phone (an address on Android, a CoreBluetooth identifier on iOS), never sent anywhere: the
 * caller hashes it.
 *
 * The RSSI comes only from the side that connected (the GATT client): neither Android's `BluetoothGattServer` nor
 * iOS's `CBPeripheralManager` can read the signal of a peer connected to them. A pair where only one phone
 * connected has the RSSI of one direction only.
 */
data class LinkReading(val peer: String, val peerToken: String?, val rssi: Int?, val atMillis: Long, val api: RadioApi)

/**
 * Every step of a [GattLink], for the radio lab's log (docs/radio-lab.md §4.1, the kind `link`): [None] unless the
 * lab listens. It never changes what the link does. [peer] is the OS's id of the other phone (the caller hashes it).
 *
 * [action]:
 * - `server_start`, `server_failed` ([error]): the link service added to this phone's GATT server, or not;
 * - `advertise_start`, `advertise_failed` ([error]): the link service's advertisement;
 * - `scan_start`, `scan_failed` ([error]): the scan for it;
 * - `connect` (a peer found, connecting), `connected`, `services` (the link characteristic found; [error] when not:
 *   the peer is then forgotten), `read` (only when reading the peer's token failed, [error]; a token read is a
 *   [LinkReading]), `subscribed` (its notifications on, or [error]), `wrote` (this phone's token
 *   written to the peer, or [error]), `notified` ([peerToken]: the peer's token pushed to this phone, as a
 *   notification or as the peer's write to this phone's service), `read_rssi` (only when it failed, [error]; a read
 *   RSSI is a [LinkReading]), `disconnected` ([error]: the OS's reason when there is one), `reconnect` (connecting
 *   again after a drop), `forget` (a silent peer given up to make room, or a peer without the service);
 * - `identifier_changed` ([peer]: the OLD id, [peerToken]: the token it had): another id read the token of this
 *   silent peer, so the OS gave the same phone a new id (iOS and Android rotate the address about every 15 minutes
 *   when not connected); the new id carries on with the same token, and the old one is forgotten;
 * - `central_connected`, `central_disconnected`, `central_subscribed`, `central_unsubscribed`: a peer connected to
 *   this phone's service (the server side);
 * - `failed` ([error]: no adapter, Bluetooth off, no permission), `stop`.
 */
interface LinkTrace {
    fun link(action: String, peer: String? = null, peerToken: String? = null, error: String? = null) = Unit

    companion object {
        val None: LinkTrace = object : LinkTrace {}
    }
}

/** The link's numbers (docs/radar-run.md §5.2); guesses until the run. */
object GattLinkRules {
    /** Connections this phone opens at most; the OS's limit is about 7–8 on both platforms, the run has fewer. */
    const val MAX_LINKS = 5

    /** How often a phone writes its token to every peer it connected to: the write wakes the peer's app. */
    const val WRITE_MILLIS = 5_000L

    /** How often the RSSI of every link is read. */
    const val RSSI_MILLIS = 3_000L

    /** How long after a drop the phone connects again. */
    const val RECONNECT_MILLIS = 2_000L

    /**
     * A peer silent (disconnected) this long gives its place to a new one when [MAX_LINKS] are taken: its id most
     * likely changed.
     */
    const val FORGET_MILLIS = 60_000L

    /** A GATT operation without an answer for this long is given up (Android runs one at a time per connection). */
    const val OPERATION_TIMEOUT_MILLIS = 10_000L

    private const val TOKEN_BYTES = RadarToken.LENGTH / 2

    /** The characteristic's value for [token]: its 4 bytes. A token that is not one ([RadarToken]) throws. */
    fun tokenBytes(token: String): ByteArray {
        require(RadarToken.isWellFormed(token)) { "not a radar token" }
        return ByteArray(TOKEN_BYTES) { token.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    /** The token in a characteristic's value; null unless it is exactly 4 bytes. */
    fun tokenOf(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size != TOKEN_BYTES) return null
        return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

/** A phone without a link (the JVM bots, a platform without an implementation). */
class NoopGattLink : GattLink {
    override val isSupported: Boolean = false

    override fun run(token: StateFlow<String?>, trace: LinkTrace): Flow<LinkReading> = emptyFlow()
}

/**
 * `gatt.link` in the radar's catalog of techniques (ADR 0017 §2.3): the lab's only. Any phone with Bluetooth LE can
 * run it (Android and iOS both host and connect); whether the adapter is on is the run's business, as for the
 * channels.
 */
object LinkTechnique : Technique {
    override val id: String = "gatt.link"
    override val kind: TechniqueKind = TechniqueKind.LINK
    override val status: TechniqueStatus = TechniqueStatus.LAB

    override fun available(caps: RadarCaps): Availability = caps.noBluetoothLe ?: Availability.Available
}
