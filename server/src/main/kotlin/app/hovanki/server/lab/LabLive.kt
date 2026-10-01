package app.hovanki.server.lab

import app.hovanki.shared.lab.FieldKinds
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.lab.RxFields
import app.hovanki.shared.lab.SyncFields
import app.hovanki.shared.protocol.LabLiveDevice
import app.hovanki.shared.protocol.LabLivePair
import app.hovanki.shared.protocol.LabLiveView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * The live view of the runs going on (docs/adr/0017-radar-techniques-and-big-run.md §5.4), from the chunks received
 * so far: per device its last app state, Bluetooth, battery, clock offset and step, and who heard whom in the last
 * seconds. Memory only, one entry per run, dropped when the run finishes or is deleted, and when nothing came for
 * [IDLE_MILLIS] (a run left behind). Bounded whatever the phones send: the readings older than
 * [LabProperties.liveWindow] are dropped as new ones come, those dated ahead of the server's clock by more than
 * [AHEAD_MILLIS] are never kept, and a run keeps at most [MAX_READINGS]. A sender nobody in the run advertised is
 * shown as `?`, never by its token (it may be a real game's). A summary of readings (a field log's `rx`, its count in
 * `n`) counts as that many. A game's field log shows its devices only: its senders' tokens are the game's, which
 * change with the clock and which no device of the run has, so its readings are not kept (who heard whom: the report,
 * docs/field-test.md step 6).
 */
@Component
class LabLive(private val properties: LabProperties) {
    private val runs = ConcurrentHashMap<String, RunLive>()

    /**
     * The events of one upload of [deviceId] in [runId], in their order; [pairs]: its readings are kept for who heard
     * whom (a lab run's, not a game's). The caller holds the run's lock, so a run that finishes or is deleted meanwhile
     * is dropped after this, never before.
     */
    fun accept(runId: String, deviceId: String, events: List<JsonObject>, nowMillis: Long, pairs: Boolean = true) {
        evictIdle(nowMillis)
        val run = runs.computeIfAbsent(runId) { RunLive() }
        run.accept(deviceId, events, nowMillis - properties.liveWindow.toMillis(), nowMillis, pairs)
    }

    /** What [devices] of the run said last and heard lately, at [nowMillis]; senders by their radar tokens. */
    fun view(runId: String, devices: List<LabDeviceRecord>, nowMillis: Long): LabLiveView {
        evictIdle(nowMillis)
        return runs[runId]?.view(devices, nowMillis, nowMillis - properties.liveWindow.toMillis())
            ?: LabLiveView(nowMillis, devices.map { LabLiveDevice(it.id, it.label) })
    }

    fun drop(runId: String) {
        runs.remove(runId)
    }

    /** The runs kept now (tests). */
    internal val size: Int get() = runs.size

    private fun evictIdle(nowMillis: Long) {
        runs.entries.removeIf { it.value.acceptedAt < nowMillis - IDLE_MILLIS }
    }

    private class DeviceLive {
        var lastEventAt: Long? = null
        var clockOffset: Long? = null
        var app: String? = null
        var bluetooth: String? = null
        var battery: Double? = null
        var step: Int? = null
        var syncMillis: Long? = null
        var syncOk: Boolean? = null
        var syncTransport: String? = null
    }

    /** [count] readings of [rssi] (their median when a summary). */
    private class Reading(
        val t: Long,
        val listener: String,
        val token: String?,
        val channel: String,
        val rssi: Int,
        val count: Int,
    )

    private class RunLive {
        private val devices = HashMap<String, DeviceLive>()
        private val readings = ArrayDeque<Reading>()

        /** When the last upload came, by the server's clock. */
        @Volatile
        var acceptedAt = Long.MIN_VALUE
            private set

