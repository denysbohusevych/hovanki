@file:OptIn(ExperimentalTime::class)

package app.hovanki.client.diagnostics

import app.hovanki.client.network.Transport
import app.hovanki.radar.SightingVia
import app.hovanki.shared.protocol.DeviceReport
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.rules.RadarSmoother
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * What the phone measures, for the developer's eyes in a debug build (docs/architecture.md, «Диагностика
 * debug-сборки»): every GPS fix's accuracy and pace, every phone Bluetooth hears (token, dBm, the level smoothed with
 * the server's rules and its band), the syncs and what the phone told the server about itself. It is for checking the
 * radar's numbers and the GPS on real phones (docs/adr/0012-nearby-radar.md, section 8).
 *
 * Never a coordinate: a fix is its accuracy, its time and the mock flag. Everything stays in memory, nothing is sent or
 * stored; the developer shares the log by their own hand ([report], the system «Share» menu). Release builds use
 * [Off]: it records nothing. Main thread, except [onSyncSent], which the connection calls from its own.
 */
class Diagnostics(
    val isEnabled: Boolean,
    private val deviceTimeMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutableState = MutableStateFlow(DiagnosticsState())
    val state: StateFlow<DiagnosticsState> = mutableState.asStateFlow()

    /** The sync on its way: when it went (device clock), with how many sightings and what the phone said. */
    private val pendingSync = MutableStateFlow<PendingSync?>(null)
    private val smoothers = HashMap<String, RadarSmoother>()
    private val lastRadioLine = HashMap<String, Long>()

    private class PendingSync(val atMillis: Long, val sightings: Int, val device: DeviceReport?)

    /** A GPS fix taken at [fixAtMillis] of the device's clock. */
    fun onFix(accuracyMeters: Double, isMock: Boolean, fixAtMillis: Long) {
        if (!isEnabled) return
        val now = deviceTimeMillis()
        val previous = mutableState.value.gps
        val reading = GpsReading(
            accuracyMeters = accuracyMeters,
            isMock = isMock,
            fixAtMillis = fixAtMillis,
            receivedAtMillis = now,
            sincePreviousMillis = previous?.let { fixAtMillis - it.fixAtMillis },
        )
        mutableState.update {
            it.copy(
                gps = reading,
                gpsFixes = it.gpsFixes + 1,
                recentAccuracy = (it.recentAccuracy + accuracyMeters).takeLast(RECENT_FIXES),
            )
        }
        val gap = reading.sincePreviousMillis?.let { ", +${seconds(it)} s" }.orEmpty()
        val mock = if (isMock) ", MOCK" else ""
        log(DiagnosticsKind.GPS, "±${accuracyMeters.roundToInt()} m$gap$mock", now)
    }

    /** Bluetooth advertises [token] and scans, as a seeker or a hider; [token] null: stopped. */
    fun onRadio(token: String?, asSeeker: Boolean) {
        if (!isEnabled) return
        val current = mutableState.value
        if (current.ownToken == token && current.radioAsSeeker == asSeeker) return
        mutableState.update { it.copy(ownToken = token, radioAsSeeker = asSeeker) }
        val text = when {
            token == null -> "radio off"
            current.ownToken != null -> "token $token"
            asSeeker -> "radio on, token $token, iBeacon (seeker)"
            else -> "radio on, token $token, service (hider)"
        }
        log(DiagnosticsKind.RADIO, text)
    }

    /**
     * Another phone heard: its [token] at [rssi] dBm at [atMillis] of the device's clock; [isRival]: the other team's
     * token (a seeker's for a hider, a hider's for a seeker), which the pulse listens for.
     */
    fun onSighting(token: String, rssi: Int, atMillis: Long, isRival: Boolean = false, via: SightingVia? = null) {
        if (!isEnabled) return
        val smoother = smoothers.getOrPut(token) { RadarSmoother() }
        smoother.add(rssi, atMillis)
        val band = smoother.bandAt(atMillis)
        mutableState.update { state ->
            val old = state.contacts.firstOrNull { it.token == token }
            val contact = RadioContact(
                token = token,
                lastRssi = rssi,
                minRssi = minOf(rssi, old?.minRssi ?: rssi),
                maxRssi = maxOf(rssi, old?.maxRssi ?: rssi),
                readings = (old?.readings ?: 0) + 1,
                lastAtMillis = atMillis,
                levelDbm = smoother.levelDbm,
                band = band,
                isRival = isRival || old?.isRival == true,
            )
            state.copy(
                contacts = (listOf(contact) + state.contacts.filter { it.token != token }).take(MAX_CONTACTS),
            )
        }
        // One line a second per phone: a scan reports several readings a second.
        val lastLine = lastRadioLine[token]
        if (lastLine == null || atMillis - lastLine >= RADIO_LINE_GAP_MILLIS) {
            lastRadioLine[token] = atMillis
            val level = smoother.levelDbm?.let { " → ${formatDbm(it)}" }.orEmpty()
            val rival = if (isRival) " rival" else ""
            val how = via?.takeIf { it != SightingVia.UNKNOWN }?.let { " (${it.key})" }.orEmpty()
            log(DiagnosticsKind.RADIO, "$token$rival $rssi dBm$level ${band.name}$how", atMillis)
        }
    }

    /** A sync goes out with [sightings] and what the phone says about itself ([device]). Any thread. */
    fun onSyncSent(sightings: Int, device: DeviceReport?) {
        if (!isEnabled) return
        pendingSync.value = PendingSync(deviceTimeMillis(), sightings, device)
    }

    /** The sync's answer came by [transport]: the server's clock said [serverTimeMillis]. */
    fun onSynced(serverTimeMillis: Long, transport: Transport = Transport.POLLING) {
        if (!isEnabled) return
        val now = deviceTimeMillis()
        val pending = pendingSync.value
        pendingSync.value = null
        val duration = pending?.let { now - it.atMillis }
        mutableState.update {
            it.copy(
                syncs = it.syncs + 1,
                lastSync = SyncReading(now, duration, error = null, sightings = pending?.sightings ?: 0),
                device = pending?.device ?: it.device,
                serverOffsetMillis = serverTimeMillis - now,
                transport = transport,
            )
        }
        val took = duration?.let { "$it ms" } ?: "answer"
        val heard = pending?.sightings?.takeIf { it > 0 }?.let { ", $it sightings" }.orEmpty()
        val via = if (transport == Transport.SOCKET) " by socket" else ""
        log(DiagnosticsKind.SYNC, "sync $took$heard$via", now)
    }

    /** The sync failed with [error]; the connection tries again in [retryInMillis]. */
    fun onSyncFailed(error: Throwable, retryInMillis: Long) {
        if (!isEnabled) return
        val now = deviceTimeMillis()
        val pending = pendingSync.value
        pendingSync.value = null
        val text = error.message?.take(MAX_ERROR_CHARS) ?: error::class.simpleName ?: "error"
        val reading = SyncReading(now, pending?.let { now - it.atMillis }, text, pending?.sightings ?: 0)
        mutableState.update { it.copy(syncFailures = it.syncFailures + 1, lastSync = reading) }
        log(DiagnosticsKind.SYNC, "sync failed: $text, retry in ${seconds(retryInMillis)} s", now)
    }

    /** Something about the game worth a line: a phase, the connection. */
    fun note(text: String) {
        if (!isEnabled) return
        log(DiagnosticsKind.GAME, text)
    }

    /** Forgets everything measured so far. */
    fun clear() {
        if (!isEnabled) return
        smoothers.clear()
        lastRadioLine.clear()
        pendingSync.value = null
        mutableState.value = DiagnosticsState(ownToken = mutableState.value.ownToken)
    }

    /** Everything measured, as text to share: [header] first (the build, the phone, the server). No coordinates. */
    fun report(header: List<String> = emptyList()): String = buildString {
        val state = mutableState.value
        val now = deviceTimeMillis()
        header.forEach { appendLine(it) }
        appendLine("Report at ${formatClock(now)} UTC (device clock); times below are UTC too")
        state.serverOffsetMillis?.let { appendLine("Server clock: ${signed(it)} ms from the device's") }
        appendLine()
        appendLine("GPS: ${state.gpsFixes} fixes")
        state.gps?.let { gps ->
            val at = formatClock(gps.fixAtMillis)
            appendLine("  last ±${gps.accuracyMeters.roundToInt()} m at $at, mock ${gps.isMock}")
        }
        state.medianAccuracy?.let { median ->
            appendLine("  median of the last ${state.recentAccuracy.size}: ±${median.roundToInt()} m")
        }
        appendLine()
        appendLine("Radio: own token ${state.ownToken ?: "—"}${if (state.radioAsSeeker) " (seeker)" else ""}")
        state.contacts.forEach { contact ->
            val rival = if (contact.isRival) " rival" else ""
            val level = contact.levelDbm?.let(::formatDbm) ?: "—"
            appendLine(
                "  ${contact.token}$rival: last ${contact.lastRssi} dBm, min ${contact.minRssi}, " +
                    "max ${contact.maxRssi}, level $level, ${contact.band.name}, ${contact.readings} readings, " +
                    "last at ${formatClock(contact.lastAtMillis)}",
            )
        }
        appendLine()
        val via = state.transport?.let { " (last ${it.name.lowercase()})" }.orEmpty()
        appendLine("Syncs: ${state.syncs} ok, ${state.syncFailures} failed$via")
        state.device?.let { appendLine("  the phone said: $it") }
        appendLine()
        appendLine("Log (${state.log.size} lines):")
        state.log.forEach { appendLine("${formatClock(it.atMillis)} ${it.kind.name.padEnd(5)} ${it.text}") }
    }

    private fun log(kind: DiagnosticsKind, text: String, atMillis: Long = deviceTimeMillis()) {
        val line = DiagnosticsLine(atMillis, kind, text)
        mutableState.update { it.copy(log = (it.log + line).takeLast(MAX_LOG_LINES)) }
    }

    companion object {
        /** Release builds: nothing is recorded. */
        val Off = Diagnostics(isEnabled = false)

        const val MAX_LOG_LINES = 2_000
        const val MAX_CONTACTS = 30
        const val RECENT_FIXES = 30
        private const val RADIO_LINE_GAP_MILLIS = 1_000L
        private const val MAX_ERROR_CHARS = 120

        /** `14:03:27.415` of [millis] since the epoch, UTC. */
        fun formatClock(millis: Long): String {
            val dayMillis = millis.mod(DAY_MILLIS)
            val hours = dayMillis / HOUR_MILLIS
            val minutes = dayMillis / MINUTE_MILLIS % 60
            val seconds = dayMillis / 1000 % 60
            val rest = dayMillis % 1000
            return "${two(hours)}:${two(minutes)}:${two(seconds)}.${rest.toString().padStart(3, '0')}"
        }

        /** `-67.4`. */
        fun formatDbm(dbm: Double): String = ((dbm * 10).roundToInt() / 10.0).toString()

        /** `1.2` seconds of [millis]. */
        fun seconds(millis: Long): String = ((millis / 100.0).roundToInt() / 10.0).toString()

        private fun signed(value: Long) = if (value >= 0) "+$value" else "$value"

        private fun two(value: Long) = value.toString().padStart(2, '0')

        private const val MINUTE_MILLIS = 60_000L
        private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
        private const val DAY_MILLIS = 24 * HOUR_MILLIS
    }
}

