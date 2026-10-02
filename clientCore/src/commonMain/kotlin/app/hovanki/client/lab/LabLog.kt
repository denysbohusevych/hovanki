@file:OptIn(ExperimentalTime::class)

package app.hovanki.client.lab

import app.hovanki.device.lab.MotionFeatures
import app.hovanki.radar.AirFrame
import app.hovanki.radar.AirSecond
import app.hovanki.radar.Decoded
import app.hovanki.radar.RadarCatalog
import app.hovanki.radar.RadarTrace
import app.hovanki.radar.RadioApi
import app.hovanki.radar.SightingVia
import app.hovanki.shared.lab.ErrFields
import app.hovanki.shared.lab.FieldKinds
import app.hovanki.shared.lab.GpsFields
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.lab.LabRadarKinds
import app.hovanki.shared.lab.LabSchema
import app.hovanki.shared.lab.MarkFields
import app.hovanki.shared.lab.RxFields
import app.hovanki.shared.lab.SurveyFields
import app.hovanki.shared.lab.SyncFields
import app.hovanki.shared.lab.UiFields
import app.hovanki.shared.protocol.LabUpload
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.rules.OverflowArea
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
 * Never a coordinate in the lab: `gps` is an accuracy and an age. In memory only, a ring of [capacity] events; the
 * developer exports it by hand ([export]), and in a run on the server the lab uploads it ([pending], `LabUploader`).
 * Debug builds only: disabled ([isEnabled] false) it records nothing, and it records only while the lab runs
 * ([isRecording]). Main thread.
 *
 * The field log (docs/adr/0018-field-test-build.md §3.2, [startField]) is the same log in a real game of the field
 * build, whatever [isEnabled] says: only there `gps` carries the coordinates ([fix]), the radio's readings are thinned
 * to one event per peer and second ([FieldThinning]), the frames and the air to one per window, what the shadow's
 * channels read is a `shadow` reading ([frame]), and the game's own kinds come in ([sync], [ui], [perm], [err],
 * [playerMark], [survey]).
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

    /**
     * The [LabFields.SEQ] of the last event that is news: anything but the uploads' own `net` events. Nothing new
     * since the last upload: the timer has nothing worth sending (`LabUploader`).
     */
    var lastNewsSeq: Long = 0
        private set

    /** The oldest event still in the ring; null: none. Older ones were dropped (or cleared) before any upload. */
    val firstKeptSeq: Long? get() = ring.firstOrNull()?.seq

    /** The run on the server this device is in ([setRun]); null: none. */
    val runId: String? get() = run

    /** The field log's thinning while in a game's run ([startField]); null: the lab's log, every reading. */
    private var thinning: FieldThinning? = null

    /** In a game's field run ([startField]): coordinates allowed, readings thinned. */
    val isField: Boolean get() = thinning != null

    /** Events are written now: the lab enabled (or a field run) and recording. */
    val isWriting: Boolean get() = (isEnabled || thinning != null) && isRecording

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

    /**
     * A game's field run (docs/adr/0018-field-test-build.md §3): from now on the log records whatever [isEnabled]
     * says, in the run [runId] with its salt [saltHex], as [label] (the player), thinned by [thinning], and [fix]
     * writes the coordinates. The log is cleared first: nothing of before goes into the game's run.
     */
    fun startField(runId: String, saltHex: String, label: String, thinning: FieldThinning) {
        clear()
        this.thinning = thinning
        setRun(runId, saltHex)
        setLabel(label)
        isRecording = true
    }

    /**
     * Out of the game's run: the readings still in their windows are written, then nothing more is recorded (unless
     * the lab of a debug build records) and coordinates are dropped again. The log is kept for the last upload.
     */
    fun stopField() {
        if (thinning == null) return
        flushReadings(all = true)
        thinning = null
        setRun(null, null)
        isRecording = false
    }

    /** Writes an event of kind [k] with the common fields and [fields]. */
    fun event(k: String, fields: JsonObjectBuilder.() -> Unit = {}) {
        if (!isWriting) return
        val field = thinning
        if (field != null && k in FieldKinds.THROTTLED) {
            val peer = (buildJsonObject(fields)[RxFields.PEER] as? JsonPrimitive)?.content
            if (!field.allowThrottled(k, peer, deviceTimeMillis())) return
        }
        val dt = deviceTimeMillis()
        val t = dt + (mutableClock.value?.offsetMillis ?: 0L)
        val seq = nextSeq++
        if (k != NET) lastNewsSeq = seq
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
     * A request to the lab's server routes: [action] `join`, `state`, `upload`, `advance` or `uwb` (the UWB token
     * posted), whether it went through ([ok]), the upload's events ([seqFrom]..[seqTo]) and [bytes] on the wire, how
     * long it took ([millis]), the [error] and how many events wait for an upload ([pending]).
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
    ) = event(NET) {
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

    /**
     * Once a second while the process lives: a gap in the ticks is the app suspended. In the field, the readings'
     * windows that are over are written too.
     */
    fun tick(n: Long) {
        if (!isWriting) return
        flushReadings()
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

    /**
     * [mode]: `hider_name`, `hider_service_data`, `ibeacon`, `overflow_probe` (and a channel's id where it has no older
     * name); [payload]: what the advertisement has; [tech]: the channel ([app.hovanki.radar.RadarChannel.id]);
     * [layout]: the advertisement's bytes in words and, with `dropped`, what was left out ([error] says why).
     */
    fun adv(
        action: String,
        mode: String,
        token: String? = null,
        payload: String? = null,
        error: String? = null,
        tech: String? = null,
        layout: String? = null,
    ) = event("adv") {
        put("action", action)
        put("mode", mode)
        put("tech", tech)
        put("token", token)
        put("payload", payload)
        put("layout", layout)
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
     * [via] of the channel [tech], from [peer] (the OS's id; hashed here). [atMillis]: when the platform heard it,
     * device clock. A reading with a token moves the lab's smoothed band for it ([band] on a change). In the field
     * one `rx` per peer, channel and window ([FieldThinning]): the count, the median and the loudest.
     */
    fun rx(
        token: String?,
        rssi: Int,
        api: RadioApi,
        via: SightingVia,
        peer: String? = null,
        atMillis: Long? = null,
        tech: String? = null,
    ) {
        if (!isWriting) return
        val now = deviceTimeMillis()
        val field = thinning
        if (field != null) {
            val key = FieldThinning.RxKey(token, api.key, via.key, peer?.let(::peerId), tech?.ifEmpty { null })
            field.rx(key, rssi, atMillis ?: now).forEach(::writeReadings)
        } else {
            event("rx") {
                put("token", token)
                put("rssi", rssi)
                put("api", api.key)
                put("via", via.key)
                put("tech", tech?.ifEmpty { null })
                put("peer", peer?.let(::peerId))
                if (atMillis != null && now - atMillis > 0) put("ago", now - atMillis)
            }
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

    /**
     * A frame of the radar's own a channel read ([decoded]: the channel's id and what it read, [RadarTrace.frame]),
     * whole: `tech` (the first channel), `via`, `token` (and `candidates` when the frame may carry several), what the
     * platform gave (`name`, `uuids`, `overflow` as the table's bits, `svcdata` and `mfr` in hex by UUID and company
     * id, `tx`, `conn`), `rssi`, `peer` (hashed), `api`, `hex` (the raw record, Android) and `ago` (how long ago the
     * platform heard it, as [rx]). The tokens are the lab's own; a frame has no position. In a field run a frame a
     * channel of the shadow read ([shadowTechs]: the overflow mask, docs/adr/0018-field-test-build.md §4 B) is also a
     * `shadow` reading ([LabRadarKinds.SHADOW]), every one of them: the report counts what the shadow would have heard.
     */
    fun frame(frame: AirFrame, decoded: List<Pair<String, Decoded>>) {
        if (!isWriting) return
        val now = deviceTimeMillis()
        if (isField) {
            decoded.firstOrNull { it.first in shadowTechs }?.let { (tech, read) -> shadowReading(tech, read, frame) }
        }
        val first = decoded.firstOrNull()
        event("frame") {
            put("tech", first?.first)
            if (decoded.map { it.first }.distinct().size > 1) {
                put("techs", JsonArray(decoded.map { it.first }.distinct().map(::JsonPrimitive)))
            }
            put("via", first?.second?.via?.key)
            put("token", first?.second?.token)
            val candidates = first?.second?.candidates.orEmpty()
            if (candidates.size > 1) put("candidates", JsonArray(candidates.map(::JsonPrimitive)))
            put("name", frame.name)
            if (frame.serviceUuids.isNotEmpty()) put("uuids", JsonArray(frame.serviceUuids.map(::JsonPrimitive)))
            if (frame.overflowUuids.isNotEmpty()) {
                // The table's bits; a UUID not in the table (none should be) as it came.
                val bits = frame.overflowUuids.map { uuid -> OverflowArea.bitOf(uuid)?.let(::JsonPrimitive) }
                put("overflow", JsonArray(bits.zip(frame.overflowUuids) { bit, uuid -> bit ?: JsonPrimitive(uuid) }))
            }
            if (frame.serviceData.isNotEmpty()) {
                put("svcdata", buildJsonObject { for ((uuid, data) in frame.serviceData) put(uuid, hexOf(data)) })
            }
            if (frame.manufacturerData.isNotEmpty()) {
                put(
                    "mfr",
                    buildJsonObject {
                        for ((company, data) in frame.manufacturerData) put(companyKey(company), hexOf(data))
                    },
                )
            }
            put("tx", frame.txPower)
            put("conn", frame.connectable)
            put("rssi", frame.rssi)
            put("peer", frame.peer?.let(::peerId))
            put("api", frame.api.key)
            put("hex", frame.hex())
            if (now - frame.atMillis > 0) put("ago", now - frame.atMillis)
        }
    }

    /**
     * One second of the frames no channel read ([RadarTrace.air]): `frames` in all, `ibeacons`, overflow `masks`,
     * `apple` (with Apple's manufacturer data) and every mask's `bits`: the street's noise.
     */
    fun air(second: AirSecond) = event("air") {
        put("frames", second.frames)
        put("ibeacons", second.iBeacons)
        put("masks", second.masks)
        put("apple", second.apple)
        put("bits", JsonArray(second.maskBits.sorted().map(::JsonPrimitive)))
    }

    /**
     * What a channel in the shadow read ([LabRadarKinds.SHADOW], the field log): `tech`, `token` (and `tokens` when a
     * damaged mask gives several), `rssi`, `api`, `via`, `peer` (hashed), `ago`; never the game's.
     */
    private fun shadowReading(tech: String, read: Decoded, frame: AirFrame) {
        val now = deviceTimeMillis()
        event(LabRadarKinds.SHADOW) {
            put("tech", tech)
            put("token", read.token)
            if (read.candidates.size > 1) put("tokens", JsonArray(read.candidates.map(::JsonPrimitive)))
            put("rssi", frame.rssi)
            put("api", frame.api.key)
            put("via", read.via.key)
            put("peer", frame.peer?.let(::peerId))
            if (now - frame.atMillis > 0) put("ago", now - frame.atMillis)
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

    /**
     * A knock the accelerometer felt ([app.hovanki.device.lab.ImpactDetector]): `peak`, |magnitude − 1| in g, and
     * `ago`, how long before this event it was. The sensors run on their own clock (since the boot), not the device's:
     * the lab measures [agoMillis] on theirs, against the newest reading, and the report puts the knock at `t − ago`
     * (docs/adr/0017-radar-techniques-and-big-run.md §3, the touch calibration).
     */
    fun impact(peakG: Double, agoMillis: Long) = event("impact") {
        put("peak", round(peakG, 2))
        put("ago", agoMillis.coerceAtLeast(0))
    }

    /**
     * A technique's answer in the shadow of the game's ([tech]: `carry.v2`): the [state] it would say
     * (`in_pocket`, `in_hand`, `unknown`) and why ([reason]); nothing of it reaches the game.
     */
    fun shadow(tech: String, state: String, reason: String? = null) = event("shadow") {
        put("tech", tech)
        put("state", state)
        put("reason", reason)
    }

    /**
     * [kind]: `core_haptics`, `core_haptics_audio`, `impact`, `notify_silent_sound`, `notify_no_sound`, `vibrator`.
     */
    fun haptic(kind: String, result: String, error: String? = null, reason: String? = null, group: Int? = null) =
        event("haptic") {
            put("kind", kind)
            put("result", result)
            put("error", error)
            put("reason", reason)
            put("group", group)
        }

    /**
     * A step of the GATT link (`gatt.link`, docs/radar-run.md §5.2): [action] the link's trace
     * ([app.hovanki.radar.link.LinkTrace]: `connect`, `connected`, `wrote`, `notified`, `disconnected`…) or `reading`
     * (a token or an RSSI read over the link), with the peer ([peer], the OS's id; hashed here as in [rx]), the peer's
     * [token], the [rssi] (dBm, only the side that connected reads it) and the [error].
     */
    fun link(action: String, peer: String? = null, token: String? = null, rssi: Int? = null, error: String? = null) =
        event("link") {
            put("action", action)
            put("peer", peer?.let(::peerId))
            put("token", token)
            put("rssi", rssi)
            put("error", error)
        }

    /**
     * UWB ranging (`uwb.ni`, docs/radar-run.md §5.3): [action] `reading` with the distance [meters] (2 decimals) and
     * the direction [degrees] (whole, clockwise from where the phone points; none when iOS doesn't know it) to [peer]
     * (the run's label: it is no OS id), or a step of the session ([app.hovanki.radar.RangeTrace]: `session_start`,
     * `config`, `running`, `suspended`, `removed`, `invalidated`…) with its [error]. The discovery tokens are never
     * written.
     */
    fun range(
        action: String,
        peer: String? = null,
        meters: Double? = null,
        degrees: Double? = null,
        error: String? = null,
    ) = event("range") {
        put("action", action)
        put("peer", peer)
        put("m", meters?.takeIf { it.isFinite() }?.let { round(it, 2) })
        put("deg", degrees?.takeIf { it.isFinite() }?.roundToLong())
        put("error", error)
    }

    /**
     * A background mode (`mode.audio`, `mode.notification_wake`, `mode.live_activity`, docs/radar-run.md §5.1, §5.3):
     * [event] `on`, `off`, `failed` or `unavailable` as the lab switched it, or what the mode said while on
     * (`interruption_began`, `route_change`, `notification_sent`, `live_activity_started`…), with the [reason].
     */
    fun mode(mode: String, event: String, reason: String? = null) = event("mode") {
        put("mode", mode)
        put("event", event)
        put("reason", reason)
    }

    fun battery(level: Double?, state: String?, lowPower: Boolean?) = event("battery") {
        put("level", level?.let { round(it, 3) })
        put("state", state)
        put("low_power", lowPower)
    }

    /** The phone's thermal state by name (`nominal`, `fair`, `serious`, `critical`; Android's `none`, `light`…). */
    fun thermal(state: String) = event(FieldKinds.THERMAL) { put("state", state) }

    /** A GPS fix: its accuracy and how old it was. Never where. */
    fun gps(accuracyMeters: Double, ageMillis: Long) = event("gps") {
        put("acc", round(accuracyMeters, 1))
        put("age", ageMillis)
    }

    // The field log (docs/adr/0018-field-test-build.md §3.2): written only in a game's run, or by the lab where it
    // makes sense there too; every field optional.

    /**
     * A GPS fix of the game: in a field run where it was ([lat], [lon]), its [speed] and [bearing] where the phone
     * says; outside of one (the lab) only its accuracy and age, as [gps]. [accepted] / [rejected]: what the game made
     * of it, when known; [mock]: the OS says it was simulated. At most one per `gpsEveryMillis` (the caller's).
     */
    fun fix(
        lat: Double,
        lon: Double,
        accuracyMeters: Double,
        ageMillis: Long,
        speed: Double? = null,
        bearing: Double? = null,
        accepted: Boolean? = null,
        rejected: String? = null,
        mock: Boolean = false,
    ) {
        val where = isField
        event(FieldKinds.GPS) {
            if (where) {
                put(GpsFields.LAT, round(lat, COORDINATE_DIGITS))
                put(GpsFields.LON, round(lon, COORDINATE_DIGITS))
            }
            put(GpsFields.ACC, round(accuracyMeters, 1))
            put(GpsFields.AGE, ageMillis)
            put(GpsFields.SPEED, speed?.let { round(it, 1) })
            put(GpsFields.BEARING, bearing?.let { round(it, 0) })
            put(GpsFields.ACCEPTED, accepted)
            put(GpsFields.REJECTED, rejected)
            if (mock) put(GpsFields.MOCK, true)
        }
    }

    /**
     * A sync with the game's server by [transport] (`poll` / `socket`): whether it went through ([ok]), how long it
     * took ([millis]), the refusal's [code] (HTTP status or the socket's close code) and [error], the answer's [bytes]
     * where known, and the game's [phase] when it changed with this answer (from [from]).
     */
    fun sync(
        transport: String,
        ok: Boolean,
        millis: Long? = null,
        code: Int? = null,
        error: String? = null,
        bytes: Int? = null,
        phase: String? = null,
        from: String? = null,
    ) = event(FieldKinds.SYNC) {
        put(SyncFields.TRANSPORT, transport)
        put(SyncFields.OK, ok)
        put(SyncFields.MILLIS, millis)
        put(SyncFields.CODE, code)
        put(SyncFields.ERROR, error)
        put(SyncFields.BYTES, bytes)
        put(SyncFields.PHASE, phase)
        put(SyncFields.FROM, from)
    }

    /** A [screen] opened or closed ([what]: `open`, `close`, `tap`), a tap meaning [action] (`catch_claim`): no text. */
    fun ui(screen: String, what: String, action: String? = null) = event(FieldKinds.UI) {
        put(UiFields.SCREEN, screen)
        put(UiFields.EVENT, what)
        put(UiFields.ACTION, action)
    }

    /** The app's permissions now, by name ([PermFields]): `always`, `when_in_use`, `denied`, `on`, `off`… */
    fun perm(states: Map<String, String>) = event(FieldKinds.PERM) {
        for ((name, state) in states) put(name, state)
    }

    /** An exception caught [where]: its class and message (cut short), the Sentry event's id if it went there. */
    fun err(where: String, type: String, message: String?, sentryId: String? = null) = event(FieldKinds.ERR) {
        put(ErrFields.WHERE, where)
        put(ErrFields.CLASS, type)
        put(ErrFields.MESSAGE, message?.take(MAX_TEXT))
        put(ErrFields.SENTRY_ID, sentryId)
    }

    /** «Something is wrong» from the player, with their few words if any (cut short). */
    fun playerMark(text: String?) = event(FieldKinds.MARK) {
        put(MarkFields.BY, MarkFields.PLAYER)
        put(MarkFields.TEXT, text?.trim()?.take(MAX_TEXT)?.ifEmpty { null })
    }

    /** The three questions after the game: [rating] 1–5, what [broken] (from the list) and in words, where [carry]. */
    fun survey(rating: Int?, broken: List<String>, text: String?, carry: String?) = event(FieldKinds.SURVEY) {
        put(SurveyFields.RATING, rating)
        put(SurveyFields.BROKEN, JsonArray(broken.map(::JsonPrimitive)))
        put(SurveyFields.TEXT, text?.trim()?.take(MAX_TEXT)?.ifEmpty { null })
        put(SurveyFields.CARRY, carry)
    }

    /** The readings' windows over by now (all of them with [all]) as `rx` events: the field log only. */
    private fun flushReadings(all: Boolean = false) {
        val field = thinning ?: return
        field.flush(deviceTimeMillis(), all).forEach(::writeReadings)
    }

    private fun writeReadings(summary: FieldThinning.RxSummary) = event(FieldKinds.RX) {
        put(RxFields.TOKEN, summary.key.token)
        put(RxFields.API, summary.key.api)
        put(RxFields.VIA, summary.key.via)
        put(RxFields.PEER, summary.key.peer)
        put(RxFields.TECH, summary.key.tech)
        put(RxFields.COUNT, summary.count)
        put(RxFields.RSSI, summary.median)
        put(RxFields.MAX, summary.max)
        val ago = deviceTimeMillis() - summary.toMillis
        if (ago > 0) put("ago", ago)
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

        /** The uploads' own kind: never news ([lastNewsSeq]). */
        const val NET = "net"

        /** A player's words, an exception's message: this long at most. */
        const val MAX_TEXT = 200

        /** About 1 cm: more than GPS knows. */
        private const val COORDINATE_DIGITS = 7

        /** The channels a field run's journal reads in the shadow ([frame]): their frames are `shadow` readings. */
        val shadowTechs: Set<String> = RadarCatalog.fieldShadow.map { it.id }.toSet()

        private const val MAX_TICK_GAPS = 1_000

        private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
        private const val FNV_PRIME = 1099511628211L

        val Off = LabLog(isEnabled = false)

        fun monotonicClock(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }

        private fun hexOf(data: ByteArray): String =
            data.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

        /** `004c`: a company id as its 4 hex digits. */
        private fun companyKey(id: Int): String = id.toString(16).padStart(4, '0')

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
