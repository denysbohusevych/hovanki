package app.hovanki.shared.lab

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The report's techniques (docs/radar-run.md step 4) on an invented run of [LabRunScripts.TOUCH]: A (an iPhone, its
 * token in the name) and B (an Android, its token in two layouts at once) 2 m apart, three touches with the button
 * in the second step, the pocket's two classifiers on A.
 */
class LabTechniquesTest {
    private class Log(val label: String) {
        private val lines = ArrayList<String>()
        private var seq = 0L

        fun event(t: Long, k: String, vararg fields: Pair<String, Any?>) {
            val json = buildJsonObject {
                put(LabFields.T, JsonPrimitive(t))
                put(LabFields.DT, JsonPrimitive(t))
                put(LabFields.MONO, JsonPrimitive(t))
                put(LabFields.DEV, JsonPrimitive(label))
                put(LabFields.K, JsonPrimitive(k))
                put(LabFields.SEQ, JsonPrimitive(++seq))
                for ((key, value) in fields) {
                    when (value) {
                        null -> Unit
                        is Number -> put(key, JsonPrimitive(value))
                        else -> put(key, JsonPrimitive(value.toString()))
                    }
                }
            }
            lines += json.toString()
        }

        fun rx(t: Long, token: String, rssi: Int, tech: String) =
            event(t, FieldKinds.RX, RxFields.TOKEN to token, RxFields.RSSI to rssi, RxFields.TECH to tech)

        val jsonl: String get() = lines.joinToString("\n", postfix = "\n")
    }

    private fun run(): LabReport {
        val a = Log("A")
        val b = Log("B")
        for (log in listOf(a, b)) {
            log.event(0, "session", "schema" to 2, "model" to if (log === a) "iPhone15,2" else "Pixel 8")
            log.event(1, "clock", "offset" to 0)
        }
        a.event(10, LabRadarKinds.ADV, "action" to "start", "token" to A_TOKEN, "tech" to NAME)
        b.event(10, LabRadarKinds.ADV, "action" to "start", "token" to B_TOKEN, "tech" to "$SCAN_RESPONSE,$MFR")
        val script = LabRunScripts.TOUCH
        var start = 1_000L
        for ((index, step) in script.steps.withIndex()) {
            for (log in listOf(a, b)) log.event(start, "step", "index" to index, "id" to step.id, "revision" to 1)
            start += step.seconds!! * 1_000L
        }
        val end = start
        // Every second: B hears A by its name only; A hears B by both of its layouts.
        var t = 1_000L
        while (t < end) {
            a.rx(t + 100, B_TOKEN, -64, SCAN_RESPONSE)
            a.rx(t + 120, B_TOKEN, -64, MFR)
            b.rx(t + 300, A_TOKEN, -63, NAME)
            t += 1_000
        }
        // Three touches in the step «touch» (9 s … 54 s): the jolts, the peak, both press the button after.
        for (at in listOf(20_000L, 30_000L, 40_000L)) {
            a.event(at, TouchKinds.TOUCH, TouchFields.SRC to TouchFields.IMPACT, TouchFields.G to 2.0)
            b.event(at + 60, TouchKinds.TOUCH, TouchFields.SRC to TouchFields.IMPACT, TouchFields.G to 1.5)
            a.rx(at + 200, B_TOKEN, -44, SCAN_RESPONSE)
            b.rx(at + 250, A_TOKEN, -42, NAME)
            a.event(at + 1_500, TouchKinds.TOUCH, TouchFields.SRC to TouchFields.BUTTON, TouchFields.PARTNER to "B")
            b.event(at + 1_800, TouchKinds.TOUCH, TouchFields.SRC to TouchFields.BUTTON, TouchFields.PARTNER to "A")
        }
        // A press nobody answered, far from any touch: a touch by the button only.
        b.event(58_000, TouchKinds.TOUCH, TouchFields.SRC to TouchFields.BUTTON, TouchFields.PARTNER to "A")
        // The pocket: A is in the hand all along; the game's classifier says so, the candidate says the pocket.
        a.event(1_000, "mark", "label" to "hand", "by" to "tester", "place" to LabPlaces.HAND)
        a.event(1_000, "carry", "state" to "in_hand")
        a.event(1_000, LabRadarKinds.SHADOW, ShadowFields.TECH to CarryTechs.V2, ShadowFields.STATE to "in_pocket")
        a.event(1_000, LabRadarKinds.SHADOW, ShadowFields.TECH to CarryTechs.V1, ShadowFields.STATE to "in_hand")
        for (log in listOf(a, b)) log.event(end, "tick", "n" to 1)
        return LabReportBuilder.build(
            "r1",
            script,
            listOf(LabReportInput("A", "dA", A_TOKEN, a.jsonl), LabReportInput("B", "dB", B_TOKEN, b.jsonl)),
            nowMillis = end,
            modelOffsets = ModelOffsets(mapOf(("iPhone15,2" to "Pixel 8") to 5.0)),
        )
    }

