package app.hovanki.e2e.route

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Turns the true position into what a phone reports, following docs/adr/0001-stack.md ("Точность GPS"):
 *
 * - Every fix has an accuracy around [accuracyMeters] ± [accuracyJitterMeters] and a Gaussian error that stays within
 *   that accuracy (σ = accuracy / 2, truncated at the accuracy radius).
 * - With [spikeProbability] the fix is taken "next to a building": accuracy degrades to [spikeAccuracyMeters],
 *   the error grows with it. Such fixes are worse than `maxUsableAccuracyMeters` and must not influence decisions.
 * - With [jumpProbability] the fix jumps [jumpMeters] away (multipath) while the accuracy still looks good:
 *   the dangerous case that the "never decide on one fix" rules exist for.
 *
 * Seeded: a scenario sees exactly the same fixes on every run. Not thread-safe; one instance per device.
 * [exact] reports the true position with exactly [accuracyMeters] (see [NONE]).
 */
class GpsNoise(
    val accuracyMeters: Double = 6.0,
    val accuracyJitterMeters: Double = 2.0,
    val spikeProbability: Double = 0.0,
    val spikeAccuracyMeters: ClosedFloatingPointRange<Double> = 30.0..40.0,
    val jumpProbability: Double = 0.0,
    val jumpMeters: ClosedFloatingPointRange<Double> = 20.0..35.0,
    val seed: Long = 1,
    val exact: Boolean = false,
) {
    init {
        require(accuracyMeters > 0 && accuracyJitterMeters >= 0 && accuracyJitterMeters < accuracyMeters)
        require(spikeProbability in 0.0..1.0 && jumpProbability in 0.0..1.0)
    }

    private val random = Random(seed)

    /** Same settings, own random stream: every device needs its own instance. */
    fun copy(seed: Long): GpsNoise = GpsNoise(
        accuracyMeters,
        accuracyJitterMeters,
        spikeProbability,
        spikeAccuracyMeters,
        jumpProbability,
        jumpMeters,
        seed,
        exact,
    )

    fun fix(truth: GeoPoint, timestampMillis: Long, isMock: Boolean = false): LocationSample {
        if (exact) return LocationSample(truth, accuracyMeters, timestampMillis, isMock)
        val roll = random.nextDouble()
        return when {
            roll < jumpProbability -> {
                val direction = random.nextDouble(2 * PI)
                val distance = random.nextDouble(jumpMeters.start, jumpMeters.endInclusive)
                val point = truth.moveBy(distance * cos(direction), distance * sin(direction))
                LocationSample(point, normalAccuracy(), timestampMillis, isMock)
            }

            roll < jumpProbability + spikeProbability -> {
                val accuracy = round(random.nextDouble(spikeAccuracyMeters.start, spikeAccuracyMeters.endInclusive))
                LocationSample(withinAccuracy(truth, accuracy), accuracy, timestampMillis, isMock)
            }

            else -> {
                val accuracy = normalAccuracy()
                LocationSample(withinAccuracy(truth, accuracy), accuracy, timestampMillis, isMock)
            }
        }
    }

    private fun normalAccuracy(): Double = round(
        accuracyMeters +
            if (accuracyJitterMeters == 0.0) 0.0 else random.nextDouble(-accuracyJitterMeters, accuracyJitterMeters),
    )

    /** Gaussian error with σ = accuracy / 2 per axis, truncated at the accuracy radius. */
    private fun withinAccuracy(truth: GeoPoint, accuracy: Double): GeoPoint {
        val sigma = accuracy / 2
        while (true) {
            val east = gaussian() * sigma
            val north = gaussian() * sigma
            if (sqrt(east * east + north * north) <= accuracy) return truth.moveBy(east, north)
        }
    }

    private fun gaussian(): Double {
        // Box–Muller.
        val u1 = random.nextDouble().coerceAtLeast(Double.MIN_VALUE)
        val u2 = random.nextDouble()
        return sqrt(-2 * kotlin.math.ln(u1)) * cos(2 * PI * u2)
    }

    private fun round(value: Double): Double = kotlin.math.round(value * 10) / 10

    companion object {
        /** Exact positions with a constant 5 m accuracy: for checks that must not depend on noise. */
        val NONE: GpsNoise = GpsNoise(accuracyMeters = 5.0, accuracyJitterMeters = 0.0, exact = true)

        /** Park, open sky: 4–8 m, no spikes, no jumps. */
        fun openSky(seed: Long): GpsNoise = GpsNoise(accuracyMeters = 6.0, accuracyJitterMeters = 2.0, seed = seed)

        /** Between buildings: rare accuracy spikes to 30–40 m and rare jumps of 20–35 m. */
        fun city(seed: Long): GpsNoise = GpsNoise(
            accuracyMeters = 8.0,
            accuracyJitterMeters = 3.0,
            spikeProbability = 0.08,
            jumpProbability = 0.03,
            seed = seed,
        )
    }
}
