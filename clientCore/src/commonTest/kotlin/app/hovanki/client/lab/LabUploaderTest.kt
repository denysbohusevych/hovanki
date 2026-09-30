package app.hovanki.client.lab

import app.hovanki.client.network.ApiException
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ErrorCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabUploaderTest {
    private val runId = FakeLabApi().runId

    private fun TestScope.recording(): Lab = Lab(this).also { it.log.isRecording = true }

    @Test
    fun aFailedBatchIsSentAgainUntilTheServerHasIt() = runTest {
        val lab = recording()
        repeat(3) { lab.log.note("event $it") }
        val api = FakeLabApi().apply { failures = 2 }
        val uploader = LabUploader(lab.log, api, backgroundScope, intervalMillis = 1_000)
        uploader.start(runId, FakeLabApi.TOKEN)
        runCurrent()

        assertEquals(1, api.uploads.size)
        assertEquals(0L, uploader.ackedSeq.value)
        assertEquals("IllegalStateException: offline", uploader.lastError.value)
        assertEquals(4L, uploader.pending.value, "three events and the failure's own")

        advanceTimeBy(1_000)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        // The same events every time, and the failures' `net` events with them.
        assertEquals(3, api.uploads.size)
        assertTrue(api.uploads.all { it.batch.seqFrom == 1L })
        assertEquals(listOf(3L, 4L, 5L), api.uploads.map { it.batch.seqTo })
        assertEquals(5L, uploader.ackedSeq.value)
        assertNull(uploader.lastError.value)
        assertEquals(1L, uploader.pending.value, "the success's own event waits for the next batch")
        assertTrue(api.uploads.last().gzip, "gzipped on the JVM")

        val net = lab.events().filter { it[LabFields.K]?.jsonPrimitive?.content == "net" }
        assertEquals(listOf(false, false, true), net.map { it.getValue("ok").jsonPrimitive.boolean })
        assertEquals(5L, net.last().getValue("seq_to").jsonPrimitive.long)
        assertEquals("upload", net.first()["action"]?.jsonPrimitive?.content)
    }

    @Test
    fun aFullBatchSendsTheNextAtOnce() = runTest {
        val lab = recording()
        repeat(5) { lab.log.note("event $it") }
        val api = FakeLabApi()
        val uploader = LabUploader(lab.log, api, backgroundScope, intervalMillis = 60_000, maxEvents = 2)
        uploader.start(runId, FakeLabApi.TOKEN)
        runCurrent()

        assertTrue(api.uploads.size >= 3, "without waiting for the interval")
        assertTrue(api.uploads.all { it.batch.count <= 2 })
        assertEquals(api.uploads.map { it.batch.seqFrom }, api.uploads.map { it.batch.seqFrom }.sorted())
        assertTrue(uploader.ackedSeq.value >= 5L)
    }

    @Test
    fun aClosedRunStopsTheUploads() = runTest {
        val lab = recording()
        lab.log.note("event")
        val api = FakeLabApi().apply { refusal = FakeLabApi.closed() }
        val uploader = LabUploader(lab.log, api, backgroundScope, intervalMillis = 1_000)
        uploader.start(runId, FakeLabApi.TOKEN)
        runCurrent()
        advanceTimeBy(10_000)

        assertEquals(1, api.uploads.size, "never again")
        assertTrue(uploader.closed.value)
        assertEquals("409 LAB_RUN_CLOSED", uploader.lastError.value)
        assertFalse(uploader.isRunning)
        assertFalse(uploader.flush(), "a flush can't send to a closed run either")

        val unknown = ApiException(401, ApiError(ErrorCode.UNAUTHORIZED, "Who?"))
        assertTrue(LabUploader.isFinal(unknown))
        assertFalse(LabUploader.isFinal(ApiException(503, null)))
    }

    @Test
    fun aFlushSendsEverythingWrittenSoFar() = runTest {
        val lab = recording()
        val api = FakeLabApi()
        val uploader = LabUploader(lab.log, api, backgroundScope, intervalMillis = 60_000, maxEvents = 3)
        uploader.start(runId, FakeLabApi.TOKEN)
        runCurrent()
        assertEquals(0, api.uploads.size, "nothing written yet")

        repeat(7) { lab.log.note("event $it") }
        val last = lab.log.nextSeq - 1
        api.failures = 1
        uploader.stop()
        assertTrue(uploader.flush())
        assertTrue(uploader.ackedSeq.value >= last)
        assertTrue(api.ackedSeq >= last)
        assertNotNull(api.uploads.firstOrNull { it.batch.seqFrom == 1L })
    }
}
