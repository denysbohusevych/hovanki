package app.hovanki.e2e.scenarios

import app.hovanki.client.lab.FieldStatus
import app.hovanki.e2e.OWN_SERVER
import app.hovanki.e2e.admin.StaffConsole
import app.hovanki.e2e.bot.BotPlayer
import app.hovanki.e2e.route.Route
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.e2e.scenario.Scenario
import app.hovanki.e2e.scenario.ScenarioReport
import app.hovanki.e2e.scenarioOnOwnServer
import app.hovanki.server.lab.FieldEventWriter
import app.hovanki.server.lab.FieldRunService
import app.hovanki.server.lab.LabReportWriter
import app.hovanki.shared.lab.FieldKinds
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.lab.SrvFields
import app.hovanki.shared.protocol.AdminLabRun
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.UserRole
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.parallel.Isolated
import org.junit.jupiter.api.parallel.ResourceLock
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.time.Duration
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The rehearsal of the field test (docs/field-test.md step 9): 50 phones of the field build with the radar and the
 * field log play one game on a server of their own with FIELD_LOG, and the test measures what the test day will cost:
 * the bytes the log takes in the database per player and hour (the stored, gzipped chunks: `lab_devices.bytes`), the
 * events a second per phone by kind, the uploads a second, the server's `/sync` p95 and heap (after a collection)
 * while it all goes on, the writer's dropped and unlogged counters; then the run ends and the whole report and a
 * `digest.jsonl` are computed, timed, with the heap after them. The numbers go to the report (`field-load-test.md`)
 * and the timeline, the ceilings below make a regression show.
 *
 * Real time, no compression of it: the game lasts [MINUTES] (default 5, `-Pe2e.fieldLoadMinutes=30` for the whole
 * rehearsal) and the per-hour figures are extrapolated from the span of every phone's log. `slow`: nightly, or by
 * hand with `./gradlew :e2e:test -Pe2e.slow=true --tests '*FieldLoadTest*'`. The bots and the server share one JVM
 * (2 GB of heap in `:e2e:test`), so the heap is the bots' and the server's together: an upper bound for the server.
 */