    @Test
    fun theTouchesAreFoundAndConfirmedByTheButton() {
        val report = run()

        val both = report.touches.filter { it.source == LabReportTouch.BOTH }
        assertEquals(listOf(20_030L, 30_030L, 40_030L), both.map { it.atMillis })
        assertTrue(both.all { it.a == "A" && it.b == "B" && it.rssiAToB == -42 && it.rssiBToA == -44 })
        assertTrue(both.all { it.impactA == 2.0 && it.impactB == 1.5 && it.skewMillis == 60L })
        val pressed = report.touches.single { it.source == LabReportTouch.BUTTON }
        assertEquals(58_000L, pressed.atMillis)
        assertEquals(LabReportTouchDetector(buttonTouches = 4, found = 3, falseAlarms = 0), report.touchDetector)

        val pair = report.touchPairs.single()
        assertEquals(4, pair.touches)
        // −45 dBm is what touching phones should hear: A was heard at −42 (3 dB too loud), B at −44.
        assertEquals(-3.0, pair.offsetAToB)
        assertEquals(-1.0, pair.offsetBToA)
        assertEquals(0.0, pair.spreadDb)
        assertNotNull(pair.driftDb)
    }

    @Test
    fun withoutTheOnlyChannelTheBandGoesAndWithoutATwinItStays() {
        val without = run().without.associateBy { it.tech }

        // B heard A by its name only: without the name nothing is left.
        val name = without.getValue(NAME)
        assertEquals(0.0, name.equalPercent)
        assertTrue(name.aloneSeconds > 50, "seconds only the name heard: ${name.aloneSeconds}")
        // A heard B by two layouts at once: without either the band is the same.
        assertEquals(100.0, without.getValue(SCAN_RESPONSE).equalPercent)
        assertEquals(0, without.getValue(MFR).aloneSeconds)
    }

    @Test
    fun theBandsErrorByCalibrationAndSmoothing() {
        val report = run()

        val byTech = report.calibration.associateBy { it.tech }
        assertEquals(CalibrationVariant.entries.map { it.id }.toSet(), byTech.keys)
        val none = byTech.getValue(CalibrationVariant.NONE.id)
        // 2 m is «burning» by the plan's meaning, heard at −64 dBm it is «hot»: wrong until the first touch lifts the
        // band to «burning», where the hysteresis keeps it (−64 is above its exit, −66).
        assertTrue(none.seconds > 60 && none.wrong in none.seconds / 2 until none.seconds, "$none")
        assertEquals(2, none.directions)
        // The model offset is there for what the iPhone hears of the Pixel only.
        assertEquals(1, byTech.getValue(CalibrationVariant.MODEL.id).uncalibrated)
        assertEquals(0, byTech.getValue(CalibrationVariant.TOUCH.id).uncalibrated)
        assertEquals(SmoothingVariant.entries.map { it.id }, report.smoothing.map { it.tech })
        assertTrue(report.smoothing.all { it.seconds == none.seconds })
    }

    @Test
    fun thePocketsClassifiersAgainstTheMarks() {
        val rows = run().carryClassifiers.associateBy { it.tech }

        assertEquals(setOf(CarryTechs.V1, CarryTechs.V2), rows.keys)
        assertEquals(100.0, rows.getValue(CarryTechs.V1).agreePercent)
        assertEquals(0.0, rows.getValue(CarryTechs.V2).agreePercent)
        assertTrue(rows.values.all { it.label == "A" && it.seconds > 60 })
    }

    @Test
    fun theCardsSayWhatStaysAndWhatGoes() {
        val cards = run().cards.associateBy { it.id }

        assertEquals(LabReportCard.KEEP, cards.getValue(CarryTechs.V1).verdict)
        assertEquals(LabReportCard.DROP, cards.getValue(CarryTechs.V2).verdict)
        // 3 of the 4 touches the button knows: the one B pressed alone was missed.
        assertEquals(LabReportCard.DROP, cards.getValue("touch.detector").verdict)
        // Two phones only: the witness has nobody to ask.
        assertEquals(LabReportCard.TOO_LITTLE, cards.getValue("infer.witness").verdict)
        // A heard B's two layouts about once a second each at 2 m: under the 2 a second the channel needs.
        assertEquals(LabReportCard.DROP, cards.getValue(SCAN_RESPONSE).verdict)
        assertTrue(cards.keys.containsAll(listOf("smooth.ema", "smooth.p80", "smooth.rate", "calib.touch")))
        assertTrue(cards.values.all { it.criterion.isNotBlank() && it.numbers.isNotBlank() })
    }

    @Test
    fun aRunWithoutAnyOfItHasEmptySections() {
        val log = Log("A")
        log.event(0, "session", "schema" to 2)
        log.event(10, "tick", "n" to 1)
        val report = LabReportBuilder.build("r2", null, listOf(LabReportInput("A", "dA", null, log.jsonl)), 10)

        assertTrue(report.touches.isEmpty() && report.touchPairs.isEmpty() && report.without.isEmpty())
        assertNull(report.touchDetector)
        assertNull(report.witness)
        assertTrue(report.calibration.all { it.seconds == 0 })
        assertEquals(LabReportBuilder.VERSION, report.version)
    }

    private companion object {
        const val A_TOKEN = "aaaa0001"
        const val B_TOKEN = "bbbb0002"
        const val NAME = "ble.name"
        const val SCAN_RESPONSE = "ble.service_data.scan_response"
        const val MFR = "ble.service_data.mfr"
    }
}
