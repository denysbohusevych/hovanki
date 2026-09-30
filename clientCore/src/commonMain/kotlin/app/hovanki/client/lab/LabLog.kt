@file:OptIn(ExperimentalTime::class)

package app.hovanki.client.lab

import app.hovanki.device.lab.MotionFeatures
import app.hovanki.radar.RadioApi
import app.hovanki.radar.SightingVia
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.lab.LabSchema
import app.hovanki.shared.protocol.LabUpload
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
 * gaps), [LabFields.DEV] (the device's label), [LabFields.K] (the kind), [LabFields.APP] (the app's state then),
 * [LabFields.SEQ] (a counter of this log's events that never goes back, [nextSeq]) and, while the device is in a run
 * on the server ([setRun]), [LabFields.RUN] (schema 2, [LabSchema.VERSION]).
 *
 * Never a coordinate: `gps` is an accuracy and an age. In memory only, a ring of [capacity] events; the developer
 * exports it by hand ([export]), and in a run on the server the lab uploads it ([pending], `LabUploader`). Debug builds
 * only: disabled ([isEnabled] false) it records nothing, and it records only while the lab runs ([isRecording]). Main
 * thread.
 */
class LabLog(
    val isEnabled: Boolean,
    private val deviceTimeMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val monotonicMillis: () -> Long = monotonicClock(),
    private val capacity: Int = CAPACITY,
    random: Random = Random.Default,
) {
    private val ring = ArrayDeque<Entry>()
    private val kinds = LinkedHashMap<String, Int>()
    private val smoothers = HashMap<String, RadarSmoother>()
    private val bands = HashMap<String, RadarBand>()
    private val tickGaps = ArrayList<TickGap>()
    private var lastTickMono: Long? = null
    private var dropped = 0L

    /** Salt of [peerId]: new with every log, never written anywhere, so a hash can't be tied to a device outside it. */
    private val ownSalt: Long = random.nextLong()

    /** [ownSalt], or in a run the run's ([setRun]): every device of the run hashes a sender the same way. */
    private var salt: Long = ownSalt

    private var run: String? = null

    /** The [LabFields.SEQ] of the next event: starts at 1 and never goes back, not even on [clear]. */
    var nextSeq: Long = 1
        private set

    /** The oldest event still in the ring; null: none. Older ones were dropped (or cleared) before any upload. */
    val firstKeptSeq: Long? get() = ring.firstOrNull()?.seq

    /** The run on the server this device is in ([setRun]); null: none. */
    val runId: String? get() = run

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

    /** Every line as it is written, besides the ring: the Mac streams its log into a file (docs/radio-lab.md §6). */
    var onLine: ((String) -> Unit)? = null

    /** The log's device clock: for the parts that measure against it (the clock sync, the controller). */
    fun deviceNow(): Long = deviceTimeMillis()

    /** The log's monotonic clock. */
    fun monoNow(): Long = monotonicMillis()

    /** The server's clock as this device knows it: its own plus the last measured offset. */
    fun serverNow(): Long = deviceTimeMillis() + (mutableClock.value?.offsetMillis ?: 0L)

    private val lastHeard = HashMap<String, Long>()

    /** When [token] was last heard (device clock); null: not since the start or [clear]. */
    fun lastHeardMillis(token: String): Long? = lastHeard[token]

    fun setLabel(label: String) {
        mutableLabel.value = label.trim().ifEmpty { DEFAULT_LABEL }
    }

    /**
     * The run on the server this device is in, and the run's salt for [peerId] (hex, the same on every device of the
     * run, so the report tells a sender apart across the devices); null, null: no run, the log's own salt again.
     */
    fun setRun(runId: String?, saltHex: String?) {
        run = runId
        salt = saltHex?.takeIf { runId != null }?.let(::saltOf) ?: ownSalt
    }

    /** Writes an event of kind [k] with the common fields and [fields]. */
    fun event(k: String, fields: JsonObjectBuilder.() -> Unit = {}) {
        if (!isEnabled || !isRecording) return
        val dt = deviceTimeMillis()
        val t = dt + (mutableClock.value?.offsetMillis ?: 0L)
        val seq = nextSeq++
        val built = buildJsonObject {
            put(LabFields.T, t)
            put(LabFields.DT, dt)
            put(LabFields.MONO, monotonicMillis())
            put(LabFields.DEV, mutableLabel.value)
            put(LabFields.K, k)
            put(LabFields.APP, appState())
            put(LabFields.SEQ, seq)
            put(LabFields.RUN, run)
            fields()
        }
        // A field without a value is left out rather than written as null: the lines stay short.
        val line = JsonObject(built.filterValues { it !is JsonNull }).toString()
        ring.addLast(Entry(seq, t, line.encodeToByteArray()))
        onLine?.invoke(line)
        if (ring.size > capacity) {
            ring.removeFirst()
            dropped++
        }
        kinds[k] = (kinds[k] ?: 0) + 1
        mutableCount.value += 1
    }

    /**
     * The header: who this device is, and the lab's mode. At the start and with every export; in a run (the run's id
     * is in every event) also the label the device joined with.
     */
    fun session(model: String?, os: String?, build: String?, commit: String?, mode: String?) = event("session") {
        put("schema", LabSchema.VERSION)
        put("model", model)
        put("os", os)
        put("build", build)
        put("commit", commit)
        put("mode", mode)
        if (run != null) put("label", mutableLabel.value)
    }

    /**
     * The run on the server moved to the step [index] (0-based) [id] «[title]», or started it again (a REPEAT): the
     * report's stretches begin here. [revision]: the run's control revision the step started with.
     */
    fun step(index: Int, id: String, title: String, revision: Long) = event("step") {
        put("index", index)
        put("id", id)
        put("title", title)
        put("revision", revision)
    }

    /**
     * A request to the lab's server routes: [action] `join`, `state`, `upload` or `advance`, whether it went through
     * ([ok]), the upload's events ([seqFrom]..[seqTo]) and [bytes] on the wire, how long it took ([millis]), the
     * [error] and how many events wait for an upload ([pending]).
     */
    fun net(
        action: String,
        ok: Boolean,
        seqFrom: Long? = null,
        seqTo: Long? = null,
        bytes: Int? = null,
        millis: Long? = null,
        error: String? = null,
        pending: Long? = null,
    ) = event("net") {
        put("action", action)
        put("ok", ok)
        put("seq_from", seqFrom)
        put("seq_to", seqTo)
        put("bytes", bytes)
        put("millis", millis)
        put("error", error)
        put("pending", pending)
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
        if (last != null && mono - last > LabSchema.TICK_GAP_MILLIS && tickGaps.size < MAX_TICK_GAPS) {
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
            lastHeard[token] = now
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
    fun lines(): List<String> = ring.map { it.bytes.decodeToString() }

    /**
     * The oldest kept events after [afterSeq] (the last one the server acknowledged), in order, as JSONL: at most
     * [maxEvents] and [maxBytes] (a single longer line goes alone); null when there are none.
     */
    fun pending(
        afterSeq: Long,
        maxEvents: Int = LabUpload.MAX_EVENTS,
        maxBytes: Int = LabUpload.MAX_BODY_BYTES,
    ): LabBatch? {
        // The ring is in seq order: the first entry after [afterSeq] by a binary search.
        var low = 0
        var high = ring.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (ring[middle].seq <= afterSeq) low = middle + 1 else high = middle
        }
        if (low == ring.size) return null
        val picked = ArrayList<Entry>()
        var size = 0
        for (index in low until ring.size) {
            val entry = ring[index]
            val lineSize = entry.bytes.size + 1
            if (picked.size >= maxEvents || (picked.isNotEmpty() && size + lineSize > maxBytes)) break
            picked += entry
            size += lineSize
        }
        val jsonl = ByteArray(size)
        var at = 0
        for (entry in picked) {
            entry.bytes.copyInto(jsonl, at)
            at += entry.bytes.size
            jsonl[at++] = '\n'.code.toByte()
        }
        return LabBatch(picked.first().seq, picked.last().seq, picked.first().t, picked.last().t, picked.size, jsonl)
    }

    /**
     * The file to share: `hovanki-lab-<label>-<UTC start>.jsonl` and a short text summary ([header] first: model, OS,
     * commit…) with the clock's offset, the count of every kind and the gaps in the ticks.
     */
    fun export(header: List<String> = emptyList()): LabExport {
        val stamp = LabSchema.fileStamp(startedAtMillis)
        val name = "hovanki-lab-${mutableLabel.value}-$stamp"
        val jsonl = buildString {
            for (entry in ring) {
                append(entry.bytes.decodeToString())
                append('\n')
            }
        }
        return LabExport("$name.jsonl", "$name.txt", jsonl, summary(header))
    }

    fun summary(header: List<String> = emptyList()): String = buildString {
        for (line in header) appendLine(line)
        appendLine("label: ${mutableLabel.value}, schema ${LabSchema.VERSION}")
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
            for (gap in tickGaps) appendLine("  ${LabSchema.formatUtc(gap.atMillis)} UTC: ${gap.millis / 1000.0} s")
        }
    }

    /** Forgets everything; a new start. The [nextSeq] goes on: an upload never sees a number twice. */
    fun clear() {
        ring.clear()
        kinds.clear()
        smoothers.clear()
        bands.clear()
        lastHeard.clear()
        tickGaps.clear()
        lastTickMono = null
        dropped = 0
        mutableCount.value = 0
        startedAtMillis = deviceTimeMillis()
    }

    private class TickGap(val atMillis: Long, val millis: Long)

    /** One line of the ring: its [seq], its server time [t] and the JSON. */
    private class Entry(val seq: Long, val t: Long, val bytes: ByteArray)

    /** The run's salt as a number: FNV-1a over the hex, the same on every device. */
    private fun saltOf(hex: String): Long {
        var hash = FNV_OFFSET
        for (char in hex.lowercase()) {
            hash = (hash xor char.code.toLong()) * FNV_PRIME
        }
        return hash
    }

    companion object {
        /** About an hour of 3 neighbours heard ~10 times a second, and everything else (docs/radio-lab.md §4.2). */
        const val CAPACITY = 250_000

        const val DEFAULT_LABEL = "A"

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
    }
}

/**
 * A slice of the log to upload ([LabLog.pending]): the events [seqFrom]..[seqTo] ([count] of them, fewer than the span
 * when some were dropped from the ring), written between [tFrom] and [tTo] (server time), as JSONL.
 */
data class LabBatch(
    val seqFrom: Long,
    val seqTo: Long,
    val tFrom: Long,
    val tTo: Long,
    val count: Int,
    val jsonl: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is LabBatch &&
        seqFrom == other.seqFrom &&
        seqTo == other.seqTo &&
        tFrom == other.tFrom &&
        tTo == other.tTo &&
        count == other.count &&
        jsonl.contentEquals(other.jsonl)

    override fun hashCode(): Int = 31 * seqFrom.hashCode() + jsonl.contentHashCode()
}

/** What «Export» hands to the system «Share»: the log and its summary. */
data class LabExport(val fileName: String, val summaryName: String, val jsonl: String, val summary: String)
