package app.hovanki.client.lab

import kotlinx.coroutines.CancellationException

/**
 * One question to the server's clock: sent at [sentAtMillis] (device clock) and [sentMono], answered with
 * [serverTimeMillis], received at [receivedMono] (monotonic).
 */
data class ClockSample(val sentAtMillis: Long, val sentMono: Long, val receivedMono: Long, val serverTimeMillis: Long) {
    val rttMillis: Long get() = receivedMono - sentMono

    /** The server's clock minus the device's, taking the server to have answered halfway through the round trip. */
    val offsetMillis: Long get() = serverTimeMillis - (sentAtMillis + rttMillis / 2)
}

/** The device's offset to the server's clock ([offsetMillis]: server − device) and how good it is. */
data class ClockEstimate(val offsetMillis: Long, val rttMillis: Long, val samples: Int, val measuredAtMono: Long)

/**
 * Measures the device's offset to the server's clock the way NTP does (docs/radio-lab.md §4.3): a few questions, the
 * one with the shortest round trip wins. Every device of the lab measures against the same server, so their logs line
 * up to tens of milliseconds.
 */
class LabClockSync(
    private val serverTime: suspend () -> Long,
    private val deviceTimeMillis: () -> Long,
    private val monotonicMillis: () -> Long,
) {
    /** [count] questions; null when none was answered (no network). */
    suspend fun measure(count: Int = SAMPLES): ClockEstimate? {
        val samples = ArrayList<ClockSample>(count)
        repeat(count) {
            val sentAt = deviceTimeMillis()
            val sentMono = monotonicMillis()
            try {
                val server = serverTime()
                samples += ClockSample(sentAt, sentMono, monotonicMillis(), server)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline or refused: this question counts for nothing.
            }
        }
        return best(samples, monotonicMillis())
    }

    companion object {
        const val SAMPLES = 5

        /** How often the lab measures again. */
        const val EVERY_MILLIS = 5 * 60_000L

        fun best(samples: List<ClockSample>, nowMono: Long): ClockEstimate? {
            val best = samples.minByOrNull { it.rttMillis } ?: return null
            return ClockEstimate(best.offsetMillis, best.rttMillis, samples.size, nowMono)
        }
    }
}
