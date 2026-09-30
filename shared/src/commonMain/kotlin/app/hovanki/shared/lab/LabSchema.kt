package app.hovanki.shared.lab

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The lab log's schema (docs/radio-lab.md §4): one JSON object per event (JSONL), the same on every device, the Mac
 * and the server, so a merge puts them on one timeline. Schema 2 is schema 1 plus [LabFields.RUN] (the run's id, only
 * while the device is in a run on the server) and [LabFields.SEQ] in every event, and the kinds `step` and `net`.
 */
object LabSchema {
    /** The schema's version, in every `session` event. */
    const val VERSION = 2

    /** Ticks come every second; a longer silence is the app suspended. */
    const val TICK_GAP_MILLIS = 2_500L

    /** `2026-09-29 17:15:30.123` */
    @OptIn(ExperimentalTime::class)
    fun formatUtc(millis: Long): String {
        val text = Instant.fromEpochMilliseconds(millis).toString() // 2026-09-29T17:15:30.123Z, or without the fraction
        val date = text.substring(0, 10)
        val time = text.substring(11).removeSuffix("Z")
        val (whole, fraction) = time.split('.').let { it[0] to (it.getOrNull(1) ?: "") }
        return "$date $whole.${fraction.padEnd(3, '0').take(3)}"
    }

    /** `20260929T171530Z` */
    fun fileStamp(millis: Long): String {
        val utc = formatUtc(millis)
        return utc.substring(0, 10).replace("-", "") + "T" + utc.substring(11, 19).replace(":", "") + "Z"
    }
}

/** The common fields of every lab event. */
object LabFields {
    /** Server time: the device's clock plus the last measured offset. */
    const val T = "t"

    /** The device's own clock. */
    const val DT = "dt"

    /** A monotonic clock, for gaps. */
    const val MONO = "mono"

    /** The device's label. */
    const val DEV = "dev"

    /** The event's kind. */
    const val K = "k"

    /** The app's state then. */
    const val APP = "app"

    /** Schema 2: the run's id, only while in a run on the server. */
    const val RUN = "run"

    /** Schema 2: the event's number on its device, never reset. */
    const val SEQ = "seq"
}
