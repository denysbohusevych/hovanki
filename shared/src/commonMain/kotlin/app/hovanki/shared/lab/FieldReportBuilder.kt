package app.hovanki.shared.lab

import app.hovanki.shared.crash.SentryScrubber
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * A device of a game's run as the server knows it: its [label] (the player's id, or `server`), its id and what it said
 * at its join (the log's own `session` says it again, and wins).
 */
data class FieldReportDevice(
    val label: String,
    val deviceId: String,
    val model: String? = null,
    val os: String? = null,
    val build: String? = null,
)

/**
 * Computes a game's field report (docs/adr/0018-field-test-build.md §6, [FieldReport]) from its devices' logs, the
 * phones' and the server's, a time window at a time ([window], fed by [FieldReportStream]): what it keeps between
 * windows is numbers per player and per pair, never the logs, so a big game's journal is never in memory at once.
 * The work that needs both players' GPS around a moment (the pairs' distances, interpolated between the fixes; never
 * written out) waits one window, for the fixes after it. [digest]: where the lines of `digest.jsonl` go, a window's at
 * a time in time order ([FieldDigest]); null: none kept. Players are P1…Pn in the order of [devices] (then as they turn
 * up); coordinates never leave this class, texts are scrubbed. Pure: the caller gives the time.
 */
class FieldReportBuilder(
    private val runId: String,
    private val gameId: String?,
    devices: List<FieldReportDevice>,
    private val options: Options = Options(),
    private val digest: ((List<String>) -> Unit)? = null,
) {
    /** [maxTechniqueEvents]: the readings and touches kept for the lab's techniques ([LabTechniques]), at most. */
    data class Options(val maxTechniqueEvents: Int = 200_000)

    private val players = LinkedHashMap<String, PlayerState>()
    private val deviceCount = HashMap<String, Int>()

    init {
        for (device in devices) addDevice(device)
    }

    /** A device that joined after the builder started (the live report). */
    fun addDevice(device: FieldReportDevice) {
        if (device.label == FieldKinds.SERVER_DEVICE || device.label == MarkFields.STAFF) return
        val p = player(device.label)
        p.model = p.model ?: device.model
        p.os = p.os ?: device.os
        p.build = p.build ?: device.build
        deviceCount[device.label] = (deviceCount[device.label] ?: 0) + 1
    }

    // The game, by the server's events

    private val phases = ArrayList<Pair<Long, String>>()
    private var roundStart: Long? = null
    private var roundEnd: Long? = null
    private var firstMillis: Long? = null
    private var lastMillis: Long? = null
    private var windows = 0
    private var finished = false

    private val timeline = ArrayList<FieldReportEntry>()
    private var timelineDropped = 0

    /** The timeline's entries kept by kind: the noisy kinds stop at [MAX_TIMELINE_PER_KIND]. */
    private val timelineKinds = HashMap<String, Int>()
    private val marks = ArrayList<FieldReportMark>()

    /** Per mark (same index): what the caps left out of the timeline and the anomalies within its ±30 s. */
    private val markDropped = ArrayList<MutableList<Pair<Long, String>>>()

    /** The last of what the caps left out, for a mark read after it ([MAX_RECENT_DROPPED]). */
    private val recentDropped = ArrayDeque<Pair<Long, String>>()
    private val anomalies = ArrayList<FieldReportAnomaly>()
    private val anomalyCounts = LinkedHashMap<String, Int>()
    private val problems = LinkedHashSet<String>()
    private var badLines = 0L
    private var outside = 0L

    private val server = ServerState()
    private val shadow = ShadowState()

    /** Claim → what the proximity rule said in the shadow. */
    private val claimShadow = HashMap<String, Boolean>()

    /** Token → the players who advertised it. */
    private val owners = HashMap<String, MutableSet<String>>()

    // The radar

    private val rssi = HashMap<RssiKey, IntHistogram>()

    /** Bluetooth against GPS ([FieldReportBtGps]): the same keys as [rssi], with the band's agreement. */
    private val btGps = HashMap<RssiKey, BtStats>()

    /** The bands the server showed per observer and heard player (`observer shl 10 or heard`), and the shadow's. */
    private val bandChanges = HashMap<Int, Changes>()
    private val shadowChanges = HashMap<Int, Changes>()

    /** The pairs' minutes not yet written: (minute, a, b) → what was seen ([FieldPairs]). */
    private val pairMinutes = HashMap<Long, PairAcc>()
    private var worstMinutes: List<FieldReportPairMinute> = emptyList()
    private val pairSeconds = LinkedHashMap<String, Int>()
    private val coverage = LinkedHashMap<String, IntArray>()
    private val masks = LinkedHashMap<String, MaskCount>()
    private val zeroPoints = ArrayList<FieldReportZero>()
    private val buttonTouches = ArrayList<Pair<Long, Pair<String, String>>>()
    private val techniqueEvents = ArrayList<LabEvent>()
    private var techniquesOverflow = false

    // The windows

    private var current: Pending? = null
    private var deferred: Pending? = null
    private var previousRx: List<Rx> = emptyList()

    /** Where the local flat projection of the fixes starts: the game's first fix. */
    private var origin: Pair<Double, Double>? = null
    private val minutes = HashMap<Long, MinuteStats>()
    private val digestLines = ArrayList<DigestLine>()
    private var digestOrder = 0L

    /** The digest's lines went out up to here: a later line found for an earlier time is stamped here. */
    private var digestEmittedBefore = Long.MIN_VALUE

    /**
     * The events of `[start, end)` on the server's clock (and the late ones of a phone that uploaded late), in time
     * order. The window before it is finished now that its next fixes are in.
     */
    fun window(start: Long, end: Long, events: List<LabEvent>) {
        check(!finished) { "The report is finished" }
        windows++
        val pending = Pending(start, end)
        current = pending
        for (event in events) ingest(event, pending)
        processDeferred()
        deferred = pending
        current = null
    }

    /** Something wrong with the logs that the report should say: a problem, once. */
    fun problem(text: String) {
        problems += text
    }

    /**
     * Lines the logs had that are no events, and events outside the run's time, read so far ([FieldReportStream]'s
     * counters): the report's problems, the latest counts replacing the earlier ones.
     */
    fun streamCounts(badLines: Long, outside: Long) {
        this.badLines = badLines
        this.outside = outside
    }

    /** The report so far ([final]: the game is over and its whole log read: what waits is done, the digest closed). */
    fun report(nowMillis: Long, final: Boolean): FieldReport {
        processDeferred()
        if (final) finish()
        return build(nowMillis, final)
    }

    /** The first line of `digest.jsonl`: the run and its players (aliases, models, systems). */
    fun digestHeader(): String = buildJsonObject {
        put("k", "digest")
        put("schema", FieldDigest.SCHEMA)
        put("run", runId)
        put(
            "pairs",
            buildJsonObject {
                put("within_m", FieldPairs.METERS)
                put("cap_per_minute", FieldPairs.ROWS_PER_MINUTE)
                put(
                    "note",
                    "k=pair: a pair of players per minute within within_m by GPS or heard; at most cap_per_minute " +
                        "rows a minute (heard first, then nearest), a k=pairs_cut line counts the rest; a before b; " +
                        "rssi_ab = a's signal heard by b; band = loudest the game showed either way, " +
                        "band_off_m = meters beyond what the band can mean, less the GPS error",
                )
            },
        )
        gameId?.let { put("game", it) }
        put(
            "players",
            JsonArray(
                players.values.map { p ->
                    buildJsonObject {
                        put("p", p.alias)
                        put("model", p.model)
                        put("os", p.os)
                        put("build", p.build)
                    }
                },
            ),
        )
    }.toString()

    // Reading the events

    private fun ingest(event: LabEvent, pending: Pending) {
        firstMillis = minOf(firstMillis ?: event.t, event.t)
        lastMillis = maxOf(lastMillis ?: event.t, event.t)
        when (event.dev) {
            FieldKinds.SERVER_DEVICE -> serverEvent(event, pending)
            MarkFields.STAFF -> if (event.k == FieldKinds.MARK) mark(event, null)
            else -> phoneEvent(player(event.dev), event, pending)
        }
    }

    private fun serverEvent(event: LabEvent, pending: Pending) {
        when (event.k) {
            ServerKinds.PHASE -> {
                val phase = event.string(ServerFields.PHASE) ?: return
                phases += event.t to phase
                if (phase in ROUND_PHASES && roundStart == null) roundStart = event.t
                if (phase == FINISHED) roundEnd = event.t
                gameEvent(event, null, "${event.string(ServerFields.FROM) ?: "?"} → $phase")
            }

            ServerKinds.CLAIM -> {
                val seeker = alias(event.string(ServerFields.SEEKER))
                val hider = alias(event.string(ServerFields.HIDER))
                val outcome = event.string(ServerFields.OUTCOME) ?: "?"
                val accept = event.boolean(ServerFields.SHADOW_ACCEPT)
                shadow.claims++
                if (accept != null) {
                    shadow.claimsWithShadow++
                    if (accept) shadow.shadowAccepted++
                    event.string(ServerFields.CATCH)?.let { claimShadow[it] = accept }
                }
                if (outcome != FieldAnomalies.CLAIM_OPEN) {
                    val s = event.string(ServerFields.SEEKER)?.let(players::get)
                    val h = event.string(ServerFields.HIDER)?.let(players::get)
                    if (s != null && h != null) {
                        pending.claims += Claim(event.t, s, h, outcome, event.double(ServerFields.DISTANCE))
                    }
                }
                val text = listOfNotNull(
                    "$seeker → $hider: $outcome",
                    event.double(ServerFields.DISTANCE)?.let { "GPS ≥ $it m" },
                    event.double(ServerFields.ESTIMATE)?.let { "likely $it m" },
                    accept?.let { "proximity in the shadow: ${if (it) "would take" else "would refuse"}" },
                    event.long(ServerFields.BURNING_SECONDS)?.let { "burning ${it}s" },
                ).joinToString(" · ")
                gameEvent(event, seeker, text)
            }

            ServerKinds.CATCH -> {
                val seeker = alias(event.string(ServerFields.SEEKER))
                val hider = alias(event.string(ServerFields.HIDER))
                val outcome = event.string(ServerFields.OUTCOME) ?: "?"
                if (outcome == CONFIRMED) {
                    shadow.catches++
                    if (claimShadow[event.string(ServerFields.CATCH)] == false) shadow.catchesShadowWouldRefuse++
                    val s = event.string(ServerFields.SEEKER)?.let(players::get)
                    val h = event.string(ServerFields.HIDER)?.let(players::get)
                    if (s != null && h != null) pending.catches += Catch(event.t, s, h)
                }
                gameEvent(event, seeker, "$seeker → $hider: $outcome (${event.string(ServerFields.REASON) ?: "-"})")
            }

            ServerKinds.DISPUTE -> {
                val text = if (event.string(ServerFields.EVENT) == "vote") {
                    "vote of ${alias(event.string(ServerFields.PLAYER))}: ${event.boolean(ServerFields.VOTE)}"
                } else {
                    "${alias(event.string(ServerFields.HIDER))} disputes ${alias(event.string(ServerFields.SEEKER))}"
                }
                gameEvent(event, null, text)
            }

            ServerKinds.REVEAL -> {
                val label = event.string(ServerFields.PLAYER)
                val what = event.string(ServerFields.EVENT) ?: "?"
                if (what == "start") label?.let(players::get)?.let { it.reveals++ }
                val text = listOfNotNull(
                    "${alias(label)} $what",
                    event.string(ServerFields.REASON),
                    event.double(ServerFields.SECONDS)?.let { "after ${round1(it)} s" },
                ).joinToString(" · ")
                gameEvent(event, alias(label), text)
            }

            ServerKinds.GLOW -> gameEvent(
                event,
                null,
                "glow ${event.int(ServerFields.INDEX) ?: "?"} ${event.string(ServerFields.EVENT) ?: "?"}",
            )

            ServerKinds.FIXES -> {
                val p = event.string(ServerFields.PLAYER)?.let(players::get) ?: return
                p.serverAccepted += event.int(ServerFields.ACCEPTED) ?: 0
                for ((key, value) in event.fields) {
                    if (!key.startsWith(ServerFields.REFUSED_PREFIX)) continue
                    val count = (value as? JsonPrimitive)?.content?.toIntOrNull() ?: continue
                    val reason = key.removePrefix(ServerFields.REFUSED_PREFIX)
                    p.serverRefused[reason] = (p.serverRefused[reason] ?: 0) + count
                }
            }

            ServerKinds.BAND -> {
                shadow.bandChanges++
                val band = event.string(ServerFields.BAND)
                val shadowBand = event.string(ServerFields.SHADOW_BAND)
                val observer = event.string(ServerFields.OBSERVER)?.let(players::get)
                val heard = event.string(ServerFields.HEARD)?.let(players::get)
                if (observer != null && heard != null && observer !== heard) {
                    val key = directed(observer, heard)
                    band?.let { bandChanges.getOrPut(key) { Changes() }.add(event.t, it) }
                    (shadowBand ?: band)?.let { shadowChanges.getOrPut(key) { Changes() }.add(event.t, it) }
                }
                if (shadowBand != null && shadowBand != band) {
                    shadow.bandShifted++
                    val key = "$band→$shadowBand"
                    shadow.shifts[key] = (shadow.shifts[key] ?: 0) + 1
                }
            }

            FieldKinds.SRV -> srv(event)
        }
    }

    private fun srv(event: LabEvent) {
        server.samples++
        server.syncs += event.long(SrvFields.SYNCS) ?: 0
        event.long(SrvFields.SYNC_P50)?.let { server.syncP50Max = maxOf(server.syncP50Max ?: it, it) }
        event.long(SrvFields.SYNC_P95)?.let { server.syncP95Max = maxOf(server.syncP95Max ?: it, it) }
        server.errors5xx += event.long(SrvFields.ERRORS_5XX) ?: 0
        server.errors429 += event.long(SrvFields.ERRORS_429) ?: 0
        event.long(SrvFields.HEAP_MB)?.let { server.heapMaxMb = maxOf(server.heapMaxMb ?: it, it) }
        event.long(SrvFields.HEAP_MAX_MB)?.let { server.heapLimitMb = it }
        event.double(SrvFields.CPU)?.let { server.cpuMax = maxOf(server.cpuMax ?: it, it) }
        server.dropped += event.long(SrvFields.DROPPED) ?: 0
        event.int(SrvFields.PLAYERS)?.let { server.playersMax = maxOf(server.playersMax ?: it, it) }
        event.int(SrvFields.SOCKETS)?.let { server.socketsMax = maxOf(server.socketsMax ?: it, it) }
        val found = FieldAnomalies.server(event.t, event.long(SrvFields.ERRORS_5XX), event.long(SrvFields.SYNC_P95))
        for (anomaly in found) {
            anomaly(anomaly)
            entry(FieldReportEntry(event.t, anomaly.kind, null, anomaly.detail))
        }
        digestLine(event.t) {
            put("k", FieldKinds.SRV)
            for (key in SRV_DIGEST) event.fields[key]?.let { put(key, it) }
        }
    }

    /** A moment of the game: into the timeline and the digest, the players by alias. */
    private fun gameEvent(event: LabEvent, player: String?, text: String) {
        entry(FieldReportEntry(event.t, event.k, player, text))
        digestLine(event.t) {
            put("k", event.k)
            for ((key, value) in event.fields) {
                if (key in PLAYER_FIELDS) {
                    put(key, alias((value as? JsonPrimitive)?.content))
                } else if (key !in HIDDEN_SERVER_FIELDS) {
                    put(key, value)
                }
            }
        }
    }

    private fun phoneEvent(p: PlayerState, event: LabEvent, pending: Pending) {
        p.events++
        val t = event.t
        p.first = minOf(p.first ?: t, t)
        val last = p.lastT
        if (last != null && t > last) {
            if (p.lastApp in FieldAnomalies.BACKGROUND_STATES) background(p, last, t)
            val start = roundStart
            if (start != null && inRound(t)) {
                FieldAnomalies.journalGap(p.alias, maxOf(last, start), t, p.lastApp, p.lastLife)?.let(::anomaly)
            }
        }
        if (last == null || t >= last) {
            p.lastT = t
            p.lastApp = event.app
        }
        when (event.k) {
            FieldKinds.SESSION -> {
                event.string("model")?.let { p.model = it }
                event.string("os")?.let { p.os = it }
                event.string("build")?.let { p.build = it }
            }

            FieldKinds.LIFE -> p.lastLife = event.string("event")

            FieldKinds.GPS -> gps(p, event)

            FieldKinds.SYNC -> sync(p, event)

            FieldKinds.BATTERY -> {
                if (event.boolean("low_power") == true) p.lowPower = true
                val level = event.double("level") ?: return
                if (p.batteryFirst == null) p.batteryFirst = t to level
                p.batteryLast = t to level
                minute(p, t)?.battery = level
            }

            FieldKinds.THERMAL -> event.string("state")?.let { if (it !in p.thermal) p.thermal += it }

            FieldKinds.PERM -> for ((key, value) in event.fields) {
                val state = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                p.permissions[key] = state
            }

            FieldKinds.ERR -> {
                p.errors++
                if (event.string(ErrFields.SENTRY_ID) != null) p.sentry++
                anomaly(
                    FieldAnomalies.error(
                        p.alias,
                        t,
                        event.string(ErrFields.CLASS),
                        event.string(ErrFields.WHERE),
                        event.string(ErrFields.MESSAGE),
                        event.string(ErrFields.SENTRY_ID),
                    ),
                )
            }

            FieldKinds.MARK -> if (event.string("action") == TouchDetector.TOUCH_ACTION) {
                // «We touched» on the touch card: the touch detector's truth, no «something is wrong».
                technique(event, p)
                TouchDetector.touchPair(event)?.split('|')?.let { (a, b) -> buttonTouches += t to (a to b) }
            } else {
                p.marks++
                mark(event, p)
            }

            FieldKinds.SURVEY -> {
                p.survey = FieldReportSurvey(
                    rating = event.int(SurveyFields.RATING),
                    broken = event.strings(SurveyFields.BROKEN),
                    carry = event.string(SurveyFields.CARRY),
                    text = event.string(SurveyFields.TEXT)?.let(SentryScrubber::text),
                )
                digestLine(t) {
                    put("k", FieldKinds.SURVEY)
                    put("p", p.alias)
                    put(SurveyFields.RATING, event.int(SurveyFields.RATING))
                    put(SurveyFields.BROKEN, JsonArray(event.strings(SurveyFields.BROKEN).map(::JsonPrimitive)))
                    put(SurveyFields.CARRY, event.string(SurveyFields.CARRY))
                    put(SurveyFields.TEXT, event.string(SurveyFields.TEXT)?.let(SentryScrubber::text))
                }
            }

            "carry" -> event.string("state")?.let { p.carry.add(t, it) }

            LabRadarKinds.ADV -> {
                event.string("token")?.let { owners.getOrPut(it) { mutableSetOf() } += p.label }
                when (event.string("action")) {
                    // One `adv` a channel on the air: an Android hider's service data says its layout, the other
                    // channels of the same advertisement only that the radio is on.
                    "start" -> {
                        val layout = event.string("tech")?.takeIf { it.startsWith(SERVICE_DATA) }
                            ?.removePrefix(SERVICE_DATA)
                        if (layout != null) {
                            p.radio.add(t, layout)
                        } else if (p.radio.at(t).let { it == null || it == OFF }) {
                            p.radio.add(t, ON)
                        }
                    }

                    "stop", "failed" -> p.radio.add(t, OFF)
                }
                if (event.string("action") == "start") technique(event, p)
            }

            FieldKinds.RX -> {
                val value = event.int(RxFields.RSSI) ?: return
                pending.rx += Rx(
                    t = t,
                    second = (t - (event.long("ago") ?: 0L)).floorDiv(1000L),
                    listener = p,
                    token = event.string(RxFields.TOKEN),
                    rssi = value,
                    max = event.int(RxFields.MAX) ?: value,
                    readings = event.int(RxFields.COUNT) ?: 1,
                    tech = event.string(RxFields.TECH) ?: "${event.string(RxFields.API)}/${event.string(RxFields.VIA)}",
                    shadow = false,
                )
                technique(event, p)
            }

            LabRadarKinds.SHADOW -> {
                val token = event.string("token") ?: return
                pending.rx += Rx(
                    t = t,
                    second = t.floorDiv(1000L),
                    listener = p,
                    token = token,
                    rssi = event.int("rssi") ?: 0,
                    max = event.int("rssi") ?: 0,
                    readings = 1,
                    tech = event.string("tech") ?: "?",
                    shadow = true,
                )
            }

            "band" -> {
                val token = event.string("token")
                val band = event.string("band")
                if (token != null && band != null) minute(p, t)?.bands?.put(token, band)
            }

            IMPACT -> technique(event, p)
        }
    }

    private fun gps(p: PlayerState, event: LabEvent) {
        val t = event.t
        val acc = event.double(GpsFields.ACC)
        p.fixes++
        acc?.let { p.accuracy.add(it) }
        val m = minute(p, t)
        m?.let { minute ->
            minute.fixes++
            acc?.let { minute.accuracy.add(it) }
        }
        p.lastGps?.let { before ->
            val gap = t - before
            if (gap > GPS_GAP_MILLIS) {
                p.gpsGaps++
                m?.let { it.gaps++ }
            }
            if (gap > p.longestGpsGap) p.longestGpsGap = gap
        }
        if (p.lastGps == null || t > p.lastGps!!) p.lastGps = t
        val lat = event.double(GpsFields.LAT) ?: return
        val lon = event.double(GpsFields.LON) ?: return
        if (event.boolean(GpsFields.MOCK) == true || (acc != null && acc > MAX_TRACK_ACCURACY)) return
        val fix = project(t, lat, lon, acc)
        p.lastFix?.let { before ->
            if (fix.t > before.t) {
                FieldAnomalies.gpsJump(p.alias, fix.t, distance(before, fix), fix.t - before.t)?.let {
                    p.jumps++
                    anomaly(it)
                }
            }
        }
        if (p.lastFix == null || fix.t >= p.lastFix!!.t) p.lastFix = fix
        p.track.add(fix)
    }

    private fun sync(p: PlayerState, event: LabEvent) {
        val t = event.t
        p.syncs++
        val m = minute(p, t)
        m?.let { it.syncs++ }
        if (event.string(SyncFields.TRANSPORT) == SyncFields.SOCKET) p.socketSyncs++
        if (event.boolean(SyncFields.OK) == true) {
            p.syncOk++
            val millis = event.long(SyncFields.MILLIS)
            millis?.let {
                p.syncMillis.add(it.toDouble())
                m?.syncMillis?.add(it.toDouble())
            }
            event.long(SyncFields.BYTES)?.let { p.syncBytes.add(it.toDouble()) }
            val stall = FieldAnomalies.syncStall(p.alias, t, waitedMillis = millis)
                ?: FieldAnomalies.syncStall(p.alias, t, failingSinceMillis = p.failingSince, lastError = p.lastError)
            if (stall != null) {
                p.stalls++
                anomaly(stall)
            }
            p.failingSince = null
        } else {
            p.syncErrors++
            m?.let { it.syncErrors++ }
            if (p.failingSince == null) p.failingSince = t
            p.lastError = event.string(SyncFields.ERROR) ?: event.int(SyncFields.CODE)?.toString()
        }
    }

    private fun mark(event: LabEvent, p: PlayerState?) {
        val text = event.string(MarkFields.TEXT) ?: event.string(MarkFields.LABEL)
        val scrubbed = text?.let(SentryScrubber::text)
        val by = event.string(MarkFields.BY) ?: if (p == null) MarkFields.STAFF else MarkFields.PLAYER
        val mark = FieldReportMark(event.t, p?.alias, by, scrubbed)
        marks += mark
        markDropped += recentDropped.filterTo(ArrayList()) { it.first in aroundOf(mark) }
        entry(FieldReportEntry(event.t, FieldKinds.MARK, p?.alias, "«${scrubbed ?: "something is wrong"}» ($by)"))
        digestLine(event.t) {
            put("k", FieldKinds.MARK)
            put("p", p?.alias)
            put(MarkFields.BY, by)
            put(MarkFields.TEXT, scrubbed)
        }
    }

    private fun technique(event: LabEvent, p: PlayerState) {
        if (techniquesOverflow) return
        if (techniqueEvents.size >= options.maxTechniqueEvents) {
            techniquesOverflow = true
            techniqueEvents.clear()
            techniqueEvents.trimToSize()
            return
        }
        // The techniques read players by their aliases: the pair of a touch's mark too.
        val fields = TouchDetector.touchPair(event)?.split('|')?.let { (a, b) ->
            val label = TouchDetector.LABEL_PREFIX + RunStep.pairKey(alias(a), alias(b))
            JsonObject(event.fields + ("label" to JsonPrimitive(label)))
        } ?: event.fields
        techniqueEvents += LabEvent(event.t, p.alias, event.k, event.app, event.mono, fields)
    }

    private fun background(p: PlayerState, from: Long, to: Long) {
        p.backgroundMillis += to - from
        // The digest's minute is the one of the event that ends it, its part within that minute: a minute before it
        // may have gone out already (a silent minute has no row at all).
        val minute = minute(p, to) ?: return
        minute.backgroundMillis += to - maxOf(from, minute.start)
    }

    // The pairs: a window's work that needs the fixes after it

    private fun processDeferred() {
        val pending = deferred ?: return
        deferred = null
        val next = current?.rx.orEmpty()
        val around = previousRx + pending.rx + next
        val heard = HashSet<Long>()
        for (rx in around) {
            if (rx.shadow) continue
            val sender = senderOf(rx) ?: continue
            heard += pairKey(sender.index, rx.listener.index, rx.second)
        }
        val counted = HashSet<Long>()
        for (rx in pending.rx) {
            val sender = senderOf(rx)
            if (rx.shadow) {
                mask(rx, sender)
                continue
            }
            if (sender == null) continue
            val key = pairKey(sender.index, rx.listener.index, rx.second)
            val first = counted.add(key)
            val stats = rx.listener.heard.getOrPut(sender.alias) { HeardStats() }
            if (first) stats.seconds++
            stats.readings += rx.readings
            stats.channels += rx.tech
            minute(rx.listener, rx.t)?.peers?.add(sender.index)
            val pair = pairAcc(rx.t, sender, rx.listener)
            pair.channels += rx.tech
            if (sender.index < rx.listener.index) {
                pair.rssiAb.add(rx.rssi.toDouble())
                pair.readingsAb += rx.readings
            } else {
                pair.rssiBa.add(rx.rssi.toDouble())
                pair.readingsBa += rx.readings
            }
            if (!first) continue
            val at = rx.second * 1000 + 500
            sampleBands(pair, at)
            val meters = pairDistance(sender, rx.listener, at) ?: continue
            // The seconds within [FieldPairs.METERS] come from the pairs' own pass (every second, heard or not).
            if (meters > FieldPairs.METERS) sampleDistance(pair, at, meters, sender, rx.listener)
            val platforms = "${sender.platform} → ${rx.listener.platform}"
            pairSeconds[platforms] = (pairSeconds[platforms] ?: 0) + 1
            val key2 = RssiKey(
                "${sender.model ?: "?"} → ${rx.listener.model ?: "?"}",
                "${sender.carry.at(at) ?: "-"}/${rx.listener.carry.at(at) ?: "-"}",
                bucket(meters),
            )
            rssi.getOrPut(key2) { IntHistogram() }.add(rx.rssi)
            val bt = btGps.getOrPut(key2) { BtStats() }
            bt.histogram.add(rx.rssi)
            val shown = bandChanges[directed(rx.listener, sender)]?.at(at)
            val off = FieldPairs.disagreement(
                shown,
                meters,
                FieldPairs.tolerance(sender.track.accuracyAt(at), rx.listener.track.accuracyAt(at)),
            )
            if (off != null) {
                bt.bandSeconds++
                if (off == 0.0) bt.agree++
            }
        }
        nearPairs(pending)
        coverage(pending, heard)
        for (claim in pending.claims) {
            val meters = pairDistance(claim.seeker, claim.hider, claim.t)
            FieldAnomalies.refusedNear(
                claim.seeker.alias,
                claim.hider.alias,
                claim.t,
                claim.outcome,
                meters,
                claim.serverMeters,
            )?.let(::anomaly)
        }
        for (catch in pending.catches) {
            val window = catch.t - ZERO_BEFORE_MILLIS..catch.t + ZERO_AFTER_MILLIS
            var aToB: Int? = null
            var bToA: Int? = null
            for (rx in around) {
                if (rx.shadow || rx.t !in window) continue
                val sender = senderOf(rx) ?: continue
                if (sender === catch.seeker && rx.listener === catch.hider) aToB = maxOf(aToB ?: rx.max, rx.max)
                if (sender === catch.hider && rx.listener === catch.seeker) bToA = maxOf(bToA ?: rx.max, rx.max)
            }
            zeroPoints += FieldReportZero(
                kind = CATCH_ZERO,
                atMillis = catch.t,
                a = catch.seeker.alias,
                b = catch.hider.alias,
                rssiAToB = aToB,
                rssiBToA = bToA,
                gpsMeters = pairDistance(catch.seeker, catch.hider, catch.t)?.let(::round1),
            )
        }
        previousRx = pending.rx
        // The fixes the next window's work may still look at: its start less the interpolation's reach.
        val keepFrom = pending.end - KEEP_FIXES_MILLIS
        for (p in players.values) p.track.dropBefore(keepFrom)
        emitDigest(pending.end)
    }

    /** The round's seconds of the window: who was near whom by GPS, both advertising, and whether one heard the other. */
    private fun coverage(pending: Pending, heard: Set<Long>) {
        val all = players.values.toList()
        if (all.size < 2) return
        val xs = DoubleArray(all.size)
        val ys = DoubleArray(all.size)
        val on = BooleanArray(all.size)
        val layouts = arrayOfNulls<String>(all.size)
        // Only the round's seconds, and only those the logs reached: a window may be far longer than the game.
        val from = maxOf(pending.start, roundStart ?: return, firstMillis ?: return)
        val to = minOf(pending.end, roundEnd ?: Long.MAX_VALUE, (lastMillis ?: return) + 1)
        var second = from.ceilDiv(1000L) * 1000
        while (second < to) {
            if (inRound(second)) {
                var any = 0
                for ((i, p) in all.withIndex()) {
                    val radio = p.radio.at(second)
                    val fix = if (radio != null && radio != OFF) p.track.at(second) else null
                    on[i] = fix != null
                    if (fix != null) {
                        xs[i] = fix.first
                        ys[i] = fix.second
                        layouts[i] = radio
                        any++
                    }
                }
                if (any >= 2) coverageSecond(all, xs, ys, on, layouts, second / 1000, heard)
            }
            second += 1000
        }
    }

    private fun coverageSecond(
        all: List<PlayerState>,
        xs: DoubleArray,
        ys: DoubleArray,
        on: BooleanArray,
        layouts: Array<String?>,
        second: Long,
        heard: Set<Long>,
    ) {
        val near = COVERAGE_METERS * COVERAGE_METERS
        for (s in all.indices) {
            if (!on[s]) continue
            for (l in all.indices) {
                if (l == s || !on[l]) continue
                val dx = xs[s] - xs[l]
                val dy = ys[s] - ys[l]
                if (dx * dx + dy * dy > near) continue
                val sender = all[s]
                val layout = layouts[s]?.takeIf { it != ON }
                val key = "${sender.platform}${layout?.let { "/$it" } ?: ""}|${all[l].platform}"
                val counts = coverage.getOrPut(key) { IntArray(2) }
                counts[0]++
                val si = sender.index
                val li = all[l].index
                if (pairKey(si, li, second - 1) in heard || pairKey(si, li, second) in heard ||
                    pairKey(si, li, second + 1) in heard
                ) {
                    counts[1]++
                }
            }
        }
    }

    private fun mask(rx: Rx, sender: PlayerState?) {
        val key = "${rx.tech}|${rx.listener.platform}|${sender?.platform ?: ""}"
        val count = masks.getOrPut(key) { MaskCount(rx.tech, rx.listener.platform, sender?.platform) }
        count.frames++
        if (sender != null) count.resolved++
    }

    private fun senderOf(rx: Rx): PlayerState? {
        val owner = owners[rx.token ?: return null]?.singleOrNull() ?: return null
        return players[owner]?.takeIf { it !== rx.listener }
    }

    /** The two players' GPS distance at [t], meters; null: either had no fix near enough. */
    private fun pairDistance(a: PlayerState, b: PlayerState, t: Long): Double? {
        val pa = a.track.at(t) ?: return null
        val pb = b.track.at(t) ?: return null
        val dx = pa.first - pb.first
        val dy = pa.second - pb.second
        return sqrt(dx * dx + dy * dy)
    }

    // The pairs' minutes (FieldPairs)

    /** The key of "[observer] heard [heard]" in [bandChanges]. */
    private fun directed(observer: PlayerState, heard: PlayerState): Int = (observer.index shl 10) or heard.index

    /** The minute of [t] for the pair [x], [y] (a is the one who joined first). */
    private fun pairAcc(t: Long, x: PlayerState, y: PlayerState): PairAcc {
        val a = if (x.index < y.index) x else y
        val b = if (x.index < y.index) y else x
        val minute = t.floorDiv(MINUTE)
        val key = ((minute and MINUTE_MASK) shl 20) or (a.index.toLong() shl 10) or b.index.toLong()
        return pairMinutes.getOrPut(key) { PairAcc(minute * MINUTE, a, b) }
    }

    /** What the game's radar showed either way at [t], into the minute's loudest band (and the shadow's). */
    private fun sampleBands(pair: PairAcc, t: Long) {
        for ((from, to) in listOf(pair.a to pair.b, pair.b to pair.a)) {
            val key = directed(from, to)
            bandChanges[key]?.at(t)?.let(pair::band)
            shadowChanges[key]?.at(t)?.let(pair::shadow)
        }
    }

    private fun sampleDistance(pair: PairAcc, t: Long, meters: Double, x: PlayerState, y: PlayerState) {
        pair.meters.add(meters)
        val a = if (x === pair.a) x else y
        val b = if (x === pair.a) y else x
        a.track.accuracyAt(t)?.let { pair.accA.add(it) }
        b.track.accuracyAt(t)?.let { pair.accB.add(it) }
        a.carry.at(t)?.let { pair.carryA.put(it, (pair.carryA[it] ?: 0) + 1) }
        b.carry.at(t)?.let { pair.carryB.put(it, (pair.carryB[it] ?: 0) + 1) }
    }

    /** Every second of the window: the pairs within [FieldPairs.METERS] by GPS get a sample in their minute. */
    private fun nearPairs(pending: Pending) {
        val all = players.values.toList()
        if (all.size < 2) return
        val from = maxOf(pending.start, firstMillis ?: return)
        val to = minOf(pending.end, (lastMillis ?: return) + 1)
        val near = FieldPairs.METERS * FieldPairs.METERS
        val spots = arrayOfNulls<Pair<Double, Double>>(all.size)
        var second = from.ceilDiv(1000L) * 1000
        while (second < to) {
            for ((i, p) in all.withIndex()) spots[i] = p.track.at(second)
            for (s in all.indices) {
                val ps = spots[s] ?: continue
                for (l in s + 1 until all.size) {
                    val pl = spots[l] ?: continue
                    val dx = ps.first - pl.first
                    val dy = ps.second - pl.second
                    val d2 = dx * dx + dy * dy
                    if (d2 > near) continue
                    val pair = pairAcc(second, all[s], all[l])
                    sampleDistance(pair, second, sqrt(d2), all[s], all[l])
                    sampleBands(pair, second)
                }
            }
            second += 1000
        }
    }

    /**
     * The pairs' minutes that started before [before]: the worst of them into the report's list, the rest as the
     * digest's lines, at most [FieldPairs.ROWS_PER_MINUTE] a minute (the heard first, then the nearest; the count of
     * the left out in a `pairs_cut` line).
     */
    private fun flushPairs(before: Long): List<DigestLine> {
        val due = pairMinutes.entries.filter { it.value.start < before }
        if (due.isEmpty()) return emptyList()
        for (entry in due) pairMinutes.remove(entry.key)
        val rows = due.map { it.value to it.value.toRow() }
        worstMinutes = FieldPairs.worst(worstMinutes + rows.map { it.second })
        if (digest == null) return emptyList()
        val lines = ArrayList<DigestLine>()
        for ((start, ofMinute) in rows.groupBy { it.second.atMillis }.entries.sortedBy { it.key }) {
            val ranked = ofMinute.sortedWith(
                compareBy(
                    { if (it.first.heard) 0 else 1 },
                    { it.second.gpsMin ?: Double.MAX_VALUE },
                    { it.first.a.index },
                    { it.first.b.index },
                ),
            )
            val kept = ranked.take(FieldPairs.ROWS_PER_MINUTE).sortedWith(
                compareBy({ it.first.a.index }, { it.first.b.index }),
            )
            for ((_, row) in kept) lines += DigestLine(start, -1, FieldPairs.line(row))
            val cut = ranked.size - kept.size
            if (cut > 0) {
                lines += DigestLine(
                    start,
                    -1,
                    buildJsonObject {
                        put("t", start)
                        put("k", FieldPairs.CUT_KIND)
                        put("dropped", cut)
                    }.toString(),
                )
            }
        }
        return lines
    }

    // Finishing

    private fun finish() {
        if (finished) return
        finished = true
        for (p in players.values) {
            val since = p.failingSince ?: continue
            val end = p.lastT ?: continue
            FieldAnomalies.syncStall(p.alias, end, failingSinceMillis = since, lastError = p.lastError)?.let {
                p.stalls++
                anomaly(it)
            }
        }
        emitDigest(Long.MAX_VALUE)
    }

    private fun build(nowMillis: Long, final: Boolean): FieldReport {
        val playerList = players.values.toList()
        val start = roundStart
        val end = roundEnd ?: lastMillis
        val dropouts = if (start == null || end == null) {
            emptyList()
        } else {
            playerList.mapNotNull { p ->
                val last = p.lastT ?: return@mapNotNull null
                if (last >= start && last < end - DROPOUT_MILLIS && p.survey == null) {
                    FieldReportDropout(p.alias, last)
                } else {
                    null
                }
            }
        }
        val ratings = playerList.mapNotNull { it.survey?.rating }
        val summary = FieldReportSummary(
            players = playerList.size,
            devices = playerList.sumOf { deviceCount[it.label] ?: 1 },
            models = counts(playerList.map { it.model ?: "?" }),
            os = counts(playerList.map { it.os ?: "?" }),
            builds = counts(playerList.map { it.build ?: "?" }),
            firstMillis = firstMillis,
            lastMillis = lastMillis,
            roundStartMillis = roundStart,
            roundEndMillis = roundEnd,
            roundSeconds = roundStart?.let { s -> (roundEnd ?: lastMillis)?.let { (it - s) / 1000 } },
            dropouts = dropouts,
            restarts = playerList.sumOf { ((deviceCount[it.label] ?: 1) - 1).coerceAtLeast(0) },
            errors = playerList.sumOf { it.errors },
            sentryEvents = playerList.sumOf { it.sentry },
            marks = marks.size,
            surveys = playerList.count { it.survey != null },
            ratings = ratings,
            ratingAverage = ratings.takeIf { it.isNotEmpty() }?.let { round1(it.average()) },
            broken = counts(playerList.flatMap { it.survey?.broken.orEmpty() }),
            carry = counts(playerList.mapNotNull { it.survey?.carry }),
        )
        val dropped = dropouts.associate { it.player to it.atMillis }
        val techniques = techniques()
        return FieldReport(
            runId = runId,
            gameId = gameId,
            computedAtMillis = nowMillis,
            final = final,
            windows = windows,
            summary = summary,
            timeline = timeline.sortedWith(compareBy({ it.atMillis }, { it.kind })),
            timelineDropped = timelineDropped,
            marks = marks.indices.sortedBy { marks[it].atMillis }.map { marks[it].copy(around = around(it)) },
            players = playerList.map { it.toReport(deviceCount[it.label] ?: 1, dropped[it.alias]) },
            server = server.toReport(),
            radar = FieldReportRadar(
                pairSeconds = pairSeconds.entries.sortedBy { it.key }.map { FieldReportCount(it.key, it.value) },
                rssi = rssi.entries.sortedWith(
                    compareBy({ it.key.models }, { it.key.carry }, { BUCKETS.indexOf(it.key.bucket) }),
                ).map { (key, histogram) ->
                    FieldReportRssi(
                        models = key.models,
                        carry = key.carry,
                        bucket = key.bucket,
                        seconds = histogram.count,
                        median = histogram.percentile(50),
                        p10 = histogram.percentile(10),
                        p90 = histogram.percentile(90),
                    )
                },
                zeroPoints = (zeroPoints + touchZeroPoints(techniques)).sortedBy { it.atMillis },
                coverage = coverage.entries.sortedBy { it.key }.map { (key, counts) ->
                    val (sender, listener) = key.split('|')
                    FieldReportCoverage(sender, listener, counts[0], counts[1], percent(counts[1], counts[0]))
                },
                masks = masks.values.sortedWith(compareBy({ it.tech }, { it.listener }, { it.sender ?: "" })).map {
                    FieldReportMask(it.tech, it.listener, it.sender, it.frames, it.resolved)
                },
                shadowRules = shadow.toReport(),
                techniques = techniques,
                btVsGps = btGps.entries.sortedWith(
                    compareBy({ it.key.models }, { it.key.carry }, { BUCKETS.indexOf(it.key.bucket) }),
                ).map { (key, bt) ->
                    FieldReportBtGps(
                        models = key.models,
                        carry = key.carry,
                        bucket = key.bucket,
                        seconds = bt.histogram.count,
                        median = bt.histogram.percentile(50),
                        p20 = bt.histogram.percentile(20),
                        p80 = bt.histogram.percentile(80),
                        bandSeconds = bt.bandSeconds,
                        bandAgree = bt.agree,
                    )
                },
                worstMinutes = worstMinutes,
            ),
            anomalies = anomalies.sortedWith(compareBy({ it.atMillis }, { it.kind }, { it.player ?: "" })),
            anomalyCounts = anomalyCounts.toMap(),
            problems = problems.toList() +
                listOfNotNull(
                    "$badLines lines that are no events".takeIf { badLines > 0 },
                    "$outside events outside the game's time, left out".takeIf { outside > 0 },
                    "The live report: the last minutes may be missing".takeIf { !final },
                ),
        )
    }

    private fun aroundOf(mark: FieldReportMark): LongRange =
        mark.atMillis - AROUND_MARK_MILLIS..mark.atMillis + AROUND_MARK_MILLIS

    /**
     * The timeline and the anomalies within [AROUND_MARK_MILLIS] of mark [index]: those kept, and those the caps left
     * out that were seen near it ([markDropped]).
     */
    private fun around(index: Int): List<String> {
        val mark = marks[index]
        val range = aroundOf(mark)
        val entries = timeline.filter {
            it.atMillis in range &&
                !(it.kind == FieldKinds.MARK && it.atMillis == mark.atMillis)
        }
            .map { it.atMillis to it.line() }
        val found = anomalies.filter { it.atMillis in range }.map { it.atMillis to it.line() }
        val dropped = markDropped[index].filter { it.first in range }
        return (entries + found + dropped).distinct().sortedBy { it.first }.take(MAX_AROUND).map { (t, text) ->
            val offset = (t - mark.atMillis) / 1000
            "${if (offset >= 0) "+" else ""}${offset}s $text"
        }
    }

    /**
     * Touches as «0 m»: the detector's when the techniques were computed (with their RSSI), and the button's presses
     * it did not find (no RSSI).
     */
    private fun touchZeroPoints(techniques: FieldReportTechniques?): List<FieldReportZero> {
        val found = techniques?.touches?.takeIf { techniques.computed }.orEmpty().map {
            val (a, b) = it.pair.split('|')
            FieldReportZero(
                TOUCH_ZERO,
                it.atMillis,
                a,
                b,
                it.rssi[Calibration.direction(a, b)],
                it.rssi[Calibration.direction(b, a)],
            )
        }
        val pressed = buttonTouches.map { (t, pair) ->
            FieldReportZero(TOUCH_ZERO, t, alias(pair.first), alias(pair.second))
        }
            .distinctBy { Triple(it.atMillis / BUTTON_SAME_MILLIS, minOf(it.a, it.b), maxOf(it.a, it.b)) }
            .filter { press ->
                found.none {
                    setOf(it.a, it.b) == setOf(press.a, press.b) &&
                        abs(it.atMillis - press.atMillis) <= TouchDetector.TRUTH_WINDOW_MILLIS
                }
            }
        return found + pressed
    }

    private fun techniques(): FieldReportTechniques? {
        if (techniquesOverflow) {
            return FieldReportTechniques(
                computed = false,
                note = "More than ${options.maxTechniqueEvents} readings and touches: merge the raw logs on a computer",
            )
        }
        if (techniqueEvents.none { it.k == FieldKinds.RX || it.k == IMPACT }) return null
        val sorted = techniqueEvents.sortedWith(compareBy({ it.t }, { it.dev }, { it.mono }))
        val sender: (String?) -> String = { token ->
            val owner = token?.let { owners[it] }?.singleOrNull()
            owner?.let(players::get)?.alias ?: (token?.let { "?$it" } ?: "?")
        }
        val marks = sorted.filter { it.k == FieldKinds.MARK }
        val touches = TouchDetector.find(sorted, sender, marks)
        val devices = sorted.mapTo(HashSet()) { it.dev }
        val techs = sorted.filter { it.k == FieldKinds.RX && it.int(RxFields.RSSI) != null }
            .filter { rx -> sender(rx.string(RxFields.TOKEN)).let { it != rx.dev && it in devices } }
            .map { LabMerge.techOf(it) }
            .distinct()
            .sorted()
        return FieldReportTechniques(
            touches = touches.map { touch ->
                LabReportTouch(
                    pair = touch.pairKey,
                    atMillis = touch.t,
                    rssi = touch.rssi,
                    peaksG = touch.peaksG.mapValues { (it.value * 100).roundToLong() / 100.0 },
                    markAtMillis = touch.truthT,
                )
            },
            touchSpreads = touches.spreads().map {
                LabReportTouchSpread(it.pairKey, it.direction, it.touches, it.spreadDb, it.driftDb)
            },
            missedTouches = TouchDetector.missed(marks, touches).size,
            without = WithoutChannel.all(sorted, sender, techs, BandErrors.secondsOf(sorted))
                .map { LabReportWithout(it.tech, it.seconds, it.same, it.onlyChannel) },
        )
    }

    // The digest

    private fun digestLine(t: Long, fields: JsonObjectBuilder.() -> Unit) {
        if (digest == null) return
        digestLines += DigestLine(
            t,
            digestOrder++,
            buildJsonObject {
                put("t", t)
                fields()
            }.toString(),
        )
    }

    private fun anomaly(anomaly: FieldReportAnomaly) {
        val count = (anomalyCounts[anomaly.kind] ?: 0) + 1
        anomalyCounts[anomaly.kind] = count
        // Per kind: a phone's flapping GPS early on crowds out no later stall (the counts keep them all).
        if (count <= MAX_ANOMALIES_PER_KIND) anomalies += anomaly else dropped(anomaly.atMillis, anomaly.line())
        // Found late (a stall is seen when it ends): stamped where the digest is, its start in `since`.
        val t = maxOf(anomaly.atMillis, digestEmittedBefore)
        digestLine(t) {
            put("k", "anomaly")
            put("kind", anomaly.kind)
            put("p", anomaly.player)
            if (t != anomaly.atMillis) put("since", anomaly.atMillis)
            anomaly.untilMillis?.let { put("until", it) }
            put("detail", anomaly.detail)
        }
    }

    /** Into the timeline: the game's moments and the marks always, the noisy kinds up to [MAX_TIMELINE_PER_KIND]. */
    private fun entry(entry: FieldReportEntry) {
        val count = (timelineKinds[entry.kind] ?: 0) + 1
        val cap = if (entry.kind in STRUCTURAL_KINDS) MAX_STRUCTURAL_TIMELINE else MAX_TIMELINE_PER_KIND
        if (count <= cap) {
            timelineKinds[entry.kind] = count
            timeline += entry
        } else {
            timelineDropped++
            dropped(entry.atMillis, entry.line())
        }
    }

    /** Left out by a cap: still in the ±30 s of the marks near it, those read so far and those read soon. */
    private fun dropped(t: Long, line: String) {
        val item = t to line
        for ((index, mark) in marks.withIndex()) {
            val near = markDropped[index]
            if (t in aroundOf(mark) && near.size < MAX_AROUND) near += item
        }
        recentDropped.addLast(item)
        if (recentDropped.size > MAX_RECENT_DROPPED) recentDropped.removeFirst()
    }

    private fun FieldReportEntry.line() = "$kind ${player?.let { "$it " } ?: ""}$text"

    private fun FieldReportAnomaly.line() = "$kind ${player?.let { "$it " } ?: ""}$detail"

    /** The minutes before [before] as lines, with the other lines of that time, in time order. */
    private fun emitDigest(before: Long) {
        val pairLines = flushPairs(before)
        val sink = digest ?: return
        // Pairs, not the map's entries: on Kotlin/Native an entry read after a removal throws.
        val due = minutes.entries.filter { it.value.start < before }
            .map { it.key to it.value }
            .sortedWith(compareBy({ it.second.start }, { it.second.player.index }))
        val lines = ArrayList<DigestLine>()
        for ((key, minute) in due) {
            minutes.remove(key)
            lines += DigestLine(minute.start, -1, minute.toLine())
        }
        lines += pairLines
        val ready = digestLines.filter { it.t < before }
        digestLines.removeAll { it.t < before }
        lines += ready
        if (before > digestEmittedBefore) digestEmittedBefore = before
        if (lines.isEmpty()) return
        lines.sortWith(compareBy({ it.t }, { it.order }))
        sink(lines.map { it.line })
    }

    private fun minute(p: PlayerState, t: Long): MinuteStats? {
        if (digest == null) return null
        val minute = t.floorDiv(MINUTE)
        return minutes.getOrPut((p.index.toLong() shl 40) or (minute and MINUTE_MASK)) {
            MinuteStats(p, minute * MINUTE)
        }
    }

    // Players

    private fun player(label: String): PlayerState = players.getOrPut(label) {
        PlayerState(label, "P${players.size + 1}", players.size)
    }

    /** A player's alias by their id in the game: `other` (no consent), `?` (unknown). */
    private fun alias(label: String?): String = when (label) {
        null, "?" -> "?"
        ServerFields.OTHER -> ServerFields.OTHER
        else -> players[label]?.alias ?: "?"
    }

    private fun inRound(t: Long): Boolean {
        var phase: String? = null
        for ((at, name) in phases) {
            if (at > t) break
            phase = name
        }
        return phase in ROUND_PHASES
    }

    private fun project(t: Long, lat: Double, lon: Double, acc: Double?): Fix {
        val (lat0, _) = origin ?: (lat to lon).also { origin = it }
        val x = lon * DEGREE * cos(lat0 * PI / 180.0)
        val y = lat * DEGREE
        return Fix(t, x, y, acc)
    }

    private class Fix(val t: Long, val x: Double, val y: Double, val acc: Double?)

    private fun distance(a: Fix, b: Fix): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    /** A player's fixes in time order, projected; where they were at a moment, interpolated. */
    private class Track {
        private val fixes = ArrayList<Fix>()

        fun add(fix: Fix) {
            if (fixes.isEmpty() || fixes.last().t <= fix.t) {
                fixes += fix
            } else {
                val index = fixes.binarySearch { it.t.compareTo(fix.t) }
                fixes.add(if (index < 0) -index - 1 else index, fix)
            }
        }

        fun dropBefore(t: Long) {
            val index = fixes.indexOfFirst { it.t >= t }
            if (index > 0) {
                fixes.subList(0, index).clear()
            } else if (index < 0) {
                fixes.clear()
            }
        }

        /** The accuracy of the fix nearest to [t] within [NEAREST_MILLIS], m; null: none or unknown. */
        fun accuracyAt(t: Long): Double? {
            if (fixes.isEmpty()) return null
            var index = fixes.binarySearch { it.t.compareTo(t) }
            if (index >= 0) return fixes[index].acc
            index = -index - 1
            val nearest = listOfNotNull(fixes.getOrNull(index - 1), fixes.getOrNull(index))
                .minBy { kotlin.math.abs(it.t - t) }
            return if (kotlin.math.abs(nearest.t - t) <= INTERPOLATE_MILLIS) nearest.acc else null
        }

        /** (x, y) at [t]: between the fixes around it, or the nearest within [NEAREST_MILLIS]; null: none. */
        fun at(t: Long): Pair<Double, Double>? {
            if (fixes.isEmpty()) return null
            var index = fixes.binarySearch { it.t.compareTo(t) }
            if (index >= 0) return fixes[index].x to fixes[index].y
            index = -index - 1
            val before = fixes.getOrNull(index - 1)
            val after = fixes.getOrNull(index)
            if (before != null && after != null && after.t - before.t <= INTERPOLATE_MILLIS) {
                val share = (t - before.t).toDouble() / (after.t - before.t)
                return (before.x + (after.x - before.x) * share) to (before.y + (after.y - before.y) * share)
            }
            val nearest = listOfNotNull(before, after).minBy { kotlin.math.abs(it.t - t) }
            return if (kotlin.math.abs(nearest.t - t) <= NEAREST_MILLIS) nearest.x to nearest.y else null
        }
    }

    /** A state that changes now and then (the carry monitor's, the radio's), and what it was at a moment. */
    private class Changes {
        private val times = ArrayList<Long>()
        private val values = ArrayList<String>()

        fun add(t: Long, value: String) {
            if (values.lastOrNull() == value && (times.lastOrNull() ?: Long.MIN_VALUE) <= t) return
            if (times.isEmpty() || times.last() <= t) {
                times += t
                values += value
            } else {
                var index = times.binarySearch(t)
                if (index < 0) index = -index - 1
                times.add(index, t)
                values.add(index, value)
            }
        }

        fun at(t: Long): String? {
            var index = times.binarySearch(t)
            if (index < 0) index = -index - 2
            return values.getOrNull(index)
        }
    }

    private class HeardStats(
        var seconds: Int = 0,
        var readings: Long = 0,
        val channels: MutableSet<String> = mutableSetOf(),
    )

    private inner class PlayerState(val label: String, val alias: String, val index: Int) {
        var model: String? = null
        var os: String? = null
        var build: String? = null
        val platform: String get() = platformOf(os)
        var events = 0L
        var first: Long? = null
        var lastT: Long? = null
        var lastApp: String? = null
        var lastLife: String? = null
        var backgroundMillis = 0L
        var fixes = 0
        val accuracy = Doubles()
        var lastGps: Long? = null
        var gpsGaps = 0
        var longestGpsGap = 0L
        var jumps = 0
        var lastFix: Fix? = null
        val track = Track()
        var serverAccepted = 0
        val serverRefused = LinkedHashMap<String, Int>()
        var syncs = 0
        var syncOk = 0
        var syncErrors = 0
        var socketSyncs = 0
        val syncMillis = Doubles()
        val syncBytes = Doubles()
        var failingSince: Long? = null
        var lastError: String? = null
        var stalls = 0
        var batteryFirst: Pair<Long, Double>? = null
        var batteryLast: Pair<Long, Double>? = null
        var lowPower = false
        val thermal = ArrayList<String>()
        val permissions = LinkedHashMap<String, String>()
        var errors = 0
        var sentry = 0
        var marks = 0
        var survey: FieldReportSurvey? = null
        val carry = Changes()
        val radio = Changes()
        val heard = LinkedHashMap<String, HeardStats>()
        var reveals = 0

        fun toReport(devices: Int, droppedAt: Long?): FieldReportPlayer {
            val battery = run {
                val first = batteryFirst
                val last = batteryLast
                val perHour = if (first != null && last != null && last.first - first.first >= BATTERY_SPAN_MILLIS) {
                    round1((first.second - last.second) * 100 / ((last.first - first.first) / 3_600_000.0))
                } else {
                    null
                }
                FieldReportBattery(first?.second, last?.second, perHour, lowPower)
            }
            return FieldReportPlayer(
                alias = alias,
                label = label,
                devices = devices,
                model = model,
                os = os,
                build = build,
                platform = platform,
                firstMillis = first,
                lastMillis = lastT,
                droppedAtMillis = droppedAt,
                events = events,
                gps = FieldReportGps(
                    fixes = fixes,
                    accP50 = accuracy.percentile(50)?.let(::round1),
                    accP95 = accuracy.percentile(95)?.let(::round1),
                    gaps = gpsGaps,
                    longestGapSeconds = longestGpsGap / 1000,
                    jumps = jumps,
                    serverAccepted = serverAccepted,
                    serverRefused = serverRefused.toMap(),
                ),
                sync = FieldReportSync(
                    count = syncs,
                    ok = syncOk,
                    errors = syncErrors,
                    p50 = syncMillis.percentile(50)?.roundToLong(),
                    p95 = syncMillis.percentile(95)?.roundToLong(),
                    socketPercent = percent(socketSyncs, syncs),
                    bytesP50 = syncBytes.percentile(50)?.roundToLong(),
                    bytesP95 = syncBytes.percentile(95)?.roundToLong(),
                    stalls = stalls,
                ),
                backgroundSeconds = backgroundMillis / 1000,
                battery = battery,
                thermal = thermal.toList(),
                permissions = permissions.toMap(),
                errors = errors,
                marks = marks,
                survey = survey,
                heard = heard.entries.sortedBy { it.key.removePrefix("P").toIntOrNull() ?: Int.MAX_VALUE }
                    .map { (peer, stats) ->
                        FieldReportHeard(peer, stats.seconds, stats.readings, stats.channels.sorted())
                    },
                reveals = reveals,
            )
        }
    }

    private class MinuteStats(val player: PlayerState, val start: Long) {
        var fixes = 0
        val accuracy = Doubles()
        var gaps = 0
        var syncs = 0
        val syncMillis = Doubles()
        var syncErrors = 0
        var battery: Double? = null
        var backgroundMillis = 0L
        val peers = HashSet<Int>()
        val bands = HashMap<String, String>()

        fun toLine(): String = buildJsonObject {
            put("t", start)
            put("k", FieldDigest.MINUTE)
            put("p", player.alias)
            put("gps", fixes)
            accuracy.percentile(50)?.let { put("acc", round1(it)) }
            if (gaps > 0) put("gaps", gaps)
            put("sync", syncs)
            syncMillis.percentile(50)?.let { put("sync_p50", it.roundToLong()) }
            syncMillis.percentile(95)?.let { put("sync_p95", it.roundToLong()) }
            if (syncErrors > 0) put("sync_err", syncErrors)
            battery?.let { put("battery", it) }
            if (backgroundMillis > 0) put("bg_s", (backgroundMillis + 500) / 1000)
            put("peers", peers.size)
            if (bands.isNotEmpty()) {
                val byBand = bands.values.groupingBy { it }.eachCount().entries.sortedBy { it.key }
                put("bands", JsonObject(byBand.associate { it.key to (JsonPrimitive(it.value) as JsonElement) }))
            }
        }.toString()
    }

    private inner class ServerState {
        var samples = 0
        var syncs = 0L
        var syncP50Max: Long? = null
        var syncP95Max: Long? = null
        var errors5xx = 0L
        var errors429 = 0L
        var heapMaxMb: Long? = null
        var heapLimitMb: Long? = null
        var cpuMax: Double? = null
        var dropped = 0L
        var playersMax: Int? = null
        var socketsMax: Int? = null

        fun toReport() = FieldReportServer(
            samples = samples,
            syncs = syncs,
            syncP50Max = syncP50Max,
            syncP95Max = syncP95Max,
            errors5xx = errors5xx,
            errors429 = errors429,
            heapMaxMb = heapMaxMb,
            heapLimitMb = heapLimitMb,
            cpuMax = cpuMax?.let { (it * 1000).roundToLong() / 1000.0 },
            dropped = dropped,
            playersMax = playersMax,
            socketsMax = socketsMax,
        )
    }

    private class ShadowState {
        var claims = 0
        var claimsWithShadow = 0
        var shadowAccepted = 0
        var catches = 0
        var catchesShadowWouldRefuse = 0
        var bandChanges = 0
        var bandShifted = 0
        val shifts = LinkedHashMap<String, Int>()

        fun toReport() = FieldReportShadowRules(
            claims = claims,
            claimsWithShadow = claimsWithShadow,
            shadowAccepted = shadowAccepted,
            catches = catches,
            catchesShadowWouldRefuse = catchesShadowWouldRefuse,
            bandChanges = bandChanges,
            bandShifted = bandShifted,
            shifts = shifts.entries.sortedBy { it.key }.map { FieldReportCount(it.key, it.value) },
        )
    }

    private class MaskCount(val tech: String, val listener: String, val sender: String?) {
        var frames = 0
        var resolved = 0
    }

    private data class RssiKey(val models: String, val carry: String, val bucket: String)

    private class BtStats {
        val histogram = IntHistogram()
        var bandSeconds = 0
        var agree = 0
    }

    /** What the minute of a pair showed: the seconds' distances, the RSSI each way, the bands, the carries. */
    private class PairAcc(val start: Long, val a: PlayerState, val b: PlayerState) {
        val meters = Doubles()
        val accA = Doubles()
        val accB = Doubles()
        val rssiAb = Doubles()
        val rssiBa = Doubles()
        var readingsAb = 0
        var readingsBa = 0
        val channels = HashSet<String>()
        val carryA = HashMap<String, Int>()
        val carryB = HashMap<String, Int>()
        var band: String? = null
        var shadowBand: String? = null

        val heard: Boolean get() = readingsAb + readingsBa > 0

        fun band(name: String) {
            if (FieldPairs.rank(name) > FieldPairs.rank(band)) band = name.lowercase()
        }

        fun shadow(name: String) {
            if (FieldPairs.rank(name) > FieldPairs.rank(shadowBand)) shadowBand = name.lowercase()
        }

        fun toRow(): FieldReportPairMinute {
            val median = meters.percentile(50)
            val accMedianA = accA.percentile(50)
            val accMedianB = accB.percentile(50)
            return FieldReportPairMinute(
                atMillis = start,
                a = a.alias,
                b = b.alias,
                gpsMedian = median?.let(::round1),
                gpsMin = meters.percentile(0)?.let(::round1),
                accA = accMedianA?.let(::round1),
                accB = accMedianB?.let(::round1),
                rssiAb = rssiAb.percentile(50)?.roundToInt(),
                rssiBa = rssiBa.percentile(50)?.roundToInt(),
                readingsAb = readingsAb,
                readingsBa = readingsBa,
                band = band,
                shadowBand = shadowBand,
                carryA = carryA.mode(),
                carryB = carryB.mode(),
                platA = a.platform,
                platB = b.platform,
                modelA = a.model,
                modelB = b.model,
                channels = channels.sorted(),
                disagreementM = FieldPairs.disagreement(band, median, FieldPairs.tolerance(accMedianA, accMedianB))
                    ?.let(::round1),
            )
        }

        private fun HashMap<String, Int>.mode(): String? =
            entries.sortedWith(compareBy({ -it.value }, { it.key })).firstOrNull()?.key
    }

    private class Rx(
        val t: Long,
        val second: Long,
        val listener: PlayerState,
        val token: String?,
        val rssi: Int,
        val max: Int,
        val readings: Int,
        val tech: String,
        val shadow: Boolean,
    )

    private class Claim(
        val t: Long,
        val seeker: PlayerState,
        val hider: PlayerState,
        val outcome: String,
        val serverMeters: Double?,
    )

    private class Catch(val t: Long, val seeker: PlayerState, val hider: PlayerState)

    private class Pending(val start: Long, val end: Long) {
        val rx = ArrayList<Rx>()
        val claims = ArrayList<Claim>()
        val catches = ArrayList<Catch>()
    }

    private class DigestLine(val t: Long, val order: Long, val line: String)

    /** Doubles without boxing, for the percentiles. */
    private class Doubles {
        private var values = DoubleArray(16)
        var size = 0
            private set

        fun add(value: Double) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        /** Nearest rank, as [LabMerge.percentile]; null for none. */
        fun percentile(percent: Int): Double? {
            if (size == 0) return null
            val sorted = values.copyOf(size).also { it.sort() }
            val rank = ceil(percent / 100.0 * size).toInt().coerceIn(1, size)
            return sorted[rank - 1]
        }
    }

    /** RSSI from [MIN_DBM] to 0 dBm, a count per dBm: exact percentiles in a fixed size. */
    private class IntHistogram {
        private val counts = IntArray(-MIN_DBM + 1)
        var count = 0
            private set

        fun add(dbm: Int) {
            counts[dbm.coerceIn(MIN_DBM, 0) - MIN_DBM]++
            count++
        }

        fun percentile(percent: Int): Int {
            val rank = ceil(percent / 100.0 * count).toInt().coerceIn(1, count)
            var seen = 0
            for ((index, n) in counts.withIndex()) {
                seen += n
                if (seen >= rank) return index + MIN_DBM
            }
            return 0
        }
    }

    companion object {
        /**
         * A window of the full report ([FieldReportStream]): 2 minutes of a game's logs in memory at once. The window
         * after it only has to reach [INTERPOLATE_MILLIS] and [ZERO_AFTER_MILLIS] on, so a shorter one counts the same.
         */
        const val WINDOW_MILLIS = 2 * 60_000L

        /** A gap between two GPS fixes counted in the player's section. */
        const val GPS_GAP_MILLIS = 30_000L

        /**
         * Fixes at most this far apart are interpolated between for a pair's distance; else the nearest within
         * [NEAREST_MILLIS].
         */
        const val INTERPOLATE_MILLIS = 10_000L
        const val NEAREST_MILLIS = 3_000L

        /** A fix less accurate than this says nothing of a pair's distance. */
        const val MAX_TRACK_ACCURACY = 50.0

        /** Pairs nearer than this by GPS, both advertising, count in the coverage. */
        const val COVERAGE_METERS = 20.0

        /** A player whose log went quiet this long before the round's end dropped out (unless they answered). */
        const val DROPOUT_MILLIS = 3 * 60_000L

        /** The battery's pace needs this long between its first and last level. */
        const val BATTERY_SPAN_MILLIS = 10 * 60_000L

        /** The timeline's noisy kinds (reveals, glows, fixes, the server's anomalies) keep this many each. */
        const val MAX_TIMELINE_PER_KIND = 1_000

        /** The game's moments and the marks: all of them, but a game of millions is no game. */
        const val MAX_STRUCTURAL_TIMELINE = 20_000

        /** Kinds of the timeline never capped short of [MAX_STRUCTURAL_TIMELINE]. */
        val STRUCTURAL_KINDS = setOf(
            ServerKinds.PHASE,
            ServerKinds.CLAIM,
            ServerKinds.CATCH,
            ServerKinds.DISPUTE,
            FieldKinds.MARK,
        )

        /** The anomalies listed per kind; the counts have them all. */
        const val MAX_ANOMALIES_PER_KIND = 300
        private const val MAX_RECENT_DROPPED = 2_000
        const val AROUND_MARK_MILLIS = 30_000L
        const val MAX_AROUND = 40

        /** A catch's «0 m»: the loudest of the pair from this long before it to this long after. */
        const val ZERO_BEFORE_MILLIS = 15_000L
        const val ZERO_AFTER_MILLIS = 2_000L

        const val CATCH_ZERO = "catch"
        const val TOUCH_ZERO = "touch"

        /** A knock the accelerometer felt (the lab's `impact`): the touch detector's input. */
        private const val IMPACT = "impact"

        /** The distance buckets of the RSSI table, meters. */
        val BUCKETS = listOf("0-5", "5-10", "10-20", "20-40", "40+")

        fun bucket(meters: Double): String = when {
            meters < 5 -> BUCKETS[0]
            meters < 10 -> BUCKETS[1]
            meters < 20 -> BUCKETS[2]
            meters < 40 -> BUCKETS[3]
            else -> BUCKETS[4]
        }

        /** `android`, `ios` or the system as the phone said it. */
        fun platformOf(os: String?): String = when {
            os == null -> "?"
            os.contains("android", ignoreCase = true) -> "android"
            os.contains("ios", ignoreCase = true) || os.contains("iphone", ignoreCase = true) -> "ios"
            else -> os.substringBefore(' ').lowercase()
        }

        private const val ROUND_HIDING = "HIDING"
        private const val ROUND_SEEKING = "SEEKING"
        private const val FINISHED = "FINISHED"
        private val ROUND_PHASES = setOf(ROUND_HIDING, ROUND_SEEKING)
        private const val CONFIRMED = "confirmed"
        private const val ON = "on"

        /** The service data's channels: `ble.service_data.<layout>`. */
        private const val SERVICE_DATA = "ble.service_data."
        private const val OFF = "off"
        private const val MIN_DBM = -127
        private const val MINUTE = 60_000L
        private const val MINUTE_MASK = (1L shl 40) - 1
        private const val KEEP_FIXES_MILLIS = 60_000L
        private const val BUTTON_SAME_MILLIS = 10_000L

        /** Meters per degree of latitude on the mean sphere. */
        private const val DEGREE = 6_371_008.8 * PI / 180.0

        /** The server's fields that name a player: aliases in the digest. */
        private val PLAYER_FIELDS = setOf(
            ServerFields.SEEKER,
            ServerFields.HIDER,
            ServerFields.PLAYER,
            ServerFields.OBSERVER,
            ServerFields.HEARD,
        )

        /** The server's fields of no use to the digest (and the claim's id, a key of nothing outside the game). */
        private val HIDDEN_SERVER_FIELDS = setOf(ServerFields.CATCH)

        private val SRV_DIGEST = listOf(
            SrvFields.WINDOW,
            SrvFields.SYNCS,
            SrvFields.SYNC_P50,
            SrvFields.SYNC_P95,
            SrvFields.POLL_P95,
            SrvFields.SOCKET_P95,
            SrvFields.ERRORS_5XX,
            SrvFields.ERRORS_429,
            SrvFields.SOCKETS,
            SrvFields.GAMES,
            SrvFields.PLAYERS,
            SrvFields.HEAP_MB,
            SrvFields.CPU,
            SrvFields.DROPPED,
        )

        private fun Long.ceilDiv(other: Long): Long = -(-this).floorDiv(other)

        private fun pairKey(sender: Int, listener: Int, second: Long): Long =
            (second shl 24) or ((sender.toLong() and 0xFFF) shl 12) or (listener.toLong() and 0xFFF)

        private fun counts(names: List<String>): List<FieldReportCount> =
            names.groupingBy { it }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key }))
                .map { FieldReportCount(it.key, it.value) }

        private fun percent(part: Int, whole: Int): Double? =
            if (whole <= 0) null else (part * 1000.0 / whole).roundToLong() / 10.0

        private fun round1(value: Double): Double = (value * 10).roundToLong() / 10.0
    }
}
