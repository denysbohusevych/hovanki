package app.hovanki.client.lab

import app.hovanki.client.network.ApiException
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.LabRunId
import app.hovanki.shared.protocol.LabUpload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Uploads the lab's [log] to a run on the server (docs/adr/0017-radar-techniques-and-big-run.md §5): every
 * [intervalMillis] the events after the last acknowledged one ([LabLog.pending]), gzipped where the platform can
 * ([gzipOrNull]), in batches of at most [maxEvents] events and [maxBytes]; a full batch sends the next one at once. A
 * failed upload keeps its events for the next attempt: the ring keeps everything until it overflows. Every attempt is
 * a `net` event in the log (the next batch carries it). The server's refusals that won't pass by themselves (the run
 * closed, over its limits, the device unknown: `LAB_RUN_CLOSED`, `LIMIT_REACHED`, 401, 404) stop the uploads, with
 * [lastError]. Main thread, the log's.
 */
class LabUploader(
    private val log: LabLog,
    private val api: LabApi,
    private val scope: CoroutineScope,
    private val intervalMillis: Long = INTERVAL_MILLIS,
    private val monotonicMillis: () -> Long = log::monoNow,
    private val maxEvents: Int = LabUpload.MAX_EVENTS,
    private val maxBytes: Int = LabUpload.MAX_BODY_BYTES,
) {
    private val mutablePending = MutableStateFlow(0L)

    /** Events written after the last one the server acknowledged. */
    val pending: StateFlow<Long> = mutablePending.asStateFlow()

    private val mutableAcked = MutableStateFlow(0L)

    /** The last event the server has ([LabFields.SEQ][app.hovanki.shared.lab.LabFields.SEQ]); 0: none. */
    val ackedSeq: StateFlow<Long> = mutableAcked.asStateFlow()

    private val mutableLastError = MutableStateFlow<String?>(null)

    /** Why the last upload failed; null: it went through. */
    val lastError: StateFlow<String?> = mutableLastError.asStateFlow()

    private val mutableClosed = MutableStateFlow(false)

    /** The server refused for good ([lastError] says why): nothing more is sent until the next [start]. */
    val closed: StateFlow<Boolean> = mutableClosed.asStateFlow()

    private val sending = Mutex()
    private var target: Pair<LabRunId, String>? = null
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Uploads to [runId] with the device [token] of the join, from the oldest event the log still keeps (the lab's log
     * was cleared for the run), every [intervalMillis] until [stop].
     */
    fun start(runId: LabRunId, token: String) {
        stop()
        target = runId to token
        mutableClosed.value = false
        mutableLastError.value = null
        mutableAcked.value = (log.firstKeptSeq ?: log.nextSeq) - 1
        updatePending()
        job = scope.launch {
            while (true) {
                when (uploadOnce()) {
                    Outcome.CLOSED -> return@launch
                    Outcome.MORE -> continue
                    else -> delay(intervalMillis)
                }
            }
        }
    }

    /** No more uploads by the timer; [flush] still sends to the run of the last [start]. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Sends everything written so far, batch after batch, trying again after a failure, until the server has all the
     * log still keeps or [timeoutMillis] ran out. True: all of it went through.
     */
    suspend fun flush(timeoutMillis: Long = FLUSH_MILLIS): Boolean {
        val last = log.nextSeq - 1
        return withTimeoutOrNull(timeoutMillis) {
            while (mutableAcked.value < last) {
                val before = mutableAcked.value
                when (uploadOnce()) {
                    // Nothing kept is left to send (the log was cleared meanwhile).
                    Outcome.IDLE -> return@withTimeoutOrNull true

                    Outcome.CLOSED -> return@withTimeoutOrNull false

                    Outcome.FAILED -> delay(RETRY_MILLIS)

                    // The server took the batch without acknowledging it: not in a tight loop.
                    Outcome.SENT, Outcome.MORE -> if (mutableAcked.value == before) delay(RETRY_MILLIS)
                }
            }
            true
        } ?: false
    }

    private suspend fun uploadOnce(): Outcome = sending.withLock {
        val (runId, token) = target ?: return@withLock Outcome.IDLE
        if (mutableClosed.value) return@withLock Outcome.CLOSED
        val last = log.nextSeq - 1
        val batch = log.pending(mutableAcked.value, maxEvents, maxBytes)
        if (batch == null) {
            updatePending()
            return@withLock Outcome.IDLE
        }
        val gzipped = gzipOrNull(batch.jsonl)
        val body = gzipped ?: batch.jsonl
        val startedAt = monotonicMillis()
        try {
            val answer = api.upload(runId, token, batch, body, gzip = gzipped != null)
            mutableAcked.value = maxOf(mutableAcked.value, answer.ackedSeq)
            mutableLastError.value = null
            updatePending()
            log.net(
                "upload",
                ok = true,
                seqFrom = batch.seqFrom,
                seqTo = batch.seqTo,
                bytes = body.size,
                millis = monotonicMillis() - startedAt,
                pending = mutablePending.value,
            )
            updatePending()
            // Cut by the limits: the rest goes right away.
            if (batch.seqTo < last && mutableAcked.value >= batch.seqTo) Outcome.MORE else Outcome.SENT
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = describe(e)
            mutableLastError.value = reason
            updatePending()
            log.net(
                "upload",
                ok = false,
                seqFrom = batch.seqFrom,
                seqTo = batch.seqTo,
                bytes = body.size,
                millis = monotonicMillis() - startedAt,
                error = reason,
                pending = mutablePending.value,
            )
            updatePending()
            if (e is ApiException && isFinal(e)) {
                mutableClosed.value = true
                Outcome.CLOSED
            } else {
                Outcome.FAILED
            }
        }
    }

    private fun updatePending() {
        mutablePending.value = (log.nextSeq - 1 - mutableAcked.value).coerceAtLeast(0)
    }

    private enum class Outcome { IDLE, SENT, MORE, FAILED, CLOSED }

    companion object {
        const val INTERVAL_MILLIS = 5_000L
        const val FLUSH_MILLIS = 30_000L
        const val RETRY_MILLIS = 1_000L

        /** A refusal that won't pass by itself: the run is over or full, or the server doesn't know the device. */
        fun isFinal(e: ApiException): Boolean = e.status == 401 ||
            e.status == 404 ||
            e.reason == ErrorReason.LAB_RUN_CLOSED ||
            e.reason == ErrorReason.LIMIT_REACHED

        /** An error in a few words for the log and the screen: `409 LAB_RUN_CLOSED`, `IOException: …`. */
        fun describe(e: Exception): String = when (e) {
            is ApiException -> listOfNotNull("${e.status}", e.reason?.name).joinToString(" ")
            else -> listOfNotNull(e::class.simpleName, e.message).joinToString(": ")
        }
    }
}
