package app.hovanki.shared.lab

/**
 * The calibrations that compete (docs/adr/0017-radar-techniques-and-big-run.md §2.3 and §3): [NONE] the readings as
 * heard, [MODEL] an offset by the two phones' models from what real games' catches sounded like (`radio_calibration`,
 * [ModelOffsets]), [TOUCH] an offset of the pair from its touches ([Calibration.touchOffset]). Decided by the band's
 * error against the run's distances.
 */
enum class CalibrationVariant(val id: String) {
    NONE("calib.none"),
    MODEL("calib.model"),
    TOUCH("calib.touch"),
}

/** How loud a [hearerModel] heard a [heardModel] at a catch: [readings] readings at [rssiDbm]. */
data class CalibrationSample(val hearerModel: String, val heardModel: String, val rssiDbm: Int, val readings: Int)

/**
 * The dB to add to what one model hears of another ([of]), by the models' catches: a catch is a metre apart
 * (`CalibrationAnchor.CATCH` of `radio_calibration`), so the offset is [Calibration.METER_DBM] minus the median of what
 * the pair of models heard then. A pair of models with fewer than [Calibration.MIN_READINGS] readings has none.
 */
class ModelOffsets(private val offsets: Map<Pair<String, String>, Double>) {
    fun of(hearerModel: String?, heardModel: String?): Double? {
        if (hearerModel == null || heardModel == null) return null
        return offsets[hearerModel to heardModel]
    }

    val size: Int get() = offsets.size

    companion object {
        val NONE = ModelOffsets(emptyMap())

        fun fromCatches(samples: List<CalibrationSample>): ModelOffsets = ModelOffsets(
            samples.groupBy { it.hearerModel to it.heardModel }.mapNotNull { (models, buckets) ->
                val median = weightedMedian(buckets.map { it.rssiDbm to it.readings }) ?: return@mapNotNull null
                if (buckets.sumOf { it.readings.toLong() } < Calibration.MIN_READINGS) return@mapNotNull null
                models to Calibration.METER_DBM - median
            }.toMap(),
        )

        private fun weightedMedian(values: List<Pair<Int, Int>>): Double? {
            val total = values.sumOf { it.second.toLong() }
            if (total <= 0) return null
            var seen = 0L
            for ((value, weight) in values.sortedBy { it.first }) {
                seen += weight
                if (seen * 2 >= total) return value.toDouble()
            }
            return null
        }
    }
}

/** The touch calibration's numbers (ADR 0017 §3). Pure. */
object Calibration {
    /** What a pair a metre apart hears (ADR 0012 §2: about −60 dBm on open ground). */
    const val METER_DBM = -60.0

    /** What two phones touching should hear of each other: a guess until the run's touches with the button. */
    const val TOUCH_DBM = -45.0

    /** A model pair's offset needs this many catch readings. */
    const val MIN_READINGS = 20

    /** The offsets of a pair's first touches are its calibration ([touchOffset]); the later ones show the drift. */
    const val CALIBRATION_TOUCHES = 3

    /** A pair's touches may spread this much for [CalibrationVariant.TOUCH] to stay (ADR 0017 §3). */
    const val MAX_SPREAD_DB = 6.0

    /** It stays if the band's error is smaller by this many percentage points than without it. */
    const val MIN_GAIN_POINTS = 10.0

    /** The dB to add to one direction from what it heard at its first touches; null: no touch heard. */
    fun touchOffset(touchRssi: List<Int>): Double? {
        val first = touchRssi.take(CALIBRATION_TOUCHES).sorted().ifEmpty { return null }
        return TOUCH_DBM - median(first)
    }

    /** How far apart the first touches of one direction were, dB; null: fewer than two. */
    fun spread(touchRssi: List<Int>): Double? {
        val first = touchRssi.take(CALIBRATION_TOUCHES)
        if (first.size < 2) return null
        return (first.max() - first.min()).toDouble()
    }

    /** The last touch against the first ones, dB (positive: louder at the end); null: fewer than two touches. */
    fun drift(touchRssi: List<Int>): Double? {
        if (touchRssi.size < 2) return null
        return touchRssi.last() - median(touchRssi.take(minOf(CALIBRATION_TOUCHES, touchRssi.size - 1)).sorted())
    }

    private fun median(sorted: List<Int>): Double = if (sorted.size % 2 == 1) {
        sorted[sorted.size / 2].toDouble()
    } else {
        (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }
}
