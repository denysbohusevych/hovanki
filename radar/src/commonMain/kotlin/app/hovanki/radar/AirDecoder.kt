package app.hovanki.radar

/**
 * What a host does with every frame its scan lets through (docs/adr/0017-radar-techniques-and-big-run.md, section
 * 2.2): every game channel of [channels] reads it, and its token becomes a [RadioSighting] for the game. While [trace]
 * listens, the shadow's channels read it too and what they read goes to [trace] only ([RadioTrace.shadow]); a frame of
 * ours goes to it whole ([RadioTrace.frame]) and everybody else's are counted into one [AirSummary] a second
 * ([RadioTrace.air]). One per running radio; called where the host's callbacks come (the main thread on the phones).
 */
class AirDecoder(private val trace: RadioTrace, private val channels: List<RadarChannel> = RadarCatalog.channels) {
    private var windowFrom: Long? = null
    private var ours = 0
    private var ibeacons = 0
    private var masks = 0
    private var apple = 0
    private var other = 0
    private val maskBits = HashMap<Int, Int>()

    /** The game's sightings in [frame]; the shadow's readings, the frame and the counts go to the trace. */
    fun decode(frame: HeardFrame): List<RadioSighting> {
        val listening = trace.isListening
        val readings = channels.mapNotNull { if (listening || it.use == ChannelUse.GAME) it.decode(frame) else null }
        if (listening) count(frame, readings.isNotEmpty())
        if (readings.isEmpty()) return emptyList()
        if (listening) trace.frame(frame, readings.joinToString(",") { it.tech })
        val sightings = ArrayList<RadioSighting>()
        for (reading in readings) {
            when (reading.use) {
                ChannelUse.GAME -> reading.tokens.mapTo(sightings) { token ->
                    RadioSighting(token, frame.rssi, frame.atMillis, frame.api, reading.via, frame.peer, reading.tech)
                }

                ChannelUse.SHADOW -> if (listening) trace.shadow(reading.tech, reading.tokens, frame, reading.via)
            }
        }
        return sightings
    }

    /** Writes the counts of the second that began at the first frame counted, when it is over by [nowMillis]. */
    fun flush(nowMillis: Long, force: Boolean = false) {
        val from = windowFrom ?: return
        if (!force && nowMillis - from < AIR_WINDOW_MILLIS) return
        if (trace.isListening) {
            trace.air(AirSummary(from, nowMillis - from, ours, ibeacons, masks, apple, other, maskBits.toMap()))
        }
        windowFrom = null
        ours = 0
        ibeacons = 0
        masks = 0
        apple = 0
        other = 0
        maskBits.clear()
    }

    private fun count(frame: HeardFrame, isOurs: Boolean) {
        flush(frame.atMillis)
        if (windowFrom == null) windowFrom = frame.atMillis
        if (isOurs) {
            ours++
            return
        }
        val foreign = RadarCatalog.foreign(frame)
        when (foreign.kind) {
            ForeignKind.IBEACON -> ibeacons++

            ForeignKind.MASK -> {
                masks++
                for (bit in foreign.bits) maskBits[bit] = (maskBits[bit] ?: 0) + 1
            }

            ForeignKind.APPLE -> apple++

            ForeignKind.OTHER -> other++
        }
    }

    companion object {
        /** `air` comes once a second (ADR 0017 §4): in a city there are hundreds of others' frames a second. */
        const val AIR_WINDOW_MILLIS = 1_000L
    }
}
