package app.hovanki.client.lab

import app.hovanki.radar.RadioApi
import app.hovanki.radar.SightingVia
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.lab.LabSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LabLogTest {
    private var now = 1_790_000_000_000L
    private var mono = 5_000L

    private fun log(capacity: Int = LabLog.CAPACITY) =
        LabLog(isEnabled = true, { now }, { mono }, capacity, Random(1)).also { it.isRecording = true }

    private fun LabLog.parsed(): List<JsonObject> = lines().map { Json.parseToJsonElement(it).jsonObject }

    @Test
    fun releaseBuildsAndAStoppedLabRecordNothing() {
        val off = LabLog.Off
        off.isRecording = true
        off.note("nothing")
        off.rx("0123abcd", -60, RadioApi.ANDROID_LE, SightingVia.SERVICE_DATA)
        assertEquals(0, off.lines().size)

        val idle = LabLog(isEnabled = true, { now }, { mono })
        idle.note("not recording")
        idle.tick(0)
        assertEquals(0, idle.lines().size)
    }

    @Test
    fun everyEventHasTheCommonFieldsAndTheServerTime() {
        val log = log()
        log.setLabel("B")
        log.appState = { "background" }
        log.note("before the clock")
        log.setClock(ClockEstimate(offsetMillis = 1_234, rttMillis = 40, samples = 5, measuredAtMono = mono))
        now += 10
        mono += 10
        log.note("after")

        val (before, clock, after) = log.parsed()
        assertEquals(now - 10, before.long(LabFields.T), "no offset yet: device time")
        assertEquals(now - 10, before.long(LabFields.DT))
        assertEquals("B", before[LabFields.DEV]?.jsonPrimitive?.content)
        assertEquals("note", before[LabFields.K]?.jsonPrimitive?.content)
        assertEquals("background", before[LabFields.APP]?.jsonPrimitive?.content)
        assertEquals("clock", clock[LabFields.K]?.jsonPrimitive?.content)
        assertEquals(1_234L, clock.long("offset"))
        assertEquals(now + 1_234, after.long(LabFields.T))
        assertEquals(mono, after.long(LabFields.MONO))
    }

    @Test
    fun emptyFieldsAreLeftOut() {
        val log = log()
        log.adv("start", "ibeacon", token = "0123abcd")
        val line = log.lines().single()
        assertFalse("null" in line, line)
        assertFalse("payload" in line, line)
    }

    @Test
    fun theRingKeepsTheNewestAndCountsTheRest() {
        val log = log(capacity = 3)
        repeat(5) { log.tick(it.toLong()) }
        assertEquals(listOf(2L, 3L, 4L), log.parsed().map { it.long("n") })
        assertEquals(5L, log.count.value)
        assertTrue("dropped 2" in log.summary())
    }

    @Test
    fun readingsMoveTheBandAndPeersAreHashed() {
        val log = log()
        log.rx("0123abcd", -50, RadioApi.COREBLUETOOTH, SightingVia.NAME, peer = "AA:BB:CC:DD:EE:FF", atMillis = now)
        log.rx("0123abcd", -52, RadioApi.COREBLUETOOTH, SightingVia.NAME, peer = "AA:BB:CC:DD:EE:FF", atMillis = now)
        val events = log.parsed()
        assertEquals(listOf("rx", "band", "rx"), events.map { it[LabFields.K]?.jsonPrimitive?.content })
        val peer = events[0]["peer"]?.jsonPrimitive?.content.orEmpty()
        assertEquals(8, peer.length)
        assertFalse("AA:BB" in log.lines().joinToString())
        assertEquals(peer, events[2]["peer"]?.jsonPrimitive?.content, "the same sender, the same hash")
        assertNotEquals(peer, log.peerId("11:22:33:44:55:66"))
        val otherLog = LabLog(isEnabled = true, { now }, { mono }, random = Random(2))
        assertNotEquals(peer, otherLog.peerId("AA:BB:CC:DD:EE:FF"), "a new salt with every log")
    }

    @Test
    fun masksKeepTheirBitsAndCandidates() {
        val log = log()
        log.mask(setOf(9, 1, 3), -70, RadioApi.ANDROID_LE, hex = "0a00", decoded = listOf("0123abcd"))
        val mask = log.parsed().single()
        assertEquals(listOf(1L, 3L, 9L), mask["bits"]?.jsonArray?.map { it.jsonPrimitive.long })
        assertEquals("0123abcd", mask["decoded"]?.jsonArray?.single()?.jsonPrimitive?.content)
    }

    @Test
    fun theSummaryShowsTheTicksGaps() {
        val log = log()
        log.tick(0)
        mono += 1_000
        now += 1_000
        log.tick(1)
        mono += 30_000
        now += 30_000
        log.tick(2)
        val summary = log.summary(listOf("model: Fake 1"))
        assertTrue(summary.startsWith("model: Fake 1"), summary)
        assertTrue("30.0 s" in summary, summary)
        assertTrue("tick: 3" in summary, summary)
    }

    @Test
    fun theExportIsNamedByLabelAndStart() {
        now = 1_790_000_000_000L // 2026-09-21T14:13:20Z
        val log = log()
        log.setLabel("droid")
        log.note("x")
        val export = log.export()
        assertEquals("hovanki-lab-droid-20260921T141320Z.jsonl", export.fileName)
        assertEquals("hovanki-lab-droid-20260921T141320Z.txt", export.summaryName)
        assertEquals(1, export.jsonl.lines().count { it.isNotBlank() })
        assertEquals("2026-09-21 14:13:20.000", LabSchema.formatUtc(now))
        assertEquals("2026-09-21 14:13:20.050", LabSchema.formatUtc(now + 50))
    }

    @Test
    fun clearStartsAnew() {
        val log = log()
        log.note("x")
        now += 5_000
        log.clear()
        assertEquals(0, log.lines().size)
        assertEquals(0L, log.count.value)
        assertEquals(now, log.startedAtMillis)
    }

    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.long
}
