package app.hovanki.shared.lab

import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.rules.ProximityRules
import app.hovanki.shared.rules.Smoothings
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * One device's made-up lab log for the radar's tests, its lines as the phone writes them (docs/radio-lab.md §4.1) on
 * the server's clock: it says what it advertises first, so [LabMerge.sender] knows it.
 */
internal class RadarTestLog(val label: String, val token: String) {
    private val lines = ArrayList<String>()
    private var seq = 0L

    init {
        event(0, "adv", "action" to "start", "mode" to "hider_name", "token" to token)
    }

    fun event(t: Long, k: String, vararg fields: Pair<String, Any?>) {
        val json = buildJsonObject {
            put(LabFields.T, JsonPrimitive(t))
            put(LabFields.DT, JsonPrimitive(t))
            put(LabFields.MONO, JsonPrimitive(t))
            put(LabFields.DEV, JsonPrimitive(label))
            put(LabFields.K, JsonPrimitive(k))
            put(LabFields.APP, JsonPrimitive("active"))
            put(LabFields.SEQ, JsonPrimitive(seq++))
            for ((key, value) in fields) {
                put(
                    key,
                    when (value) {
                        null -> JsonNull
                        is Number -> JsonPrimitive(value)
                        else -> JsonPrimitive(value.toString())
                    },
                )
            }
        }
        lines += json.toString()
    }

    /** This device hears [from] at [rssi]. */
    fun rx(t: Long, from: RadarTestLog, rssi: Int) =
        event(t, "rx", "token" to from.token, "rssi" to rssi, "api" to "corebluetooth", "via" to "name")

    /** [rssi] of [from] every [everyMillis] in `[start, end)`. */
    fun hears(from: RadarTestLog, rssi: Int, start: Long, end: Long, everyMillis: Long = 200) {
        var t = start
        while (t < end) {
            rx(t, from, rssi)
            t += everyMillis
        }
    }

    fun impact(t: Long, peakG: Double, ago: Long = 0) = event(t, "impact", "peak" to peakG, "ago" to ago)

    fun mark(t: Long, label: String, vararg fields: Pair<String, Any?>) = event(t, "mark", "label" to label, *fields)

    /** The «чокнулись» button: the truth of a touch with [other]. */
    fun touched(t: Long, other: String) =
        mark(t, "touch ${RunStep.pairKey(label, other)}", "by" to "user", "action" to "touch")

    val jsonl: String get() = lines.joinToString("\n", postfix = "\n")
}

internal fun radarMerge(vararg logs: RadarTestLog): LabMerge = LabMerge(logs.map { it.label to it.jsonl })

/** The radar over a run's logs: the truth by distance, the distances, the band tracks, the calibrations, the errors. */
class LabRadarTest {
    private val a = RadarTestLog("A", "aaaa0001")
    private val b = RadarTestLog("B", "bbbb0002")
    private val c = RadarTestLog("C", "cccc0003")

    private fun marks(merge: LabMerge) = merge.events.filter { it.k == "mark" }

    private fun track(merge: LabMerge, calibration: Calibration = Calibrations.none(), smoothing: String = EMA) =
        BandTrack(smoothing, calibration).apply { for (event in merge.events) feed(event, merge::sender) }

    @Test
    fun theTruthOfABandIsTheDistance() {
        val bands = listOf(0.0, 1.5, 1.6, 4.0, 10.0, 15.0, 15.1).map(LabBandTruth::bandFor)
        val expected = listOf(BURNING, BURNING, HOT, HOT, WARM, WARM, NONE)
        assertEquals(expected, bands)
    }

