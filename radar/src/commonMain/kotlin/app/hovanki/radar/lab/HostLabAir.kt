package app.hovanki.radar.lab

import app.hovanki.radar.AdPart
import app.hovanki.radar.AirFrame
import app.hovanki.radar.AirHost
import app.hovanki.radar.Availability
import app.hovanki.radar.Decoded
import app.hovanki.radar.RadarCaps
import app.hovanki.radar.RadarCatalog
import app.hovanki.radar.RadarChannel
import app.hovanki.radar.RadarRole
import app.hovanki.radar.RadarService
import app.hovanki.radar.RadarTrace
import app.hovanki.radar.ScanInterest
import app.hovanki.radar.TechniqueStatus
import app.hovanki.radar.channel.overflow.OverflowChannel
import app.hovanki.radar.channel.overflow.OverflowParts
import app.hovanki.radar.decode
import app.hovanki.radar.mapState
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.rules.AppleData
import app.hovanki.shared.rules.OverflowArea
import app.hovanki.shared.rules.RadarToken
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.transform

/**
 * The radio lab's air on the phone's [AirHost] (docs/radio-lab.md §5): «listen to everything» is the host running
 * every channel of the catalog and a listener of every Apple frame; the probe is the host advertising the overflow
 * channel's parts for the bits the lab picks. Everything the host does and hears also goes to [trace] (the lab's log:
 * `adv`, `scan`, `frame`, `air`), except the probe's advertisement, which comes back as [ProbeEvent]s: the lab writes
 * it with the bits it sent. Collected on the main thread, like the host's flows.
 */
class HostLabAir(private val host: AirHost, private val trace: RadarTrace = RadarTrace.None) : LabAir {
    override val canListen: Boolean get() = host.caps.value.bluetooth != BluetoothState.UNSUPPORTED

    override val canProbe: Boolean get() = host.caps.value.canAdvertiseOverflow

    /**
     * An overflow mask ([AppleData.overflowMask] raw, or the table's UUIDs listed by CoreBluetooth) is a
     * [LabFrame.Mask], whatever it decodes to (the lab decodes it and compares it with the probe's bits); a frame a
     * catalog channel reads a well-formed token from is a [LabFrame.Token] (once, with the first channel that read
     * it); the rest the host's tally counts into the log's `air`.
     */
    override fun listen(): Flow<LabFrame> {
        val channels = RadarCatalog.channels
        return host.run(channels + RawAppleListener, MutableStateFlow(null), RadarRole.HIDER, trace)
            .transform { frame ->
                val mask = maskOf(frame)
                if (mask != null) {
                    emit(mask)
                    return@transform
                }
                // One token a frame: two channels may read the same bytes (the service data's layouts).
                for ((tech, decoded) in channels.decode(frame).distinctBy { it.second.token to it.second.via }) {
                    if (!RadarToken.isWellFormed(decoded.token)) continue
                    val token = decoded.token
                    emit(LabFrame.Token(token, decoded.via, frame.rssi, frame.peer, frame.atMillis, frame.api, tech))
                }
            }
    }

    /**
     * The host advertises [OverflowParts] of [bits] as a hider. The bits ride to the host as the token's text: a change
     * is a new token, which the host re-advertises (an iPhone in the background skips it: `skipped_background`). The
     * probe's channel wants nothing heard: a host scans for nothing then.
     */
    override fun probe(bits: StateFlow<Set<Int>>): Flow<ProbeEvent> = callbackFlow {
        val events = object : RadarTrace by trace {
            override fun advertise(action: String, tech: String, token: String?, layout: String?, error: String?) {
                trySend(ProbeEvent(action, error, layout))
            }
        }
        val job = host.run(listOf(ProbeChannel), bits.mapState(ProbeChannel::tokenOf), RadarRole.HIDER, events)
            .launchIn(this)
        awaitClose { job.cancel() }
    }

    private fun maskOf(frame: AirFrame): LabFrame.Mask? {
        val raw = frame.manufacturerData[RadarService.APPLE_COMPANY_ID]?.let(AppleData::overflowMask)
        if (raw != null) {
            val hex = raw.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            return LabFrame.Mask(OverflowArea.bitsOf(raw), hex, frame.rssi, frame.peer, frame.atMillis, frame.api)
        }
        val listed = frame.overflowUuids.mapNotNull(OverflowArea::bitOf).toSet()
        if (listed.isEmpty()) return null
        return LabFrame.Mask(listed, null, frame.rssi, frame.peer, frame.atMillis, frame.api)
    }
}

/**
 * Every frame with Apple's manufacturer data (the overflow masks `0x01`, any iBeacon `0x02 0x15`, the rest of Apple's
 * chatter): Android and a Mac hear them all, which the catalog's filters by prefix alone would not. Reads nothing: the
 * lab maps the masks itself, the tally counts the rest.
 */
internal data object RawAppleListener : RadarChannel {
    override val id: String = "lab.raw_apple"
    override val status: TechniqueStatus = TechniqueStatus.LAB

    override fun available(caps: RadarCaps): Availability = caps.noBluetoothLe ?: Availability.Available

    override fun advertise(token: String, role: RadarRole): List<AdPart> = emptyList()

    override fun interests(): List<ScanInterest> = listOf(ScanInterest.Manufacturer(RadarService.APPLE_COMPANY_ID))

    override fun decode(frame: AirFrame): List<Decoded> = emptyList()
}

/**
 * The probe: [OverflowParts] of the bits its token lists (`1,5,9`; empty: the game's service alone). The overflow
 * channel's advertisement with the lab's bits instead of a token's, under the channel's id in the log.
 */
internal data object ProbeChannel : RadarChannel {
    override val id: String = OverflowChannel.id
    override val status: TechniqueStatus = TechniqueStatus.LAB

    override fun available(caps: RadarCaps): Availability = OverflowChannel.available(caps)

    override fun advertise(token: String, role: RadarRole): List<AdPart> =
        if (role == RadarRole.HIDER) OverflowParts(bitsOf(token)) else emptyList()

    override fun interests(): List<ScanInterest> = emptyList()

    override fun decode(frame: AirFrame): List<Decoded> = emptyList()

    fun tokenOf(bits: Set<Int>): String = bits.sorted().joinToString(",")

    fun bitsOf(token: String): Set<Int> = token.split(',').mapNotNull { it.trim().toIntOrNull() }
        .filter { it in 0 until OverflowArea.BITS }
        .toSet()
}
