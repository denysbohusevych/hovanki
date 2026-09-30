package app.hovanki.shared.lab

import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.rules.ProximityRules
import app.hovanki.shared.rules.Smoothings

/**
 * «Без X» for the channel [tech] (docs/adr/0017-radar-techniques-and-big-run.md §7, section 3): over [seconds]
 * pair-seconds where the pair had a band with every channel or without [tech], the two bands were the [same] in that
 * many; in [onlyChannel] of them [tech] was the only channel the pair heard (either way) in the last
 * [ProximityRules.SIGNAL_TTL_MILLIS].
 */
data class WithoutResult(val tech: String, val seconds: Int, val same: Int, val onlyChannel: Int)

/**
 * The pairs' bands with every channel against the bands without one channel: what the radar would lose without it. One
 * [BandTrack] with every reading and one per channel with that channel's readings left out, all under the same
 * smoothing and calibration (the game's: `smooth.ema`, `calib.none`). A reading's channel is [LabMerge.techOf]: its
 * `tech`, `api/via` in logs before the channels.
 */
object WithoutChannel {
    /**
     * Every channel of [techs] over the whole seconds of [seconds] (stepped by a second), for the pairs of the run's
     * devices (the ones that wrote [events]) heard at all. [events]: sorted by time, only the `rx` are read. [all]: the
     * track with every reading when the caller has it already (the same smoothing and calibration).
     */
    fun all(
        events: List<LabEvent>,
        sender: (String?) -> String,
        techs: List<String>,
        seconds: LongProgression,
        all: BandTrack? = null,
        smoothing: String = Smoothings.EMA,
        calibration: Calibration = Calibrations.none(),
    ): List<WithoutResult> {
        val readings = events.filter { it.k == "rx" && it.int("rssi") != null }
        val devices = events.mapTo(HashSet()) { it.dev }
        val full =
            all ?: BandTrack(smoothing, calibration).also { track -> readings.forEach { track.feed(it, sender) } }
        val pairs = full.pairs.filter { pair -> pair.split('|').all { it in devices } }.sorted()
        if (pairs.isEmpty() || techs.isEmpty()) return techs.map { WithoutResult(it, 0, 0, 0) }
        val heard = heardTimes(readings, sender, pairs.toSet())
        return techs.map { tech ->
            val track = BandTrack(full.smoothing, full.calibration)
            for (rx in readings) if (LabMerge.techOf(rx) != tech) track.feed(rx, sender)
            var counted = 0
            var same = 0
            var only = 0
            for (t in seconds step BandErrors.SECOND_MILLIS) {
                for (pair in pairs) {
                    val withIt = full.pairBandAt(pair, t)
                    val withoutIt = track.pairBandAt(pair, t)
                    if (withIt == RadarBand.NONE && withoutIt == RadarBand.NONE) continue
                    counted++
                    if (withIt == withoutIt) same++
                    if (onlyChannel(heard[pair].orEmpty(), tech, t)) only++
                }
            }
            WithoutResult(tech, counted, same, only)
        }
    }

    /** Pair → channel → the times it was heard (either way), sorted. */
    private fun heardTimes(
        readings: List<LabEvent>,
        sender: (String?) -> String,
        pairs: Set<String>,
    ): Map<String, Map<String, LongArray>> {
        val lists = HashMap<String, HashMap<String, MutableList<Long>>>()
        for (rx in readings) {
            val from = sender(rx.string("token"))
            if (from == rx.dev) continue
            val pair = RunStep.pairKey(from, rx.dev)
            if (pair !in pairs) continue
            lists.getOrPut(pair) { HashMap() }.getOrPut(LabMerge.techOf(rx)) { ArrayList() } += rx.t
        }
        return lists.mapValues { (_, byTech) -> byTech.mapValues { (_, times) -> times.sorted().toLongArray() } }
    }

    /** [tech] heard in the last signal's life before [t], and no other channel. */
    private fun onlyChannel(heard: Map<String, LongArray>, tech: String, t: Long): Boolean {
        var found = false
        for ((channel, times) in heard) {
            val index = lastAtOrBefore(times, t)
            if (index < 0 || t - times[index] > ProximityRules.SIGNAL_TTL_MILLIS) continue
            if (channel != tech) return false
            found = true
        }
        return found
    }
}
