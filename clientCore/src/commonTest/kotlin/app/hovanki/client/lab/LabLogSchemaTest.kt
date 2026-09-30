package app.hovanki.client.lab

import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.lab.LabSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Schema 2 of the lab's log (docs/radio-lab.md §4): `seq`, `run`, the upload's batches, `step` and `net`. */
class LabLogSchemaTest {
    private var now = 1_790_000_000_000L
    private var mono = 5_000L

    private fun log(capacity: Int = LabLog.CAPACITY, seed: Int = 1) =
        LabLog(isEnabled = true, { now }, { mono }, capacity, Random(seed)).also { it.isRecording = true }

    private fun parse(line: String): JsonObject = Json.parseToJsonElement(line).jsonObject

    private fun LabLog.parsed(): List<JsonObject> = lines().map(::parse)

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content

    @Test
    fun everyEventHasASeqAndInARunTheRunsId() {
        val log = log()
        log.note("before")
        log.setRun("run-1", "00ff")
        log.note("in the run")
        log.setRun(null, null)
        log.note("after")

        val (before, inRun, after) = log.parsed()
        assertEquals(listOf(1L, 2L, 3L), log.parsed().map { it.long(LabFields.SEQ) })
        assertNull(before[LabFields.RUN])
        assertEquals("run-1", inRun.text(LabFields.RUN))
        assertNull(after[LabFields.RUN])
        assertEquals(4L, log.nextSeq)
        assertEquals(1L, log.firstKeptSeq)
    }

    @Test
    fun theSessionSaysSchema2AndInARunTheLabel() {
        val log = log()
        log.setLabel("droid")
        log.session("Pixel 8", "Android 16", "1.0 (1)", "abc", null)
        log.setRun("run-1", "00ff")
        log.session("Pixel 8", "Android 16", "1.0 (1)", "abc", "probe")

        val (outside, inRun) = log.parsed()
        assertEquals(LabSchema.VERSION, outside.getValue("schema").jsonPrimitive.int)
        assertEquals(2, LabSchema.VERSION)
        assertNull(outside["label"])
        assertEquals("droid", inRun.text("label"))
        assertEquals("run-1", inRun.text(LabFields.RUN))
    }

    @Test
    fun stepAndNetEvents() {
        val log = log()
        log.step(1, "probe", "A's overflow probe", revision = 3)
        log.net("upload", ok = false, seqFrom = 1, seqTo = 1, bytes = 120, millis = 40, error = "offline", pending = 2)
        log.net("state", ok = true)

        val (step, failed, ok) = log.parsed()
        assertEquals("step", step.text(LabFields.K))
        assertEquals(1L, step.long("index"))
        assertEquals("probe", step.text("id"))
        assertEquals("A's overflow probe", step.text("title"))
        assertEquals(3L, step.long("revision"))
        assertEquals("net", failed.text(LabFields.K))
        assertEquals("upload", failed.text("action"))
        assertFalse(failed.getValue("ok").jsonPrimitive.boolean)
        assertEquals(1L, failed.long("seq_from"))
        assertEquals(120L, failed.long("bytes"))
        assertEquals("offline", failed.text("error"))
        assertEquals(2L, failed.long("pending"))
        assertTrue(ok.getValue("ok").jsonPrimitive.boolean)
        assertNull(ok["error"], "nothing empty written")
    }

    @Test
    fun pendingIsTheOldestUnackedEventsCutAtTheLimits() {
        val log = log()
        repeat(10) {
            now += 100
            log.note("event $it")
        }
        val lines = log.lines()

        val first = assertNotNull(log.pending(afterSeq = 0, maxEvents = 4))
        assertEquals(1L, first.seqFrom)
        assertEquals(4L, first.seqTo)
        assertEquals(4, first.count)
        assertEquals(parse(lines[0]).long(LabFields.T), first.tFrom)
        assertEquals(parse(lines[3]).long(LabFields.T), first.tTo)
        assertEquals(lines.take(4).joinToString("") { "$it\n" }, first.jsonl.decodeToString())

        val next = assertNotNull(log.pending(afterSeq = 4, maxEvents = 4))
        assertEquals(5L..8L, next.seqFrom..next.seqTo)
        assertNull(log.pending(afterSeq = 10), "everything acked")

        // Bytes: the first two lines and their newlines fit, the third doesn't.
        val two = lines[0].length + lines[1].length + 2
        val cut = assertNotNull(log.pending(afterSeq = 0, maxBytes = two + 5))
        assertEquals(2, cut.count)
        assertEquals(two, cut.jsonl.size)
        // A line longer than the limit goes alone rather than never.
        assertEquals(1, assertNotNull(log.pending(afterSeq = 0, maxBytes = 10)).count)
    }

    @Test
    fun clearAndTheRingKeepTheSeqGoing() {
        val log = log(capacity = 3)
        repeat(5) { log.note("event $it") }
        assertEquals(3L, log.firstKeptSeq, "two dropped from the ring before any upload")
        assertEquals(3L, assertNotNull(log.pending(afterSeq = 0)).seqFrom)

        log.clear()
        assertNull(log.firstKeptSeq)
        assertNull(log.pending(afterSeq = 0))
        assertEquals(6L, log.nextSeq)
        log.note("after the clear")
        assertEquals(6L, log.parsed().single().long(LabFields.SEQ))
        assertEquals(6L, log.firstKeptSeq)
    }

    @Test
    fun inARunEveryDeviceHashesASenderWithTheRunsSalt() {
        val phone = log(seed = 1)
        val other = log(seed = 2)
        assertNotEquals(phone.peerId("peer-1"), other.peerId("peer-1"), "own salts")

        phone.setRun("run-1", "00ff00ff")
        other.setRun("run-1", "00FF00FF")
        assertEquals(phone.peerId("peer-1"), other.peerId("peer-1"), "the run's salt")
        assertNotEquals(phone.peerId("peer-1"), phone.peerId("peer-2"))

        val own = log(seed = 1).peerId("peer-1")
        phone.setRun(null, null)
        assertEquals(own, phone.peerId("peer-1"), "out of the run: the log's own salt again")
    }
}
