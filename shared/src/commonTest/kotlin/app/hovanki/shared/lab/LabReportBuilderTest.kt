package app.hovanki.shared.lab

import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.OverflowCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The report of a made-up run of three phones, their logs written by hand in schema 2 (docs/radio-lab.md §4). */
class LabReportBuilderTest {
    /** One device's log: its lines as the phone writes them, on a clock [offset] ms behind the server's. */
    private class Log(val label: String, val offset: Long) {
        private val lines = ArrayList<String>()
        private var seq = 0L

        fun event(t: Long, k: String, app: String = "active", fields: Array<Pair<String, Any?>> = emptyArray()) {
            val json = buildJsonObject {
                put(LabFields.T, JsonPrimitive(t))
                put(LabFields.DT, JsonPrimitive(t - offset))
                put(LabFields.MONO, JsonPrimitive(t - START))
                put(LabFields.DEV, JsonPrimitive(label))
                put(LabFields.K, JsonPrimitive(k))
                put(LabFields.APP, JsonPrimitive(app))
                put(LabFields.RUN, JsonPrimitive(RUN))
                put(LabFields.SEQ, JsonPrimitive(seq++))
                for ((key, value) in fields) {
                    put(
                        key,
                        when (value) {
                            null -> JsonNull
                            is Number -> JsonPrimitive(value)
                            is Boolean -> JsonPrimitive(value)
                            is Collection<*> -> JsonArray(value.map { JsonPrimitive(it as Int) })
                            else -> JsonPrimitive(value.toString())
                        },
                    )
                }
            }
            lines += json.toString()
        }

        fun rx(t: Long, token: String, rssi: Int, api: String = "corebluetooth", via: String = "name") =
            event(t, "rx", fields = arrayOf("token" to token, "rssi" to rssi, "api" to api, "via" to via))

        fun step(t: Long, index: Int, revision: Long = 1) {
            val step = LabRunScripts.E2E.steps[index]
            event(
                t,
                "step",
                fields = arrayOf("index" to index, "id" to step.id, "title" to step.title, "revision" to revision),
            )
            event(t, "mark", fields = arrayOf("label" to "run: ${step.id}", "by" to "run", "step" to index + 1))
        }

        val jsonl: String get() = lines.joinToString("\n", postfix = "\n")
    }

    private fun session(log: Log, model: String, os: String) = log.event(
        START,
        "session",
        fields = arrayOf("schema" to 2, "model" to model, "os" to os, "build" to "1.0 (7)", "commit" to "abc123"),
    )