        @Synchronized
        fun accept(deviceId: String, events: List<JsonObject>, keepAfter: Long, nowMillis: Long, pairs: Boolean) {
            acceptedAt = nowMillis
            val device = devices.getOrPut(deviceId) { DeviceLive() }
            for (event in events) {
                val t = event.long(LabFields.T) ?: event.long(LabFields.DT) ?: continue
                // A clock far ahead would keep its readings forever: what is dated ahead of the server's is dropped.
                if (t > nowMillis + AHEAD_MILLIS) continue
                if (t >= (device.lastEventAt ?: Long.MIN_VALUE)) device.lastEventAt = t
                event.string(LabFields.APP)?.let { device.app = it }
                when (event.string(LabFields.K)) {
                    "clock" -> event.long("offset")?.let { device.clockOffset = it }

                    "bt" -> event.string("state")?.let { device.bluetooth = it }

                    "battery" -> event.double("level")?.let { device.battery = it }

                    "step" -> event.int("index")?.let { device.step = it }

                    // A field log's: the phone's last sync, to see who is behind (docs/field-test.md step 6).
                    FieldKinds.SYNC -> {
                        device.syncOk = event.bool(SyncFields.OK)
                        device.syncMillis = event.long(SyncFields.MILLIS)
                        device.syncTransport = event.string(SyncFields.TRANSPORT)
                    }

                    "rx" -> {
                        if (!pairs) continue
                        val rssi = event.int(RxFields.RSSI) ?: continue
                        if (t <= keepAfter) continue
                        val count = event.int(RxFields.COUNT)?.coerceIn(1, MAX_COUNT) ?: 1
                        val channel = "${event.string(RxFields.API)}/${event.string(RxFields.VIA)}"
                        readings.addLast(Reading(t, deviceId, event.string(RxFields.TOKEN), channel, rssi, count))
                        if (readings.size > MAX_READINGS) readings.removeFirst()
                    }
                }
            }
            prune(keepAfter)
        }

        @Synchronized
        fun view(known: List<LabDeviceRecord>, nowMillis: Long, keepAfter: Long): LabLiveView {
            prune(keepAfter)
            val labels = known.associate { it.id to it.label }
            val byToken = known.groupBy({ it.radarToken }, { it.label })
                .mapValues { (_, list) -> list.distinct().sorted().joinToString("+") }
            val recentAfter = nowMillis - RECENT_MILLIS
            val pairs = readings
                .groupBy { Triple(sender(it.token, byToken), labels[it.listener] ?: "?", it.channel) }
                .map { (key, list) ->
                    val recent = list.filter { it.t > recentAfter }.sortedBy { it.rssi }
                    val heard = recent.sumOf { it.count }
                    LabLivePair(
                        from = key.first,
                        to = key.second,
                        channel = key.third,
                        heardInLast10s = heard,
                        medianRssi = weightedMedian(recent, heard),
                    )
                }
                .sortedWith(compareBy({ it.from }, { it.to }, { it.channel }))
            val deviceViews = known.map { record ->
                val live = devices[record.id]
                LabLiveDevice(
                    deviceId = record.id,
                    label = record.label,
                    lastEventAtMillis = live?.lastEventAt,
                    clockOffsetMillis = live?.clockOffset,
                    appState = live?.app,
                    bluetooth = live?.bluetooth,
                    batteryLevel = live?.battery,
                    stepIndex = live?.step,
                    syncMillis = live?.syncMillis,
                    syncOk = live?.syncOk,
                    syncTransport = live?.syncTransport,
                )
            }
            return LabLiveView(nowMillis, deviceViews, pairs)
        }

        private fun prune(keepAfter: Long) {
            // Uploads come in order per device, not across devices: an old reading may sit behind a newer one.
            if (readings.any { it.t <= keepAfter }) readings.removeAll { it.t <= keepAfter }
        }

        private fun sender(token: String?, byToken: Map<String, String>): String = token?.let(byToken::get) ?: "?"

        /** The median of [sorted]'s readings, each counted [Reading.count] times ([total] of them); null: none. */
        private fun weightedMedian(sorted: List<Reading>, total: Int): Int? {
            if (total == 0) return null
            val middle = (total - 1) / 2
            var seen = 0
            for (reading in sorted) {
                seen += reading.count
                if (seen > middle) return reading.rssi
            }
            return sorted.lastOrNull()?.rssi
        }
    }

    internal companion object {
        const val RECENT_MILLIS = 10_000L

        /** A run nothing came from for this long is forgotten (its readings are older than the window anyway). */
        const val IDLE_MILLIS = 10 * 60_000L

        /** A device's clock may be this much ahead of the server's; readings dated later are not kept. */
        const val AHEAD_MILLIS = 60_000L

        /** Readings kept per run: a minute of eight phones hearing each other ten times a second, and more. */
        const val MAX_READINGS = 50_000

        /** A summary counts as at most this many readings: a second of a phone's scans, and more. */
        const val MAX_COUNT = 1_000

        private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

        private fun JsonObject.string(key: String): String? = primitive(key)?.takeIf { it.isString }?.content

        private fun JsonObject.long(key: String): Long? = primitive(key)?.longOrNull

        private fun JsonObject.int(key: String): Int? = primitive(key)?.intOrNull

        private fun JsonObject.double(key: String): Double? = primitive(key)?.doubleOrNull

        private fun JsonObject.bool(key: String): Boolean? = primitive(key)?.booleanOrNull
    }
}