    @Test
    fun theDistancesComeFromTheStepsAndTheMarks() {
        b.mark(5_000, "3 m", "by" to "tester", "distance" to 3.0)
        // The run's own mark carries the smallest of the step's distances: the stretch has them by pair.
        a.mark(2_000, "run: near", "by" to "run", "step" to 1, "distance" to 7.0)
        c.mark(12_000, "2 m", "by" to "tester", "distance" to 2.0)
        val stretches = listOf(
            LabDistanceStretch(0, 10_000, mapOf("A|B" to 1.0, "A|C" to 5.0)),
            LabDistanceStretch(10_000, 20_000, mapOf("A|B" to 20.0)),
        )
        val distances = LabDistances(stretches, marks(radarMerge(a, b, c)))
        assertEquals(1.0, distances.at("A|B", 1_000))
        assertEquals(1.0, distances.at("A|B", 3_000), "the run's mark is not a distance of its own")
        assertEquals(3.0, distances.at("A|B", 6_000), "B's mark holds for every pair of B")
        assertEquals(3.0, distances.at("B|C", 6_000))
        assertEquals(5.0, distances.at("A|C", 6_000))
        assertEquals(20.0, distances.at("A|B", 12_000), "a mark holds until its stretch ends")
        assertEquals(2.0, distances.at("A|C", 13_000))
        assertNull(distances.at("A|C", 11_000), "the second step says nothing of A and C")
        assertNull(distances.at("A|B", 25_000), "after the last stretch")
    }

    @Test
    fun withoutStretchesAMarkHoldsUntilTheNext() {
        a.mark(1_000, "3 m", "by" to "tester", "distance" to 3.0)
        b.mark(5_000, "8 m", "by" to "tester", "distance" to 8.0)
        val distances = LabDistances(emptyList(), marks(radarMerge(a, b)))
        assertNull(distances.at("A|B", 500))
        assertEquals(3.0, distances.at("A|B", 2_000))
        assertEquals(8.0, distances.at("A|B", 100_000))
    }

    @Test
    fun aPairsBandIsTheLouderDirection() {
        a.hears(b, -80, 0, 5_000)
        b.hears(a, -65, 0, 5_000)
        val track = track(radarMerge(a, b))
        assertEquals(WARM, track.directionBandAt(from = "B", to = "A", t = 4_000))
        assertEquals(HOT, track.directionBandAt(from = "A", to = "B", t = 4_000))
        assertEquals(HOT, track.pairBandAt("A|B", 4_000))
        assertEquals(NONE, track.pairBandAt("A|B", -1))
        assertEquals(HOT, track.pairBandAt("A|B", 4_800 + ProximityRules.SIGNAL_TTL_MILLIS))
        assertEquals(NONE, track.pairBandAt("A|B", 4_800 + ProximityRules.SIGNAL_TTL_MILLIS + 1))
        assertEquals(setOf("B|A", "A|B"), track.directions)
        assertEquals(setOf("A|B"), track.pairs)
    }

    @Test
    fun aCalibrationShiftsTheBands() {
        a.hears(b, -80, 0, 5_000)
        val merge = radarMerge(a, b)
        assertEquals(WARM, track(merge).directionBandAt("B", "A", 4_000))
        val louder = Calibration("test", mapOf(Calibration.direction("B", "A") to 15.0))
        assertEquals(HOT, track(merge, louder).directionBandAt("B", "A", 4_000))
        assertEquals(0.0, louder.offset("A", "B"), "the other direction has none")
    }

    @Test
    fun theModelCalibrationTakesTheMetreSteps() {
        a.hears(b, -70, 0, 5_000)
        b.hears(a, -64, 0, 5_000)
        a.hears(b, -90, 5_000, 10_000)
        c.hears(a, -50, 0, 5_000)
        val merge = radarMerge(a, b, c)
        val distances = LabDistances(
            listOf(
                LabDistanceStretch(0, 5_000, mapOf("A|B" to 1.0, "A|C" to 1.0)),
                LabDistanceStretch(5_000, 10_000, mapOf("A|B" to 10.0)),
            ),
            emptyList(),
        )
        val models = mapOf("A" to "iPhone15,2", "B" to "Pixel 8", "C" to null)
        val calibration = Calibrations.model(merge.events, merge::sender, distances, models)
        assertEquals(Calibrations.MODEL, calibration.id)
        // −60 dBm at a metre less the median there; C has no model: none for its directions.
        assertEquals(mapOf("B|A" to 10.0, "A|B" to 4.0), calibration.offsetsDb)
    }

