package app.hovanki.client.lab

import app.hovanki.shared.protocol.FieldUpload

/**
 * How the field log stays small (docs/adr/0018-field-test-build.md §3.2): the lab writes every reading of the radio,
 * about 5 KB a second per phone; a game of 50 phones for 3 hours would be 2.5 GB. In the field:
 * - `rx`: one event per peer and [rxEveryMillis]: how many readings, their median and the loudest ([rx], [flush]);
 * - `frame` and `air`: at most one per kind and peer every [frameEveryMillis] ([allowThrottled]);
 * - `gps`: at most one fix every [gpsEveryMillis] ([allowGps]);
 * - everything else as it is.
 *
 * Pure: the time comes in, the device's clock of the readings. Bounded: at most [MAX_READINGS] readings a window are
 * kept for the median (the count goes on), at most [MAX_PEERS] peers at once (a new one beyond closes the oldest).
 */
class FieldThinning(
    val rxEveryMillis: Long = FieldUpload.RX_EVERY_MILLIS,
    val frameEveryMillis: Long = FieldUpload.FRAME_EVERY_MILLIS,
    val gpsEveryMillis: Long = FieldUpload.GPS_EVERY_MILLIS,
) {
    /** Who was heard, and how: one window each. */
    data class RxKey(val token: String?, val api: String, val via: String, val peer: String?)

    /** A window of readings of one [key]: from [fromMillis] to [toMillis], [count] of them. */
    data class RxSummary(
        val key: RxKey,
        val count: Int,
        val median: Int,
        val max: Int,
        val fromMillis: Long,
        val toMillis: Long,
    )

    private class Window(val from: Long) {
        var to: Long = from
        var count = 0
        var max = Int.MIN_VALUE
        val readings = ArrayList<Int>()

        fun add(rssi: Int, at: Long) {
            count++
            to = maxOf(to, at)
            max = maxOf(max, rssi)
            if (readings.size < MAX_READINGS) readings += rssi
        }

        fun summary(key: RxKey): RxSummary {
            val sorted = readings.sorted()
            return RxSummary(key, count, sorted[(sorted.size - 1) / 2], max, from, to)
        }
    }

    private val windows = LinkedHashMap<RxKey, Window>()
    private val lastThrottled = HashMap<String, Long>()
    private var lastGps: Long? = null

    /**
     * A reading of [key] at [atMillis]: kept in its peer's window. Returns the windows it closed: its own one when
     * the reading falls after it, the oldest when there are too many peers.
     */
    fun rx(key: RxKey, rssi: Int, atMillis: Long): List<RxSummary> {
        val closed = ArrayList<RxSummary>(1)
        val open = windows[key]
        if (open != null && atMillis >= open.from + rxEveryMillis) {
            windows.remove(key)
            closed += open.summary(key)
        }
        val window = windows.getOrPut(key) {
            if (windows.size >= MAX_PEERS) {
                val oldest = windows.entries.first()
                windows.remove(oldest.key)
                closed += oldest.value.summary(oldest.key)
            }
            Window(atMillis)
        }
        window.add(rssi, atMillis)
        return closed
    }

    /** The windows that are over by [nowMillis] (all of them with [all]), oldest first: each written once. */
    fun flush(nowMillis: Long, all: Boolean = false): List<RxSummary> {
        val closed = ArrayList<RxSummary>()
        val iterator = windows.entries.iterator()
        while (iterator.hasNext()) {
            val (key, window) = iterator.next()
            if (all || nowMillis >= window.from + rxEveryMillis) {
                closed += window.summary(key)
                iterator.remove()
            }
        }
        return closed.sortedBy { it.fromMillis }
    }

    /** Whether an event of [kind] about [key] may be written at [nowMillis]: one per [frameEveryMillis]. */
    fun allowThrottled(kind: String, key: String?, nowMillis: Long): Boolean {
        val id = "$kind|${key.orEmpty()}"
        val last = lastThrottled[id]
        if (last != null && nowMillis - last < frameEveryMillis) return false
        if (lastThrottled.size >= MAX_PEERS) lastThrottled.clear()
        lastThrottled[id] = nowMillis
        return true
    }

    /** Whether a fix taken at [atMillis] may be written: one per [gpsEveryMillis]. */
    fun allowGps(atMillis: Long): Boolean {
        val last = lastGps
        if (last != null && atMillis - last < gpsEveryMillis && atMillis >= last) return false
        lastGps = atMillis
        return true
    }

    companion object {
        const val MAX_READINGS = 200
        const val MAX_PEERS = 500
    }
}
