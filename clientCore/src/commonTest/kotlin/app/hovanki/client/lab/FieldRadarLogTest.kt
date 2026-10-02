package app.hovanki.client.lab

import app.hovanki.radar.AirFrame
import app.hovanki.radar.Decoded
import app.hovanki.radar.RadarCatalog
import app.hovanki.radar.RadioApi
import app.hovanki.radar.SightingVia
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.lab.LabRadarKinds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The radar in a field game's journal (docs/adr/0018-field-test-build.md §4 B) on the lab's trace: what the shadow's
 * channels read is a `shadow` reading, every one; the frames themselves are thinned as ever; the lab writes no
 * `shadow` reading of the radar.
 */
class FieldRadarLogTest {
    private var now = 1_790_000_000_000L
    private val token = "0a1b2c3d"

    private fun LabLog.parsed(): List<JsonObject> = lines().map { Json.parseToJsonElement(it).jsonObject }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    private val mask = AirFrame(atMillis = now - 30, rssi = -81, api = RadioApi.ANDROID_LE, peer = "AA:BB:CC:DD:EE:FF")
    private val overflow = listOf(
        "ble.overflow" to Decoded(token, SightingVia.OVERFLOW_RAW, listOf(token, "0a1b2c3e")),
    )
    private val ours = AirFrame(atMillis = now - 10, rssi = -60, api = RadioApi.ANDROID_LE, peer = "11:22:33:44:55:66")
    private val scanResponse = listOf("ble.service_data.scan_response" to Decoded(token, SightingVia.SERVICE_DATA))

    @Test
    fun theShadowsReadingsAreAllWrittenTheFramesThinned() {
        val log = LabLog(isEnabled = false, { now }, { 0L })
        log.startField("run1", "00ff", "player-1", FieldThinning())
        val trace = LabRadioTrace(log)

        repeat(3) {
            trace.frame(mask, overflow)
            trace.frame(ours, scanResponse)
            now += 1_000
        }

        val events = log.parsed()
        val shadows = events.filter { it.text(LabFields.K) == LabRadarKinds.SHADOW }
        assertEquals(3, shadows.size, "every reading of the shadow")
        val shadow = shadows.first()
        assertEquals("ble.overflow", shadow.text("tech"))
        assertEquals(token, shadow.text("token"))
        assertEquals(2, shadow["tokens"]?.jsonArray?.size)
        assertEquals("overflow_raw", shadow.text("via"))
        assertEquals(-81, shadow.text("rssi")?.toInt())
        assertEquals(8, shadow.text("peer")?.length)
        assertTrue(events.none { "AA:BB" in it.toString() }, "the sender's id hashed")
        val frames = events.filter { it.text(LabFields.K) == LabRadarKinds.FRAME }
        assertEquals(2, frames.size, "one frame a peer and window")
        assertEquals(setOf(RadarCatalog.fieldShadow.single().id), LabLog.shadowTechs)
    }

    @Test
    fun theLabWritesFramesOnly() {
        val log = LabLog(isEnabled = true, { now }, { 0L }).also { it.isRecording = true }
        LabRadioTrace(log).frame(mask, overflow)
        assertEquals(listOf(LabRadarKinds.FRAME), log.parsed().map { it.text(LabFields.K) })
    }
}