    /** A (iPhone) and B (iPhone) and droid, 2 m apart; the steps of [LabRunScripts.E2E] at 1 s, 9 s and 15 s. */
    private fun logs(): List<LabReportInput> {
        val a = Log("A", offset = 400)
        val b = Log("B", offset = -250)
        val droid = Log("droid", offset = 0)
        session(a, "iPhone15,2", "iOS 26.0")
        session(b, "iPhone13,1", "iOS 26.0")
        session(droid, "Pixel 8", "Android 16")
        for ((log, offset) in listOf(a to 400L, b to -250L, droid to 0L)) {
            log.event(START + 10, "clock", fields = arrayOf("offset" to offset, "rtt" to 40, "samples" to 5))
            log.event(START + 20, "battery", fields = arrayOf("level" to 0.9, "state" to "unplugged"))
        }
        // A says what it advertises; B doesn't: the server's radar token tells who it is.
        a.event(START + 30, "adv", fields = arrayOf("action" to "start", "mode" to "hider_name", "token" to A_TOKEN))
        droid.event(
            START + 30,
            "adv",
            fields = arrayOf("action" to "start", "mode" to "hider_service_data", "token" to DROID_TOKEN),
        )
        // Before the first step: droid hears B once.
        droid.rx(START + 500, B_TOKEN, -58, api = "android_scanner", via = "service_data")

        a.step(START + 1_000, 0)
        droid.step(START + 1_050, 0)
        b.step(START + 1_100, 0)
        a.event(
            START + 1_000,
            "mark",
            fields = arrayOf("label" to "pocket", "by" to "tester", "place" to LabPlaces.POCKET_FRONT),
        )
        a.event(START + 1_000, "carry", fields = arrayOf("state" to "in_hand"))
        for (second in 1..7) {
            val t = START + second * 1_000L + 200
            a.rx(t, B_TOKEN, -60 - second)
            a.rx(t + 10, DROID_TOKEN, -70)
            b.rx(t + 20, A_TOKEN, -62)
            droid.rx(t + 30, A_TOKEN, -65, api = "android_scanner", via = "service_data")
            // droid hears B only every other second: fewer than 1 a second.
            if (second % 2 == 1) droid.rx(t + 40, B_TOKEN, -75, api = "android_scanner", via = "service_data")
            for (log in listOf(a, b, droid)) log.event(t + 50, "tick", fields = arrayOf("n" to second))
        }
        a.event(START + 3_000, "carry", fields = arrayOf("state" to "in_pocket"))
        a.event(START + 3_000, "motion", fields = arrayOf("std" to 0.2, "orient" to "upright", "activity" to "walking"))
        b.event(START + 4_000, "haptic", fields = arrayOf("kind" to "core_haptics", "result" to "played"))
        b.event(START + 5_000, "haptic", fields = arrayOf("kind" to "core_haptics", "result" to "engine_stopped"))
        b.event(START + 6_000, "haptic", fields = arrayOf("kind" to "core_haptics", "result" to "error"))
        droid.event(START + 6_000, "haptic", fields = arrayOf("kind" to "vibrator", "result" to "skipped"))

        // The probe: A's token in the overflow area; B hears it once clean and once damaged beyond reading.
        a.step(START + 9_000, 1)
        b.step(START + 9_020, 1)
        droid.step(START + 9_040, 1)
        val bits = OverflowCode.encode(A_TOKEN)
        a.event(
            START + 9_100,
            "adv",
            fields = arrayOf(
                "action" to "start",
                "mode" to "overflow_probe",
                "payload" to bits.sorted().joinToString(","),
            ),
        )
        b.event(START + 10_000, "mask", fields = arrayOf("bits" to bits.sorted(), "rssi" to -66, "api" to "mac"))
        b.event(START + 11_000, "mask", fields = arrayOf("bits" to listOf(1, 2, 3), "rssi" to -67, "api" to "mac"))
        droid.rx(START + 12_000, UNKNOWN_TOKEN, -90, api = "android_scanner", via = "service_data")
        // A suspended for 6 s: no ticks, then again.
        a.event(START + 14_000, "tick", app = "background", fields = arrayOf("n" to 8))

        a.step(START + 15_000, 2)
        b.step(START + 15_010, 2)
        droid.step(START + 15_020, 2)
        a.event(START + 20_000, "battery", fields = arrayOf("level" to 0.85, "state" to "unplugged"))
        b.rx(START + 22_000, A_TOKEN, -61)
        return listOf(
            LabReportInput("A", "dev-a", A_TOKEN, a.jsonl),
            LabReportInput("B", "dev-b", B_TOKEN, b.jsonl),
            LabReportInput("droid", "dev-droid", DROID_TOKEN, droid.jsonl + "{not json\n"),
        )
    }

    private fun report(): LabReport = LabReportBuilder.build(RUN, LabRunScripts.E2E, logs(), NOW)

    @Test
    fun theDevicesAreTold() {
        val report = report()
        assertEquals(RUN, report.runId)
        assertEquals(NOW, report.computedAtMillis)
        assertEquals("e2e", report.scenarioId)
        assertEquals(listOf("A", "B", "droid"), report.devices.map { it.label })
        val a = report.devices[0]
        assertEquals("dev-a", a.deviceId)
        assertEquals("iPhone15,2", a.model)
        assertEquals("1.0 (7)", a.build)
        assertEquals(2, a.schema)
        assertEquals(listOf(400L), a.clockOffsetsMillis)
        assertEquals(A_TOKEN, a.radarToken)
        assertEquals(listOf("droid: 1 lines that are not lab events"), report.problems, "schema 2 is no problem")
    }