@Isolated
@Tag("slow")
class FieldLoadTest {
    @Test
    @ResourceLock(OWN_SERVER)
    fun fiftyPhonesWriteTheFieldLog() = scenarioOnOwnServer(
        "Load: field log of $PLAYERS bots",
        properties = mapOf("hovanki.game.max-players" to PLAYERS.toString()),
        timeout = (MINUTES + 12).minutes,
    ) { server ->
        enableAllFeatures()
        val random = Random(2026)
        StaffConsole(serverUrl, observer).use { console ->
            val boss = player("Boss", at = PARK, logChanges = false)
            val account = boss.signsUp()
            boss.confirmsEmail()
            observer.setRole(checkNotNull(boss.userId), UserRole.ADMIN)
            console.logIn(account)

            val phones = (1..PLAYERS).map { n ->
                val start = PARK.offset(random.nextDouble(-150.0, 150.0), random.nextDouble(-150.0, 150.0))
                player("P$n", at = start, logChanges = false, fieldLog = true)
            }
            val rules = GameSetups.FAST_RULES.copy(syncIntervalSeconds = 3)
            val settings = GameSetups.radar(center = PARK).copy(
                hidingSeconds = 20,
                seekingSeconds = (MINUTES + 5) * 60,
                rules = rules,
            )
            val host = phones.first()
            requireOk(host.createGame(settings), "${host.name} creates a game")
            val code = checkNotNull(host.snapshot).joinCode
            coroutineScope {
                phones.drop(1).map { bot -> async { requireOk(bot.join(code), "${bot.name} joins") } }.awaitAll()
            }
            useGame(checkNotNull(host.snapshot).gameId, code)
            requireOk(host.startGame(seekers = phones.take(SEEKERS)), "${host.name} starts")
            awaitPhase(GamePhase.SEEKING, within = 60.seconds)
            for (phone in phones) {
                eventually("${phone.name}'s field log is on", within = 90.seconds) {
                    phone.takeIf { it.fieldState.status == FieldStatus.ON }
                }
            }
            val run =
                eventually("the game's run") { console.fieldGames().runs.firstOrNull { it.gameId == gameId.value } }

            note("playing for $MINUTES min")
            val syncsBefore = metrics.syncLatencies.size
            val uploadsBefore = metrics.uploadCount
            val startedAt = System.currentTimeMillis()
            val until = startedAt + MINUTES * 60_000L
            while (System.currentTimeMillis() < until) {
                for ((i, phone) in phones.withIndex()) {
                    val seeker = i < SEEKERS
                    phone.gps.walkTo(
                        PARK.offset(random.nextDouble(-250.0, 250.0), random.nextDouble(-250.0, 250.0)),
                        if (seeker) Route.RUNNING else Route.WALKING,
                    )
                }
                delay(30.seconds)
            }
            val windowSeconds = (System.currentTimeMillis() - startedAt) / 1000.0
            val syncs = metrics.syncLatencies.drop(syncsBefore).sorted()
            val syncP95 = syncs[(syncs.size * 95 / 100).coerceAtMost(syncs.lastIndex)]
            val uploads = metrics.uploadCount - uploadsBefore

            // The last uploads (every 10 s), the server's writer flushed.
            delay(15.seconds)
            val writer = server.bean(FieldEventWriter::class.java)
            writer.awaitIdle(30_000)
            delay(6.seconds)
            val meters = server.bean(MeterRegistry::class.java)
            val dropped = meters.find(FieldEventWriter.DROPPED_COUNTER).counter()?.count() ?: 0.0
            val unlogged = meters.find(FieldEventWriter.UNLOGGED_COUNTER).counter()?.count() ?: 0.0

            // What the server keeps while the game plays: the heap after a collection (bots and server together).
            val heapAfterGcMb = heapAfterGcMb()
            val figures = measure(console, run, phones)

            // The game's run ends (as the janitor ends it once the game is gone): the whole report on the report
            // thread, then an admin's digest.jsonl, each timed, the heap's peak while they ran.
            val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
            pools.forEach { it.resetPeakUsage() }
            val reportStarted = System.nanoTime()
            val closed = server.bean(FieldRunService::class.java).closeRunsOfGoneGames { false }
            check(closed == 1, "the game's run is finished ($closed)")
            server.bean(LabReportWriter::class.java).awaitIdle(Duration.ofMinutes(5))
            val reportMillis = (System.nanoTime() - reportStarted) / 1_000_000
            val whole = console.fieldReport(run.id)
            val digestStarted = System.nanoTime()
            val digestLines = console.fieldExport(run.id, "digest.jsonl", reason = "the field load test")
                .lineSequence().count { it.isNotBlank() }
            val digestMillis = (System.nanoTime() - digestStarted) / 1_000_000
            val reportPeakMb = pools.sumOf { it.peakUsage.used } / MB
            val reportHeapAfterGcMb = heapAfterGcMb()
            val compute = Compute(reportMillis, digestMillis, digestLines, reportPeakMb, reportHeapAfterGcMb)

            val report = figures.render(windowSeconds, uploads, syncs.size, syncP95, dropped, unlogged) +
                compute.render(heapAfterGcMb)
            ScenarioReport.reportDir().apply { mkdirs() }.resolve("field-load-test.md").writeText(report)
            for (line in report.lines().filter { it.isNotBlank() }) note(line)

            check(metrics.errors.isEmpty(), "no failed requests: ${metrics.errors.take(5)}")
            check(figures.phones == PLAYERS, "every phone's log is in the run (${figures.phones})")
            check(dropped == 0.0 && unlogged == 0.0, "the server's writer dropped $dropped and left $unlogged events")
            check(syncP95 < MAX_SYNC_P95_MILLIS, "p95 of /sync is $syncP95 ms (limit $MAX_SYNC_P95_MILLIS ms)")
            check(
                figures.storedBytesPerHour.max() < MAX_STORED_BYTES_PER_PLAYER_HOUR,
                "stored bytes per player and hour: ${figures.storedBytesPerHour.max()} " +
                    "(limit $MAX_STORED_BYTES_PER_PLAYER_HOUR)",
            )
            check(
                figures.eventsPerSecondPerPhone < MAX_EVENTS_PER_SECOND_PER_PHONE,
                "events a second per phone: ${figures.eventsPerSecondPerPhone} (limit $MAX_EVENTS_PER_SECOND_PER_PHONE)",
            )
            check(
                uploads / windowSeconds < MAX_UPLOADS_PER_SECOND,
                "uploads a second: ${uploads / windowSeconds} (limit $MAX_UPLOADS_PER_SECOND)",
            )
            check(
                heapAfterGcMb < MAX_HEAP_AFTER_GC_MB,
                "heap after GC $heapAfterGcMb MB (limit $MAX_HEAP_AFTER_GC_MB MB)",
            )
            check(whole.final && whole.players.size == PLAYERS, "the whole report: ${whole.players.size} players")
            check(
                reportHeapAfterGcMb < MAX_HEAP_AFTER_GC_MB,
                "heap after GC with the report computed $reportHeapAfterGcMb MB (limit $MAX_HEAP_AFTER_GC_MB MB)",
            )
            check(reportMillis < MAX_REPORT_MILLIS, "the whole report took $reportMillis ms (limit $MAX_REPORT_MILLIS)")
        }
    }

