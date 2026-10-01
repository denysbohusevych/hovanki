package app.hovanki.server.lab

import app.hovanki.server.radio.RadioCalibrationRepository
import app.hovanki.shared.lab.LabReport
import app.hovanki.shared.lab.LabReportBuilder
import app.hovanki.shared.lab.LabReportDevice
import app.hovanki.shared.lab.LabReportInput
import app.hovanki.shared.lab.LabRunScripts
import app.hovanki.shared.lab.ModelOffsets
import app.hovanki.shared.protocol.protocolJson
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Computes the report of a run of the radio lab (docs/adr/0017-radar-techniques-and-big-run.md §5.5) on a thread of
 * its own, like [app.hovanki.server.history.HistoryWriter]: the request that finished the run never waits for it. The
 * devices' chunks are put together into one log per device and handed to [LabReportBuilder]; the report goes to
 * `lab_reports` as JSON, replacing an older one (late uploads compute it again). A run asked for again while it waits
 * is computed once. The logs are read chunk by chunk into lean events, at most [LabProperties.maxReportEvents] of a
 * run (a bigger run's report says so), and only the events within the run's time
 * ([LabProperties.joinWindow] and [LabProperties.uploadGrace] around it) count. Logs only counts and the kind of an
 * error: the logs hold tokens and RSSI.
 */
@Component
class LabReportWriter(
    private val repository: LabRunRepository,
    private val properties: LabProperties,
    private val clock: Clock,
    /** What real games' catches sounded like by model: the report's `calib.model` (ADR 0017 §2.3). */
    private val calibration: RadioCalibrationRepository,
) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val waiting = ConcurrentHashMap.newKeySet<String>()
    private val pool = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(QUEUE_SIZE)) { task ->
        thread(start = false, isDaemon = true, name = "lab-report") { task.run() }
    }

    /** Computes [runId]'s report in the background. */
    fun compute(runId: String) {
        if (!waiting.add(runId)) return
        try {
            pool.execute {
                waiting.remove(runId)
                try {
                    store(runId)
                } catch (e: Throwable) {
                    // An OutOfMemoryError too: the report is lost, the server and its games go on.
                    val sqlState = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<SQLException>()
                        .firstOrNull()?.sqlState
                    log.warn(
                        "Could not compute the report of lab run {}: {} (SQL state {})",
                        runId,
                        e.javaClass.simpleName,
                        sqlState,
                    )
                }
            }
        } catch (e: RejectedExecutionException) {
            waiting.remove(runId)
            log.warn("Lab report queue full, dropped run {}", runId)
        }
    }

    /** Waits until everything handed over so far is computed (tests). */
    fun awaitIdle(timeout: Duration = Duration.ofSeconds(10)) {
        val done = CountDownLatch(1)
        pool.execute { done.countDown() }
        check(done.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) { "Lab reports not computed within $timeout" }
    }

    internal fun store(runId: String) {
        val run = repository.findRun(runId) ?: return
        val script = LabRunScripts.byId(run.scenarioId)?.takeIf { it.version == run.scenarioVersion }
        val devices = repository.devicesOf(runId)
        val events = devices.sumOf { it.events }
        val report = if (events > properties.maxReportEvents) {
            tooBig(run, script?.id, devices, events)
        } else {
            val inputs = devices.map { device ->
                LabReportInput(device.label, device.id, device.radarToken) {
                    LabChunks.lines(repository.chunksOf(device.id), repository::chunkBody)
                }
            }
            // The events a phone's clock put far outside the run are left out: a day around it is plenty.
            val from = run.createdAt.minus(SLACK).toEpochMilli()
            val to = (run.finishedAt ?: clock.instant()).plus(properties.uploadGrace).plus(SLACK).toEpochMilli()
            val offsets = ModelOffsets.fromCatches(calibration.catches())
            LabReportBuilder.build(runId, script, inputs, clock.millis(), from..to, offsets)
        }
        val body = protocolJson.encodeToString(LabReport.serializer(), report)
        repository.upsertReport(runId, report.version, clock.instant(), body)
        log.info("Lab run {}: report of {} devices, {} steps", runId, devices.size, report.steps.size)
    }

    /** The report of a run too big to read here: its devices and why, nothing else. */
    private fun tooBig(run: LabRunRecord, scenarioId: String?, devices: List<LabDeviceRecord>, events: Long) =
        LabReport(
            version = LabReportBuilder.VERSION,
            runId = run.id,
            computedAtMillis = clock.millis(),
            scenarioId = scenarioId,
            devices = devices.map { device ->
                LabReportDevice(
                    label = device.label,
                    deviceId = device.id,
                    model = device.model,
                    os = device.os,
                    build = device.build,
                    commit = device.commit,
                    events = device.events.toInt(),
                    radarToken = device.radarToken,
                )
            },
            problems = listOf(
                "The run has $events events, more than the server's report reads (${properties.maxReportEvents}): " +
                    "download the raw logs and merge them on a computer (./gradlew :e2e:lab --args=\"merge …\")",
            ),
            steps = emptyList(),
            carry = emptyList(),
            masks = emptyList(),
            haptics = emptyList(),
            battery = emptyList(),
            ticks = emptyList(),
        )

    override fun destroy() {
        pool.shutdown()
        pool.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val QUEUE_SIZE = 100
        const val SHUTDOWN_SECONDS = 10L
        val SLACK: Duration = Duration.ofDays(1)
    }
}