    @Test
    fun theStepsComeFromTheStepEvents() {
        val steps = report().steps
        assertEquals(listOf(-1, 0, 1, 2), steps.map { it.index })
        assertEquals(listOf("before", "all_hiders", "probe", "all_again"), steps.map { it.id })
        assertEquals("Everybody advertises as a hider", steps[1].title)
        assertEquals(START + 1_000, steps[1].startMillis, "the earliest device's step event")
        assertEquals(START + 9_000, steps[1].endMillis)
        assertEquals(START + 15_000, steps[3].startMillis)
        assertEquals(START + 22_001, steps[3].endMillis, "the last stretch ends with the last event")
        val before = steps[0].directions.single()
        assertEquals("B" to "droid", before.from to before.to)
    }

    @Test
    fun theDirectionsOfAStep() {
        val directions = report().steps[1].directions.associateBy { "${it.from}>${it.to} ${it.channel}" }
        val bToA = assertNotNull(directions["B>A corebluetooth/name"], "$directions")
        assertEquals(7, bToA.readings)
        assertEquals(0.88, bToA.perSecond, "7 readings in 8 s")
        assertEquals(-64, bToA.medianRssi)
        assertEquals(-62, bToA.p80Rssi, "the nearest rank in the loudness's order, as the merge's summary")
        assertEquals(-67, bToA.minRssi)
        assertEquals(-61, bToA.maxRssi)
        assertEquals(1_800L, bToA.longestGapMillis, "the last reading to the step's end")
        // B never said what it advertised: its radar token names it.
        assertEquals(4, directions.getValue("B>droid android_scanner/service_data").readings)
        assertEquals(7, directions.getValue("A>B corebluetooth/name").readings)
        assertEquals(7, directions.getValue("A>droid android_scanner/service_data").readings)
        assertEquals(7, directions.getValue("droid>A corebluetooth/name").readings)
        assertEquals(5, directions.size, "$directions")
    }

    @Test
    fun anUnknownSenderIsNotNamed() {
        // A token nobody in the run advertised may be a real game's nearby: the report never keeps it.
        val report = report()
        val probe = report.steps[2].directions
        assertEquals(listOf(LabReportBuilder.UNKNOWN_SENDER), probe.map { it.from })
        val json = protocolJson.encodeToString(LabReport.serializer(), report)
        assertTrue(UNKNOWN_TOKEN !in json)
    }

    @Test
    fun eventsOutsideTheRunsTimeAreLeftOut() {
        // A phone's clock far off, and a motion event without a time: neither stretches the report.
        val log = Log("A", offset = 0)
        session(log, "iPhone15,2", "iOS 26.0")
        log.event(START + 10, "clock", fields = arrayOf("offset" to 0))
        log.event(START + 1_000, "carry", fields = arrayOf("state" to "in_hand"))
        log.event(START + 5_000, "tick", fields = arrayOf("n" to 1))
        log.event(START + 400L * 24 * 3_600_000, "motion", fields = arrayOf("std" to 0.1))
        val untimed = """{"dt":"soon","k":"motion","std":0.3,"seq":99}"""
        val input = LabReportInput("A", "d", radarToken = null, jsonl = log.jsonl + untimed + "\n")
        val window = START - 3_600_000..NOW + 3_600_000
        val report = LabReportBuilder.build(RUN, LabRunScripts.E2E, listOf(input), NOW, window)
        assertEquals(4, report.devices.single().events)
        assertEquals(listOf("A: 2 events outside the run's time, left out"), report.problems)
        assertEquals(START + 5_001, report.steps.single().endMillis)

        // Without a window a device's pocket seconds still stop after two days.
        val merge = LabMerge(listOf("A" to log.jsonl))
        assertEquals(LabMerge.MAX_CARRY_SPAN_MILLIS / 1_000 + 1, merge.carrySeconds().size.toLong())
    }