    /** The whole report and the digest once the run ends. */
    private class Compute(
        val reportMillis: Long,
        val digestMillis: Long,
        val digestLines: Int,
        val peakMb: Long,
        val heapAfterGcMb: Long,
    ) {
        fun render(heapBeforeMb: Long) = buildString {
            appendLine()
            appendLine("## The whole report")
            appendLine()
            appendLine("Heap after GC while the game played: $heapBeforeMb MB (bots and server in one JVM).")
            appendLine("The whole report (run finished → stored): $reportMillis ms.")
            appendLine("digest.jsonl: $digestLines lines in $digestMillis ms.")
            appendLine("Heap: peak $peakMb MB used while they ran (garbage too), $heapAfterGcMb MB after GC.")
        }
    }

    /** The heap the process keeps: used after a full collection. */
    private fun heapAfterGcMb(): Long {
        repeat(2) { System.gc() }
        return ManagementFactory.getMemoryMXBean().heapMemoryUsage.used / MB
    }

    /** What the run holds after the game: the admin's view (stored bytes) and the raw logs (events by kind, span). */
    private class Figures(
        val phones: Int,
        val storedBytesPerHour: List<Long>,
        val storedTotal: Long,
        val rawTotal: Long,
        val kindCounts: Map<String, Long>,
        val kindRawBytes: Map<String, Long>,
        val phoneSeconds: Double,
        val serverStoredBytes: Long,
        val maxHeapMb: Long,
        val heapMaxMb: Long?,
        val maxCpu: Double?,
        val srvSyncP95: Double?,
        val srvDropped: Long,
    ) {
        val eventsPerSecondPerPhone: Double get() = kindCounts.values.sum() / phoneSeconds

        fun render(window: Double, uploads: Int, syncs: Int, syncP95: Long, dropped: Double, unlogged: Double) =
            buildString {
                val sorted = storedBytesPerHour.sorted()
                fun mb(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes / 1_048_576.0)
                fun f(value: Double) = String.format(Locale.ROOT, "%.3f", value)
                appendLine("# Field log load: $phones phones")
                appendLine()
                appendLine("Window of the game: ${window.toInt()} s; phones' logs together: ${phoneSeconds.toInt()} s.")
                appendLine()
                appendLine("## Bytes in the database")
                appendLine()
                appendLine(
                    "Stored (gzip) per player and hour: median ${mb(sorted[sorted.size / 2])} MB, " +
                        "max ${mb(sorted.last())} MB (${sorted[sorted.size / 2]} / ${sorted.last()} bytes).",
                )
                appendLine(
                    "Stored per phone and second (all phones): ${f(storedTotal / phoneSeconds)} B/s; " +
                        "raw JSONL ${f(
                            rawTotal / phoneSeconds,
                        )} B/s, gzip ratio ${f(rawTotal.toDouble() / storedTotal)}.",
                )
                appendLine(
                    "50 phones for 3 hours: ${mb((storedTotal / phoneSeconds * PLAYERS * 3 * 3600).toLong())} MB.",
                )
                appendLine("The server's own device: $serverStoredBytes bytes.")
                appendLine()
                appendLine("## Events a second per phone, by kind")
                appendLine()
                appendLine("| kind | events/s per phone | share | raw B/s per phone | share of bytes |")
                appendLine("|---|---|---|---|---|")
                val all = kindCounts.values.sum()
                for ((kind, count) in kindCounts.entries.sortedByDescending { it.value }) {
                    val raw = kindRawBytes.getValue(kind)
                    appendLine(
                        "| $kind | ${f(count / phoneSeconds)} | ${f(count.toDouble() / all)} | " +
                            "${f(raw / phoneSeconds)} | ${f(raw.toDouble() / rawTotal)} |",
                    )
                }
                appendLine("| all | ${f(eventsPerSecondPerPhone)} | 1.000 | ${f(rawTotal / phoneSeconds)} | 1.000 |")
                appendLine()
                appendLine("## Server")
                appendLine()
                appendLine("Upload requests: $uploads in ${window.toInt()} s = ${f(uploads / window)} per second.")
                appendLine("`/sync`: $syncs in the window, p95 $syncP95 ms; srv events: max p95 $srvSyncP95 ms.")
                appendLine(
                    "Heap: max $maxHeapMb MB used of ${heapMaxMb ?: "?"} MB by `srv` " +
                        "(garbage too; bots and server in one JVM).",
                )
                appendLine("CPU: max ${maxCpu ?: "?"} (the process, the bots too).")
                appendLine("Writer: dropped $dropped (srv says $srvDropped), unlogged $unlogged.")
            }
    }

    private suspend fun Scenario.measure(console: StaffConsole, run: AdminLabRun, phones: List<BotPlayer>): Figures {
        val view = console.labRun(run.id)
        val storedByLabel = view.devices.associate { it.label to it.bytes }
        val labels = view.devices.associate { "hovanki-lab-${it.label}-${it.id}.jsonl" to it.label }
        val kinds = HashMap<String, Long>()
        val kindBytes = HashMap<String, Long>()
        val perHour = ArrayList<Long>()
        var phoneSeconds = 0.0
        var rawTotal = 0L
        var storedTotal = 0L
        val srv = ArrayList<JsonObject>()
        // The zip goes to a file and is read an entry and a line at a time: 50 phones' logs never whole in the heap.
        val zip = File.createTempFile("hovanki-field-load", ".zip")
        try {
            console.downloadFieldRawTo(run.id, reason = "the field load test", file = zip)
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val label = labels[entry.name] ?: continue
                    val server = label == FieldKinds.SERVER_DEVICE
                    var first = Long.MAX_VALUE
                    var last = Long.MIN_VALUE
                    var bytes = 0L
                    // Not closed: that would close the zip.
                    val reader = input.bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val event = Json.parseToJsonElement(line).jsonObject
                        val kind = event[LabFields.K]?.jsonPrimitive?.content ?: "?"
                        if (server) {
                            if (kind == FieldKinds.SRV) srv += event
                            continue
                        }
                        event[LabFields.T]?.jsonPrimitive?.longOrNull?.let {
                            first = minOf(first, it)
                            last = maxOf(last, it)
                        }
                        bytes += line.length + 1L
                        kinds.merge(kind, 1L, Long::plus)
                        kindBytes.merge(kind, line.length + 1L, Long::plus)
                    }
                    if (server) continue
                    val span = (last - first) / 1000.0
                    check(span > 60, "$label's log spans only $span s")
                    val stored = storedByLabel.getValue(label)
                    perHour += (stored / span * 3600).toLong()
                    storedTotal += stored
                    rawTotal += bytes
                    phoneSeconds += span
                }
            }
        } finally {
            zip.delete()
        }
        fun long(key: String) = srv.mapNotNull { it[key]?.jsonPrimitive?.longOrNull }
        return Figures(
            phones = view.devices.count { it.label != FieldKinds.SERVER_DEVICE },
            storedBytesPerHour = perHour,
            storedTotal = storedTotal,
            rawTotal = rawTotal,
            kindCounts = kinds,
            kindRawBytes = kindBytes,
            phoneSeconds = phoneSeconds,
            serverStoredBytes = storedByLabel[FieldKinds.SERVER_DEVICE] ?: 0,
            maxHeapMb = long(SrvFields.HEAP_MB).maxOrNull() ?: Runtime.getRuntime().let { it.totalMemory() / MB },
            heapMaxMb = long(SrvFields.HEAP_MAX_MB).maxOrNull(),
            maxCpu = srv.mapNotNull { it[SrvFields.CPU]?.jsonPrimitive?.doubleOrNull }.maxOrNull(),
            srvSyncP95 = srv.mapNotNull { it[SrvFields.SYNC_P95]?.jsonPrimitive?.doubleOrNull }.maxOrNull(),
            srvDropped = long(SrvFields.DROPPED).sum(),
        ).also { check(phones.size == PLAYERS, "${phones.size} phones") }
    }

    private companion object {
        const val PLAYERS = 50
        const val SEEKERS = 5
        val MINUTES = System.getProperty("hovanki.e2e.fieldLoadMinutes")?.toInt() ?: 5
        const val MB = 1_048_576L

        // Ceilings, picked from the measured run (5 min, 50 bots) with about 1.5x margin (5 min: stored 0.92 MB per
        // player and hour at most, 10.6 events/s per phone, heap 181 MB; 15 min: 0.96 MB, 12.7 events/s, heap 394 MB;
        // 5.0 uploads/s: 50 phones every 10 s, structural).
        // `/sync` p95 was 6 ms; the limit is LoadTest's, for a shared CI runner. A regression of the thinning
        // (rx, frame, air) or a new chatty kind shows as the bytes and the events/s.
        const val MAX_STORED_BYTES_PER_PLAYER_HOUR = 1_500_000L
        const val MAX_EVENTS_PER_SECOND_PER_PHONE = 20.0
        const val MAX_UPLOADS_PER_SECOND = 7.0
        const val MAX_SYNC_P95_MILLIS = 500L

        // The heap after a full collection, bots and server together: below what the staging server has
        // (SERVER_MEM_LIMIT=1g, about 0.75 GB of heap), so a leak shows and a collection's timing never does. The raw
        // `srv` heap_mb (garbage too) is reported only.
        const val MAX_HEAP_AFTER_GC_MB = 768L

        /** The whole report of the game (all its phones' logs, read in windows) on the report thread. */
        const val MAX_REPORT_MILLIS = 120_000L
    }
}
