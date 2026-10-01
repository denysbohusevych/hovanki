package app.hovanki.radar

import app.hovanki.shared.rules.AppleData
import app.hovanki.shared.rules.OverflowArea
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * One per platform (docs/adr/0017-radar-techniques-and-big-run.md, section 2.2): owns the advertisement and the scan
 * and knows the OS's rules. Android (`AndroidAirHost`), iOS (`IosAirHost`), the simulator of the bots
 * (`host.JvmAirHost`). The channels say what to send and what to hear; the host sends and hears it.
 */
interface AirHost {
    val caps: StateFlow<RadarCaps>

    /** Looks at the adapter and the permissions again (iOS: the first call starts watching, and asks the user). */
    fun refresh() = Unit

    /**
     * While collected: advertises the parts of [channels] for [token] in [role] (one advertisement assembled from all
     * parts, [AdPlan]: what the platform can't send is dropped and traced), scans for the union of the channels'
     * interests, and emits every frame heard. [token] null: no advertisement. A change of [token] re-advertises
     * (iOS: not in the background). Every frame also goes to [trace] ([AirTally]).
     */
    fun run(
        channels: List<RadarChannel>,
        token: StateFlow<String?>,
        role: RadarRole,
        trace: RadarTrace = RadarTrace.None,
    ): Flow<AirFrame>
}

/**
 * Every action and reading of the radar, for the debug build's radio lab (docs/radio-lab.md §4.1): [None] unless the
 * lab listens. It never changes what the radio does.
 */
interface RadarTrace {
    /**
     * [action]: `start`, `stop`, `failed` ([error]: the OS's words), `skipped_background` (iOS keeps the old
     * advertisement: it can't start another one in the background) or `dropped` (a part of [tech] the platform can't
     * send, [error] says why); [layout]: [AdBudget.layout] of the whole advertisement.
     */
    fun advertise(action: String, tech: String, token: String?, layout: String? = null, error: String? = null) = Unit

    /**
     * [action]: `start`, `stop` or `failed` ([error]) of a scan by [api], [filters] in words; `region_enter` and
     * `region_exit`: iOS region monitoring ([RegionEvent]).
     */
    fun scan(action: String, api: RadioApi, filters: String? = null, error: String? = null) = Unit

    /** A frame some channel decoded: whole, with what every channel read in it (the channel's id first). */
    fun frame(frame: AirFrame, decoded: List<Pair<String, Decoded>>) = Unit

    /** The frames nobody decoded, counted once a second. */
    fun air(second: AirSecond) = Unit

    companion object {
        val None: RadarTrace = object : RadarTrace {}
    }
}

/**
 * One second of foreign frames (no channel decoded them), for the log's `air` event: [frames] in all, [iBeacons] of
 * any UUID, overflow [masks] (raw or listed), [apple] frames with Apple's manufacturer data, and every bit set in the
 * masks ([maskBits]): the street's noise the overflow channel has to live with. [atMillis]: the second's start.
 */
data class AirSecond(
    val atMillis: Long,
    val frames: Int,
    val iBeacons: Int,
    val masks: Int,
    val apple: Int,
    val maskBits: Set<Int>,
)

/**
 * What a host tells [trace] about the frames it hears: a frame one of [channels] decodes, whole ([RadarTrace.frame]);
 * the others counted by the second of their [AirFrame.atMillis] ([RadarTrace.air], when the next second's first
 * frame comes, or at [flush]); region events as a `scan` action. Not thread-safe: a host calls it from one thread.
 */
class AirTally(private val trace: RadarTrace, private val channels: List<RadarChannel>) {
    private var second: Long? = null
    private var frames = 0
    private var iBeacons = 0
    private var masks = 0
    private var apple = 0
    private val bits = mutableSetOf<Int>()

    /** Counts [frame]; returns what the channels decoded in it (nothing, and no work, with [RadarTrace.None]). */
    fun heard(frame: AirFrame): List<Pair<String, Decoded>> {
        if (trace === RadarTrace.None) return emptyList()
        frame.regionEvent?.let { event ->
            trace.scan("region_${event.name.lowercase()}", frame.api)
            return emptyList()
        }
        val decoded = channels.decode(frame)
        if (decoded.isNotEmpty()) {
            trace.frame(frame, decoded)
            return decoded
        }
        val at = frame.atMillis.floorDiv(SECOND)
        if (second != null && second != at) flush()
        second = at
        frames++
        val appleData = frame.manufacturerData[RadarService.APPLE_COMPANY_ID]
        if (appleData != null) apple++
        if (frame.iBeacon != null || appleData?.let(AppleData::iBeacon) != null) iBeacons++
        val mask = appleData?.let(AppleData::overflowMask)
        if (mask != null || frame.overflowUuids.isNotEmpty()) masks++
        mask?.let { bits += OverflowArea.bitsOf(it) }
        frame.overflowUuids.mapNotNullTo(bits, OverflowArea::bitOf)
        return emptyList()
    }

    /** Hands the second counted so far to [trace]. */
    fun flush() {
        val at = second ?: return
        trace.air(AirSecond(at * SECOND, frames, iBeacons, masks, apple, bits.toSet()))
        second = null
        frames = 0
        iBeacons = 0
        masks = 0
        apple = 0
        bits.clear()
    }

    private companion object {
        const val SECOND = 1_000L
    }
}

/** A read-only view of [this] through [transform]: the value now, and every distinct one while collected. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal fun <T, R> StateFlow<T>.mapState(transform: (T) -> R): StateFlow<R> = object : StateFlow<R> {
    override val value: R get() = transform(this@mapState.value)
    override val replayCache: List<R> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        this@mapState.map(transform).distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}