    @Test
    fun theTouchCalibrationTakesTheMedianTouch() {
        fun touch(t: Long, ab: Int, ba: Int) = Touch("A|B", t, mapOf("A|B" to ab, "B|A" to ba), emptyMap(), null)
        val calibration = Calibrations.touch(listOf(touch(0, -45, -38), touch(5_000, -44, -42), touch(9_000, -50, -40)))
        assertEquals(Calibrations.TOUCH, calibration.id)
        assertEquals(mapOf("A|B" to 5.0, "B|A" to 0.0), calibration.offsetsDb)
        assertEquals(emptyMap(), Calibrations.touch(emptyList()).offsetsDb)
        assertEquals(emptyMap(), Calibrations.none().offsetsDb)
    }

    /** A hears B at a steady HOT for 12 s; the steps and a mark say 3 m, 1 m, 10 m, then 30 m. */
    private fun steadyHot(): Pair<LabMerge, LabDistances> {
        a.hears(b, -65, 0, 12_000)
        a.mark(10_000, "30 m", "by" to "tester", "distance" to 30.0)
        val merge = radarMerge(a, b)
        val stretches = listOf(
            LabDistanceStretch(0, 4_000, mapOf("A|B" to 3.0)),
            LabDistanceStretch(4_000, 8_000, mapOf("A|B" to 1.0)),
            LabDistanceStretch(8_000, 12_000, mapOf("A|B" to 10.0)),
        )
        return merge to LabDistances(stretches, marks(merge))
    }

    @Test
    fun theBandErrorCountsExactOneOffAndWrong() {
        val (merge, distances) = steadyHot()
        val error = BandErrors.of(track(merge), distances, listOf("A|B"), 0L..11_000L)
        // HOT against HOT ×4, BURNING ×4, WARM ×2, NONE ×2 (the mark).
        assertEquals(BandError(EMA, Calibrations.NONE, 12, exact = 4, oneOff = 6, wrong = 2, 10.0 / 12), error)
        assertEquals(4.0 / 12, error.exactShare)
        val none = BandErrors.of(track(merge), distances, listOf("B|C"), 0L..11_000L)
        assertEquals(0, none.seconds, "no truth for B and C")
        assertEquals(0.0, none.exactShare)
    }

    @Test
    fun everySmoothingMeetsEveryCalibration() {
        val (merge, distances) = steadyHot()
        val touch = Touch("A|B", 0, mapOf("B|A" to -50), emptyMap(), null)
        val errors = BandErrors.all(merge.events, merge::sender, distances, listOf(touch), emptyMap())
        assertEquals(
            Smoothings.ALL.flatMap { smoothing -> Calibrations.ALL.map { smoothing to it } },
            errors.map { it.smoothing to it.calibration },
        )
        val ema = errors.filter { it.smoothing == EMA }.associateBy { it.calibration }
        assertEquals(12, ema.getValue(Calibrations.NONE).seconds, "the seconds from the first event to the last")
        assertEquals(6, ema.getValue(Calibrations.NONE).oneOff)
        assertEquals(ema.getValue(Calibrations.NONE), ema.getValue(Calibrations.MODEL).copy(calibration = NONE_ID))
        // Heard 10 dB under the touch's reference: BURNING throughout, right for 1 m only.
        val touched = ema.getValue(Calibrations.TOUCH)
        assertEquals(listOf(4, 4, 4), listOf(touched.exact, touched.oneOff, touched.wrong))
    }

    @Test
    fun thePairsAndSecondsOfARun() {
        a.rx(1_500, b, -60)
        c.rx(3_200, a, -60)
        val events = radarMerge(a, b, c).events.filter { it.k == "rx" }
        assertEquals(listOf("A|C"), BandErrors.pairsOf(events), "B wrote no reading")
        assertEquals(listOf(2_000L, 3_000L), BandErrors.secondsOf(events).toList())
        assertEquals(emptyList(), BandErrors.secondsOf(emptyList()).toList())
    }

    private companion object {
        const val EMA = Smoothings.EMA
        const val NONE_ID = Calibrations.NONE
        val NONE = RadarBand.NONE
        val WARM = RadarBand.WARM
        val HOT = RadarBand.HOT
        val BURNING = RadarBand.BURNING
    }
}
