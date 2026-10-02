package app.hovanki.shared.lab

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bluetooth against GPS for an AI (docs/adr/0018-field-test-build.md §6, docs/field-test.md step 9): the pair rows of
 * `digest.jsonl` (`k` = [KIND]: one per pair of players and minute, when they were within [METERS] by GPS or heard
 * each other) and the pure judgement of how far a band the game showed is from the GPS distance. Players are P1…Pn;
 * distances are meters and never coordinates.
 */
object FieldPairs {
    const val KIND = "pair"

    /** A line of the digest saying how many pair rows of a minute were left out by the cap. */
    const val CUT_KIND = "pairs_cut"

    /** Two players are a pair for a minute if they were this near by GPS at any second of it, or heard each other. */
    const val METERS = 60.0

    /** The most pair rows one minute writes to the digest: the heard ones first, then the nearest. */
    const val ROWS_PER_MINUTE = 25

    /** How many of the worst minutes the report lists. */
    const val WORST = 10

    /** The bands from silent to loudest, by rank; what the server says (`NONE`, `WARM`…) in any case. */
    fun rank(band: String?): Int = when (band?.uppercase()) {
        null -> -1
        "WARM" -> 1
        "HOT" -> 2
        "BURNING" -> 3
        else -> 0
    }

    /** The meters a band can honestly stand for (ADR 0012: warm a few dozen, hot some ten, burning a few). */
    private fun range(band: String): ClosedFloatingPointRange<Double> = when (rank(band)) {
        3 -> 0.0..10.0
        2 -> 2.0..25.0
        1 -> 5.0..60.0
        else -> 20.0..Double.MAX_VALUE
    }

    /**
     * How many meters beyond what [band] can stand for the GPS [meters] are, less the GPS's own error [toleranceMeters]
     * (the two phones' accuracies' mean): 0 when they agree. Null: no band or no distance.
     */
    fun disagreement(band: String?, meters: Double?, toleranceMeters: Double = 0.0): Double? {
        if (band == null || meters == null) return null
        val range = range(band)
        val outside = when {
            meters < range.start -> range.start - meters
            meters > range.endInclusive -> meters - range.endInclusive
            else -> 0.0
        }
        return (outside - toleranceMeters).coerceAtLeast(0.0)
    }

    /** The tolerance of two phones' accuracies (m): their mean, zero for a missing one. */
    fun tolerance(accA: Double?, accB: Double?): Double = ((accA ?: 0.0) + (accB ?: 0.0)) / 2

    /** The worst of [rows] first: most disagreement, then earliest, then the pair; at most [WORST]. */
    fun worst(rows: List<FieldReportPairMinute>): List<FieldReportPairMinute> =
        rows.filter { (it.disagreementM ?: 0.0) > 0.0 }
            .sortedWith(compareBy({ -(it.disagreementM ?: 0.0) }, { it.atMillis }, { it.a }, { it.b }))
            .take(WORST)

    /** One pair row of `digest.jsonl`. */
    fun line(row: FieldReportPairMinute): String = buildJsonObject {
        put("t", row.atMillis)
        put("k", KIND)
        put("minute", LabSchema.formatUtc(row.atMillis).substring(11, 16))
        put("a", row.a)
        put("b", row.b)
        fields(row)
    }.toString()

    private fun JsonObjectBuilder.fields(row: FieldReportPairMinute) {
        row.gpsMedian?.let { put("gps_m_median", it) }
        row.gpsMin?.let { put("gps_m_min", it) }
        row.accA?.let { put("gps_acc_a", it) }
        row.accB?.let { put("gps_acc_b", it) }
        row.rssiAb?.let { put("rssi_ab_median", it) }
        row.rssiBa?.let { put("rssi_ba_median", it) }
        put("readings_ab", row.readingsAb)
        put("readings_ba", row.readingsBa)
        row.band?.let { put("band", it) }
        row.shadowBand?.let { put("shadow_band", it) }
        row.carryA?.let { put("carry_a", it) }
        row.carryB?.let { put("carry_b", it) }
        row.platA?.let { put("plat_a", it) }
        row.platB?.let { put("plat_b", it) }
        row.modelA?.let { put("model_a", it) }
        row.modelB?.let { put("model_b", it) }
        put("channels", JsonArray(row.channels.map(::JsonPrimitive)))
        row.disagreementM?.let { put("band_off_m", it) }
    }
}
