package app.hovanki.shared.lab

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The lab log's schema (docs/radio-lab.md §4): one JSON object per event (JSONL), the same on every device, the Mac
 * and the server, so a merge puts them on one timeline. Schema 2 is schema 1 plus [LabFields.RUN] (the run's id, only
 * while the device is in a run on the server) and [LabFields.SEQ] in every event, and the kinds `step` and `net`. The
 * field log (docs/adr/0018-field-test-build.md §3.2) is schema 2 too: the kinds of [FieldKinds] and their optional
 * fields; a reader skips a kind or a field it doesn't know.
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

    /**
     * The fields that say where somebody was: only in a field run (`LabRunKind.GAME`) and only in its `gps` events
     * ([GpsFields]). A lab run never stores them ([withoutCoordinates]).
     */
    val COORDINATES: Set<String> = setOf(GpsFields.LAT, GpsFields.LON)

    /** Whether [event] says where somebody was ([COORDINATES]). */
    fun hasCoordinates(event: JsonObject): Boolean = COORDINATES.any { it in event }

    /** [event] without [COORDINATES]: what a lab run keeps of a line that has them. */
    fun withoutCoordinates(event: JsonObject): JsonObject =
        if (hasCoordinates(event)) JsonObject(event.filterKeys { it !in COORDINATES }) else event

    /**
     * [event] as a run keeps it: a field run's ([fieldRun]) `gps` events with their [COORDINATES], every other event
     * of any run without them.
     */
    fun keptInRun(event: JsonObject, fieldRun: Boolean): JsonObject {
        if (fieldRun && (event[LabFields.K] as? JsonPrimitive)?.content == FieldKinds.GPS) return event
        return withoutCoordinates(event)
    }

    /** `20260929T171530Z` */
    fun fileStamp(millis: Long): String {
        val utc = formatUtc(millis)
        return utc.substring(0, 10).replace("-", "") + "T" + utc.substring(11, 19).replace(":", "") + "Z"
    }
}

/**
 * The kinds the radar's channels and hosts write (docs/adr/0017-radar-techniques-and-big-run.md §4, ADR 0018 §4 B),
 * besides the lab's own. Every one carries `tech`, the channel's id (`ble.name`, `ble.service_data.bare`…):
 *
 * - [ADV] (the lab's kind, now also): `tech` (the channels in the advertisement, comma-separated), `layout` (an
 *   Android hider's: `scan_response` / `bare` / `mfr`), `bytes` and `limit` (the packet against 31, iOS 28),
 *   `fields` (`uuid128 18 + svc_data 22 = 40/31`), `scan_rsp` (the scan response's), `dropped` (channels left out),
 *   `background` (iOS: UUIDs kept for the locked phone's overflow area);
 * - [FRAME]: a frame of ours, whole: `tech`, `api`, `rssi`, `peer` (hashed), `hex` (Android's record), `name`,
 *   `uuids`, `overflow` (bits), `svc` and `mfr` (hex by UUID and company id), `ibeacon`, `tx`, `conn`;
 * - [AIR]: everybody else's frames the scan let through, once a second: `window` (ms), `ours`, `ibeacon`, `masks`,
 *   `apple`, `other` (counts), `bits` (the masks' bits: bit → frames);
 * - [SHADOW]: what a channel in the shadow read, never the game's: `tech`, `token` (and `tokens` when a damaged mask
 *   gives several), `rssi`, `api`, `via`, `peer`;
 * - [REGION]: the seekers' iBeacon region (`ble.ibeacon.region`): `event` (`enter`, `exit`, `state`, `failed`),
 *   `state` (`inside`, `outside`, `unknown`), `error`.
 */
object LabRadarKinds {
    const val ADV = "adv"
    const val FRAME = "frame"
    const val AIR = "air"
    const val SHADOW = "shadow"
    const val REGION = "region"
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