    @Test
    fun thePocketTheMasksAndTheVibration() {
        val report = report()
        val carry = report.carry.filter { it.label == "A" }.associate { (it.truth to it.said) to it.seconds }
        assertEquals(2, carry[("in_pocket" to "in_hand")], "$carry")
        assertTrue((carry[("in_pocket" to "in_pocket")] ?: 0) >= 15, "$carry")
        assertEquals(listOf(LabReportMask("B", frames = 2, matched = 1, decoded = 1)), report.masks)
        assertEquals(
            setOf(
                LabReportHaptic("B", "core_haptics", played = 1, errors = 1, skipped = 0, engineStopped = 1),
                LabReportHaptic("droid", "vibrator", played = 0, errors = 0, skipped = 1, engineStopped = 0),
            ),
            report.haptics.toSet(),
        )
        val battery = report.battery.single { it.label == "A" }
        assertEquals(LabReportBattery("A", 0.9, 0.85, 2), battery)
    }

    @Test
    fun theTicksShowTheAppSuspended() {
        val ticks = report().ticks.associateBy { it.label }
        assertEquals(LabReportTicks("A", ticks = 8, gaps = 1, longestGapMillis = 6_750), ticks["A"])
        assertEquals(LabReportTicks("B", ticks = 7, gaps = 0, longestGapMillis = 1_000), ticks["B"])
    }

    @Test
    fun theReportGoesOnTheWire() {
        val report = report()
        val json = protocolJson.encodeToString(LabReport.serializer(), report)
        assertEquals(report, protocolJson.decodeFromString(LabReport.serializer(), json))
    }

    @Test
    fun noLogsNoSteps() {
        val empty = LabReportBuilder.build(RUN, null, emptyList(), NOW)
        assertEquals(emptyList(), empty.steps)
        assertEquals(null, empty.scenarioId)
    }

    @Test
    fun oldLogsFallBackToTheRunsMarks() {
        // Schema 1: no step events, only the local runner's marks.
        val log = Log("A", offset = 0)
        log.event(START, "session", fields = arrayOf("schema" to 1))
        log.event(START + 10, "clock", fields = arrayOf("offset" to 0))
        log.event(START + 1_000, "mark", fields = arrayOf("label" to "run: all_hiders", "by" to "run", "step" to 1))
        log.event(START + 2_000, "mark", fields = arrayOf("label" to "run: probe", "by" to "run", "step" to 2))
        log.event(START + 2_500, "mark", fields = arrayOf("label" to "run: probe", "by" to "run", "step" to 2))
        log.event(START + 9_000, "mark", fields = arrayOf("label" to "run: probe", "by" to "run", "step" to 2))
        log.event(START + 9_500, "mark", fields = arrayOf("label" to "run: done", "by" to "run"))
        val input = LabReportInput("A", "d", radarToken = null, jsonl = log.jsonl)
        val report = LabReportBuilder.build(RUN, LabRunScripts.E2E, listOf(input), NOW)
        assertEquals(listOf("before", "all_hiders", "probe", "probe"), report.steps.map { it.id })
        assertEquals("A's overflow probe", report.steps[2].title)
        assertEquals(listOf(START + 2_000, START + 9_000), report.steps.drop(2).map { it.startMillis }, "a repeat")
        assertEquals(emptyList(), report.problems)
    }

    private companion object {
        const val START = 1_790_000_000_000L
        const val NOW = START + 60_000
        const val RUN = "run-1"
        const val A_TOKEN = "aaaa0001"
        const val B_TOKEN = "bbbb0001"
        const val DROID_TOKEN = "dddd0001"
        const val UNKNOWN_TOKEN = "eeee0009"
    }
}