data class DiagnosticsState(
    /** The latest GPS fix. */
    val gps: GpsReading? = null,
    val gpsFixes: Int = 0,
    /** The accuracy of the last [Diagnostics.RECENT_FIXES] fixes, metres. */
    val recentAccuracy: List<Double> = emptyList(),
    /** The token this phone advertises; null while the radio is off. */
    val ownToken: String? = null,
    val radioAsSeeker: Boolean = false,
    /** The phones heard, the latest first. */
    val contacts: List<RadioContact> = emptyList(),
    val syncs: Int = 0,
    val syncFailures: Int = 0,
    val lastSync: SyncReading? = null,
    /** What the phone told the server about itself with the last sync that got through. */
    val device: DeviceReport? = null,
    /** Server time minus the device's, by the last sync; null before one. */
    val serverOffsetMillis: Long? = null,
    /** How the last sync that got through went (docs/adr/0015-websockets.md); null before one. */
    val transport: Transport? = null,
    /** The latest [Diagnostics.MAX_LOG_LINES] lines, the oldest first. */
    val log: List<DiagnosticsLine> = emptyList(),
) {
    val medianAccuracy: Double?
        get() = recentAccuracy.sorted().let { sorted -> if (sorted.isEmpty()) null else sorted[sorted.size / 2] }
}

/** A GPS fix without its place. Times of the device's clock. */
data class GpsReading(
    val accuracyMeters: Double,
    val isMock: Boolean,
    val fixAtMillis: Long,
    val receivedAtMillis: Long,
    /** Since the fix before; null for the first. */
    val sincePreviousMillis: Long?,
)

/** A phone Bluetooth hears: raw dBm, and the level and band the server's smoothing would make of them. */
data class RadioContact(
    val token: String,
    val lastRssi: Int,
    val minRssi: Int,
    val maxRssi: Int,
    val readings: Int,
    /** Device clock. */
    val lastAtMillis: Long,
    val levelDbm: Double?,
    val band: RadarBand,
    /** The other team's token: the pulse listens for it. */
    val isRival: Boolean,
)

/** A sync's outcome: when (device clock), how long it took, the error if it failed, the sightings it carried. */
data class SyncReading(val atMillis: Long, val durationMillis: Long?, val error: String?, val sightings: Int)

enum class DiagnosticsKind { GPS, RADIO, SYNC, GAME }

data class DiagnosticsLine(val atMillis: Long, val kind: DiagnosticsKind, val text: String)
