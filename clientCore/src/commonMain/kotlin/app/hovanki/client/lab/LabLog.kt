@file:OptIn(ExperimentalTime::class)

package app.hovanki.client.lab

import app.hovanki.client.radio.RadioApi
import app.hovanki.client.radio.SightingVia
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.rules.RadarSmoother
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

/**
 * The radio lab's log (docs/radio-lab.md §4): one JSON object per event (JSONL), the same schema on every device and
 * the Mac, so a merge puts them on one timeline. Every event has [LabFields.T] (server time: the device's clock plus
 * the last measured offset, [setClock]), [LabFields.DT] (the device's clock), [LabFields.MONO] (a monotonic clock for
 * gaps), [LabFields.DEV] (the device's label), [LabFields.K] (the kind) and [LabFields.APP] (the app's state then).
 *
 * Never a coordinate: `gps` is an accuracy and an age. In memory only, a ring of [capacity] events; the developer
 * exports it by hand ([export]). Debug builds only: disabled ([isEnabled] false) it records nothing, and it records only
 * while the lab runs ([isRecording]). Main thread.
 */
class LabLog(
    val isEnabled: Boolean,
    private val deviceTimeMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val monotonicMillis: () -> Long = monotonicClock(),
    private val capacity: Int = CAPACITY,
    random: Random = Random.Default,
) {
    private val ring = ArrayDeque<ByteArray>()
    private val kinds = LinkedHashMap<String, Int>()
    private val smoothers = HashMap<String, RadarSmoother>()
    private val bands = HashMap<String, RadarBand>()
    private val tickGaps = ArrayList<TickGap>()
    private var lastTickMono: Long? = null
    private var dropped = 0L

    /** Salt of [peerId]: new with every log, never written anywhere, so a hash can't be tied to a device outside it. */
    private val salt: Long = random.nextLong()

    private val mutableLabel = MutableStateFlow(DEFAULT_LABEL)

    /** This device's name in the lab: `A`, `B`, `droid`, `mac`. */
    val label: StateFlow<String> = mutableLabel.asStateFlow()

    private val mutableClock = MutableStateFlow<ClockEstimate?>(null)

    /** The last measured offset to the server's clock; null: never measured. */
    val clock: StateFlow<ClockEstimate?> = mutableClock.asStateFlow()

    private val mutableCount = MutableStateFlow(0L)

    /** Events written since the start or [clear], dropped ones included. */
    val count: StateFlow<Long> = mutableCount.asStateFlow()

    /** The device's clock at the start or at [clear], for the export's file name. */
    var startedAtMillis: Long = deviceTimeMillis()
        private set

    /** The app's state for every event, from the platform: `active`, `background`, `screen_off`… */
    var appState: () -> String = { "-" }

    /** The lab is running: events are written. Off, the game's radio tracing outside the lab writes nothing. */
    var isRecording: Boolean = false

    /** The log's device clock: for the parts that measure against it (the clock sync, the controller). */
    fun deviceNow(): Long = deviceTimeMillis()

    /** The log's monotonic clock. */
    fun monoNow(): Long = monotonicMillis()

    fun setLabel(label: String) {
        mutableLabel.value = label.trim().ifEmpty { DEFAULT_LABEL }
    }

    /** Writes an event of kind [k] with the common fields and [fields]. */
    fun event(k: String, fields: JsonObjectBuilder.() -> Unit = {}) {
        if (!isEnabled || !isRecording) return
        val dt = deviceTimeMillis()
        val built = buildJsonObject {
            put(LabFields.T, dt + (mutableClock.value?.offsetMillis ?: 0L))
            put(LabFields.DT, dt)
            put(LabFields.MONO, monotonicMillis())
            put(LabFields.DEV, mutableLabel.value)
            put(LabFields.K, k)
            put(LabFields.APP, appState())
            fields()
        }
        // A field without a value is left out rather than written as null: the lines stay short.
        val line = JsonObject(built.filterValues { it !is JsonNull }).toString()
        ring.addLast(line.encodeToByteArray())
        if (ring.size > capacity) {
            ring.removeFirst()
            dropped++
        }
        kinds[k] = (kinds[k] ?: 0) + 1
        mutableCount.value += 1
    }

    /** The header: who this device is, and the lab's mode. At the start and with every export. */
    fun session(model: String?, os: String?, build: String?, commit: String?, mode: String?) = event("session") {
        put("schema", SCHEMA)
        put("model", model)
        put("os", os)
        put("build", build)
        put("commit", commit)
        put("mode", mode)
    }

    /** A new offset to the server's clock: from now on [LabFields.T] includes it. */
    fun setClock(estimate: ClockEstimate) {
        mutableClock.value = estimate
        clockEvent()
    }

    /** The offset in use and how old it is (the clock could not be measured: [failed]). */
    fun clockEvent(failed: Boolean = false) {
        val estimate = mutableClock.value
        event("clock") {
            put("offset", estimate?.offsetMillis)
            put("rtt", estimate?.rttMillis)
            put("samples", estimate?.samples)
            put("age", estimate?.let { monotonicMillis() - it.measuredAtMono })
            if (failed) put("failed", true)
        }
    }

    fun mark(
        label: String,
        by: String,
        step: Int? = null,
        place: String? = null,
        action: String? = null,
        distance: Double? = null,
    ) = event("mark") {
        put("label", label)
        put("by", by)
        put("step", step)
        put("place", place)
        put("action", action)
        put("distance", distance)
    }

    fun life(event: String) = event("life") { put("event", event) }

    /** Once a second while the process lives: a gap in the ticks is the app suspended. */
    fun tick(n: Long) {
        if (!isEnabled || !isRecording) return
        val mono = monotonicMillis()
        val last = lastTickMono
        if (last != null && mono - last > TICK_GAP_MILLIS && tickGaps.size < MAX_TICK_GAPS) {
            tickGaps += TickGap(deviceTimeMillis() - (mono - last), mono - last)
        }
        lastTickMono = mono
        event("tick") { put("n", n) }
    }

    fun bt(state: String, peripheral: String? = null, central: String? = null) = event("bt") {
        put("state", state)
        put("peripheral_state", peripheral)
        put("central_state", central)
    }

    /** [mode]: `hider_name`, `hider_service_data`, `ibeacon`, `overflow_probe`; [payload]: what the advertisement has. */
    fun adv(action: String, mode: String, token: String? = null, payload: String? = null, error: String? = null) =
        event("adv") {
            put("action", action)
            put("mode", mode)
            put("token", token)
            put("payload", payload)
            put("error", error)
        }

    fun scan(action: String, api: RadioApi, filters: String? = null, error: String? = null) = event("scan") {
        put("action", action)
        put("api", api.key)
        put("filters", filters)
        put("error", error)
    }

    /**
     * Every reading, not thinned out: [token] (null when the sender carried none we could read), [rssi], by [api] via
     * [via], from [peer] (the OS's id; hashed here). [atMillis]: when the platform heard it, device clock. A reading
     * with a token moves the lab's smoothed band for it ([band] on a change).
     */
    fun rx(token: String?, rssi: Int, api: RadioApi, via: SightingVia, peer: String? = null, atMillis: Long? = null) {
        if (!isEnabled || !isRecording) return
        val now = deviceTimeMillis()
        event("rx") {
            put("token", token)
            put("rssi", rssi)
            put("api", api.key)
            put("via", via.key)
            put("peer", peer?.let(::peerId))
            if (atMillis != null && now - atMillis > 0) put("ago", now - atMillis)
        }
        if (token != null) {
            val smoother = smoothers.getOrPut(token) { RadarSmoother() }
            val at = atMillis ?: now
            smoother.add(rssi, at)
            val band = smoother.bandAt(at)
            if (bands[token] != band) {
                bands[token] = band
                event("band") {
                    put("token", token)
                    put("level", smoother.levelDbm?.let { round(it, 1) })
                    put("band", band.name.lowercase())
                }
            }
        }
    }

    /** The loudest band the lab hears now: what the lab's pulse beats. */
    fun strongestBand(): RadarBand {
        val now = deviceTimeMillis()
        return smoothers.values.map { it.bandAt(now) }.maxByOrNull { it.ordinal } ?: RadarBand.NONE
    }

    /**
     * An overflow mask heard: its [bits] (and [hex], the raw 16 bytes, where the platform gives them), what it
     * decodes to ([decoded]) and, for our own probe, which bits should stand ([expected]).
     */
    fun mask(
        bits: Collection<Int>,
        rssi: Int,
        api: RadioApi,
        hex: String? = null,
        peer: String? = null,
        decoded: List<String> = emptyList(),
        expected: Collection<Int>? = null,
    ) = event("mask") {
        put("hex", hex)
        put("bits", JsonArray(bits.sorted().map(::JsonPrimitive)))
        put("rssi", rssi)
        put("api", api.key)
        put("peer", peer?.let(::peerId))
        put("decoded", JsonArray(decoded.map(::JsonPrimitive)))
        if (expected != null) put("expected", JsonArray(expected.sorted().map(::JsonPrimitive)))
    }

    fun motion(sample: MotionFeatures) = event("motion") {
        put("std", sample.std?.let { round(it, 3) })
        put("gx", sample.gravity?.x?.let { round(it, 3) })
        put("gy", sample.gravity?.y?.let { round(it, 3) })
        put("gz", sample.gravity?.z?.let { round(it, 3) })
        put("orient", sample.orientation?.key)
        put("activity", sample.activity?.name?.lowercase())
    }

    fun prox(near: Boolean?, rawCm: Double? = null, maxCm: Double? = null, monitoring: Boolean? = null) =
        event("prox") {
            put("near", near)
            put("raw", rawCm?.let { round(it, 1) })
            put("max", maxCm?.let { round(it, 1) })
            put("monitoring", monitoring)
        }

    fun light(lux: Double) = event("light") { put("lux", round(lux, 1)) }

    fun carry(state: String, candidate: String? = null, reason: String? = null) = event("carry") {
        put("state", state)
        put("candidate", candidate)
        put("reason", reason)
    }

    /** [kind]: `core_haptics`, `impact`, `notify_silent_sound`, `notify_no_sound`, `vibrator`. */
    fun haptic(kind: String, result: String, error: String? = null, reason: String? = null, group: Int? = null) =
        event("haptic") {
            put("kind", kind)
            put("result", result)
            put("error", error)
            put("reason", reason)
            put("group", group)
        }

    fun battery(level: Double?, state: String?, lowPower: Boolean?) = event("battery") {
        put("level", level?.let { round(it, 3) })
        put("state", state)
        put("low_power", lowPower)
    }

    /** A GPS fix: its accuracy and how old it was. Never where. */
    fun gps(accuracyMeters: Double, ageMillis: Long) = event("gps") {
        put("acc", round(accuracyMeters, 1))
        put("age", ageMillis)
    }

    fun note(text: String) = event("note") { put("text", text) }

    /** An 8-character hash of the OS's id of a sender: tells senders apart within this log only. */
    fun peerId(raw: String): String {
        // FNV-1a over the salt and the id: no cryptography needed, the salt never leaves the process.
        var hash = FNV_OFFSET xor salt
        for (char in raw) {
            hash = (hash xor char.code.toLong()) * FNV_PRIME
        }
        return (hash ushr 32).toInt().toUInt().toString(16).padStart(8, '0')
    }

    /** The log so far, one JSON object per line. */
    fun lines(): List<String> = ring.map { it.decodeToString() }

    /**
     * The file to share: `hovanki-lab-<label>-<UTC start>.jsonl` and a short text summary ([header] first: model, OS,
     * commit…) with the clock's offset, the count of every kind and the gaps in the ticks.
     */
    fun export(header: List<String> = emptyList()): LabExport {
        val stamp = fileStamp(startedAtMillis)
        val name = "hovanki-lab-${mutableLabel.value}-$stamp"
        val jsonl = buildString {
            for (line in ring) {
                append(line.decodeToString())
                append('\n')
            }
        }
        return LabExport("$name.jsonl", "$name.txt", jsonl, summary(header))
    }

    fun summary(header: List<String> = emptyList()): String = buildString {
        for (line in header) appendLine(line)
        appendLine("label: ${mutableLabel.value}, schema $SCHEMA")
        val estimate = mutableClock.value
        if (estimate == null) {
            appendLine("clock: never measured, t = device clock")
        } else {
            val age = (monotonicMillis() - estimate.measuredAtMono) / 1000
            appendLine("clock: offset ${estimate.offsetMillis} ms, rtt ${estimate.rttMillis} ms, measured $age s ago")
        }
        appendLine("events: ${mutableCount.value} (kept ${ring.size}, dropped $dropped)")
        for ((kind, n) in kinds) appendLine("  $kind: $n")
        if (tickGaps.isEmpty()) {
            appendLine("tick gaps: none")
        } else {
            appendLine("tick gaps (the app was suspended):")
            for (gap in tickGaps) appendLine("  ${formatUtc(gap.atMillis)} UTC: ${gap.millis / 1000.0} s")
        }
    }

    /** Forgets everything; a new start. */
    fun clear() {
        ring.clear()
        kinds.clear()
        smoothers.clear()
        bands.clear()
        tickGaps.clear()
        lastTickMono = null
        dropped = 0
        mutableCount.value = 0
        startedAtMillis = deviceTimeMillis()
    }

    private class TickGap(val atMillis: Long, val millis: Long)

    companion object {
        /** The schema's version, in every `session` event. */
        const val SCHEMA = 1

        /** About an hour of 3 neighbours heard ~10 times a second, and everything else (docs/radio-lab.md §4.2). */
        const val CAPACITY = 250_000

        const val DEFAULT_LABEL = "A"

        /** Ticks come every second; a longer silence is the app suspended. */
        const val TICK_GAP_MILLIS = 2_500L
        private const val MAX_TICK_GAPS = 1_000

        private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
        private const val FNV_PRIME = 1099511628211L

        val Off = LabLog(isEnabled = false)

        fun monotonicClock(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }

        internal fun round(value: Double, digits: Int): Double {
            var scale = 1.0
            repeat(digits) { scale *= 10 }
            return (value * scale).roundToLong() / scale
        }

        /** `20260929T171530Z` */
        internal fun fileStamp(millis: Long): String {
            val utc = formatUtc(millis)
            return utc.substring(0, 10).replace("-", "") + "T" + utc.substring(11, 19).replace(":", "") + "Z"
        }

        /** `2026-09-29 17:15:30.123` */
        fun formatUtc(millis: Long): String {
            val instant = kotlin.time.Instant.fromEpochMilliseconds(millis)
            val text = instant.toString() // 2026-09-29T17:15:30.123Z, or without the fraction
            val date = text.substring(0, 10)
            val time = text.substring(11).removeSuffix("Z")
            val (whole, fraction) = time.split('.').let { it[0] to (it.getOrNull(1) ?: "") }
            return "$date $whole.${fraction.padEnd(3, '0').take(3)}"
        }
    }
}

/** The common fields of every lab event. */
object LabFields {
    const val T = "t"
    const val DT = "dt"
    const val MONO = "mono"
    const val DEV = "dev"
    const val K = "k"
    const val APP = "app"
}

/** What «Export» hands to the system «Share»: the log and its summary. */
data class LabExport(val fileName: String, val summaryName: String, val jsonl: String, val summary: String)
