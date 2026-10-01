package app.hovanki.radar

import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Platform
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections

/**
 * The air a [JvmAirHost] advertises and listens in: whoever drives the world (the e2e bots' `RadioWorld`) asks every
 * running host what it puts on the air ([JvmAirHost.broadcast]) and hands it to every other within range
 * ([JvmAirHost.receive]) with the signal the distance and the bodies leave.
 */
interface JvmAir {
    fun register(host: JvmAirHost)

    fun unregister(host: JvmAirHost)
}

/**
 * A simulated phone's Bluetooth (docs/adr/0017-radar-techniques-and-big-run.md, section 2.2): the same channels, the
 * same advertisement and the same decoding as on a real phone ([RadarCatalog], [AdJoin], [AirDecoder]), the OS's rules
 * of [AirRules] in between; so a new channel behaves honestly in the bots' games without touching them. [onScreen]: the
 * app on the screen (a phone in a pocket is not: an iPhone's app is in the background there). [state] is the phone's
 * Bluetooth switch; [peer] the id its OS gives it to others (an address).
 *
 * Runs while [run] is collected, like the phones' radios: the token goes on the air with the role's and the platform's
 * channels, what comes in is decoded on the collector's thread. With a [trace] that listens (a journal), the shadow
 * works as on a phone: an iPhone's mask, the `.mfr` and `.bare` layouts by the player's number, the region.
 */
class JvmAirHost(
    val platform: AirPlatform,
    private val air: JvmAir,
    private val onScreen: () -> Boolean,
    private val clock: () -> Long,
    private val trace: RadioTrace = RadioTrace.None,
    val peer: String = "sim",
    override val state: MutableStateFlow<BluetoothState> = MutableStateFlow(BluetoothState.ON),
) : ProximityRadio {
    /** The token this phone advertises right now; null while nothing runs the radio. */
    @Volatile var token: String? = null
        private set

    /** Advertising as a seeker (the iBeacon frame) rather than as a hider. */
    @Volatile var asSeeker: Boolean = false
        private set

    @Volatile private var options: RadioOptions = RadioOptions()

    @Volatile private var inbox: Channel<HeardFrame>? = null

    private val tokensHeard = Collections.synchronizedSet(LinkedHashSet<String>())

    /** Every token the game's channels ever read here (never the shadow's). */
    val heardTokens: Set<String> get() = synchronized(tokensHeard) { tokensHeard.toSet() }

    /** Whether the phone's app is on the screen now. */
    val isOnScreen: Boolean get() = onScreen()

    override fun run(tokens: StateFlow<String?>, asSeeker: Boolean, options: RadioOptions): Flow<RadioSighting> =
        channelFlow {
            this@JvmAirHost.asSeeker = asSeeker
            this@JvmAirHost.options = options
            val frames = Channel<HeardFrame>(INBOX, BufferOverflow.DROP_OLDEST)
            inbox = frames
            val decoder = AirDecoder(trace)
            val region = RegionWatch()
            launch {
                tokens.collect { next ->
                    val was = token
                    if (was != null) trace.advertise("stop", mode(), was)
                    token = next
                    if (next != null) {
                        val advert = RadarCatalog.advert(next, role(), platform, options, trace.isListening)
                        trace.advertise("start", mode(), next, report = advert.report())
                    }
                }
            }
            air.register(this@JvmAirHost)
            try {
                while (true) {
                    val frame = withTimeoutOrNull(TICK_MILLIS) { frames.receive() }
                    val now = clock()
                    if (frame != null) {
                        if (frame.iBeacon != null) region.heard(now)
                        for (sighting in decoder.decode(frame)) {
                            tokensHeard += sighting.token
                            send(sighting)
                        }
                    }
                    decoder.flush(now)
                    region.check(now)
                }
            } finally {
                air.unregister(this@JvmAirHost)
                inbox = null
                token?.let { trace.advertise("stop", mode(), it) }
                token = null
            }
        }

    /** What this phone puts on the air now, by its OS's rules; null: nothing (no token, Bluetooth off, refused). */
    fun broadcast(): Broadcast? {
        val token = token ?: return null
        if (state.value != BluetoothState.ON) return null
        val advert = RadarCatalog.advert(token, role(), platform, options, trace.isListening)
        return AirRules.broadcast(advert, platform, onScreen())
    }

    /** [broadcast] of the phone its OS knows as [sender] came in at [rssi] dBm: what this phone's APIs make of it. */
    fun receive(broadcast: Broadcast, rssi: Int, sender: String) {
        val frames = inbox ?: return
        if (state.value != BluetoothState.ON) return
        val interests = RadarCatalog.interests(platform, trace.isListening)
        for (frame in AirRules.hear(broadcast, platform, onScreen(), interests, rssi, clock(), sender)) {
            frames.trySend(frame)
        }
    }

    private fun role(): AirRole = if (asSeeker) AirRole.SEEKER else AirRole.HIDER

    private fun mode(): String = when {
        asSeeker -> "ibeacon"
        platform == AirPlatform.ANDROID -> "hider_service_data"
        else -> "hider_name"
    }

    /**
     * The seekers' iBeacon region (`ble.ibeacon.region`) on an iPhone with a journal: entered with the first iBeacon
     * ranged, left [REGION_EXIT_MILLIS] after the last one, as iOS does.
     */
    private inner class RegionWatch {
        private var lastHeard: Long? = null
        private var inside = false
        private val watching: Boolean get() = platform == AirPlatform.IOS && trace.isListening

        fun heard(now: Long) {
            lastHeard = now
            if (!inside && watching) {
                inside = true
                trace.region("enter", "inside")
            }
        }

        fun check(now: Long) {
            val last = lastHeard ?: return
            if (inside && now - last >= REGION_EXIT_MILLIS) {
                inside = false
                if (watching) trace.region("exit", "outside")
            }
        }
    }

    companion object {
        /** Frames waiting for the collector; the oldest go first when it lags. */
        private const val INBOX = 1_024

        /** How often the host looks at its clock without frames: the air's second, the region's exit. */
        private const val TICK_MILLIS = 250L

        /** iOS says a region is left about 30 s after its last beacon. */
        const val REGION_EXIT_MILLIS = 30_000L
    }
}

/** The bots' [AirPlatform] of a phone the protocol knows: anything not an iPhone scans and sends like Android. */
fun airPlatformOf(platform: Platform): AirPlatform = if (platform ==
    Platform.IOS
) {
    AirPlatform.IOS
} else {
    AirPlatform.ANDROID
}
