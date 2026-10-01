package app.hovanki.client.lab

import app.hovanki.radar.AirPlatform
import app.hovanki.radar.AirRole
import app.hovanki.radar.AirSummary
import app.hovanki.radar.GameAir
import app.hovanki.radar.HeardFrame
import app.hovanki.radar.RadarCatalog
import app.hovanki.radar.RadioApi
import app.hovanki.radar.RadioOptions
import app.hovanki.radar.SightingVia
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.lab.LabRadarKinds
import app.hovanki.shared.rules.OverflowArea
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The radar's kinds in the journal (docs/adr/0017-radar-techniques-and-big-run.md §4, ADR 0018 §4 B): the
 * advertisement's layout and bytes, frames of ours whole, everybody else's once a second, the shadow's readings and
 * the iBeacon region; the radio hears the log's switch through [LabRadioTrace.isListening].
 */
class LabRadarEventsTest {
    private val now = 1_790_000_000_000L
    private val token = "0a1b2c3d"

    private fun log() = LabLog(isEnabled = true, { now }, { 0L }, random = Random(1)).also { it.isRecording = true }

    private fun LabLog.parsed(): List<JsonObject> = lines().map { Json.parseToJsonElement(it).jsonObject }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun theTraceListensWhileTheLogRecords() {
        val log = LabLog(isEnabled = true, { now }, { 0L })
        val trace = LabRadioTrace(log)
        assertFalse(trace.isListening)
        log.isRecording = true
        assertTrue(trace.isListening)
        val release = LabLog.Off
        release.isRecording = true
        assertFalse(LabRadioTrace(release).isListening, "a release build never listens")
    }

    @Test
    fun theAdvertisementSaysItsLayoutAndBytes() {
        val log = log()
        val advert = RadarCatalog.advert(token, AirRole.HIDER, AirPlatform.ANDROID, RadioOptions(2), shadow = true)
        LabRadioTrace(log).advertise("start", "hider_service_data", token, report = advert.report())
        val adv = log.parsed().single()
        assertEquals(LabRadarKinds.ADV, adv.text(LabFields.K))
        assertEquals("ble.service_data.mfr", adv.text("tech"))
        assertEquals("mfr", adv.text("layout"))
        assertEquals(26, adv["bytes"]?.jsonPrimitive?.int)
        assertEquals(31, adv["limit"]?.jsonPrimitive?.int)
        assertEquals("mfr 26 = 26/31", adv.text("fields"))
        assertNull(adv["scan_rsp"])
        assertNull(adv["dropped"])
    }

    @Test
    fun aFrameOfOursIsWrittenWholeAndItsSenderHashed() {
        val log = log()
        val frame = HeardFrame(
            rssi = -63,
            atMillis = now - 40,
            api = RadioApi.ANDROID_LE,
            peer = "AA:BB:CC:DD:EE:FF",
            serviceUuids = listOf(GameAir.SERVICE_UUID),
            serviceData = mapOf(GameAir.SERVICE_UUID to token),
            rawHex = "1107",
            connectable = false,
        )
        LabRadioTrace(log).frame(frame, "ble.service_data.scan_response")
        val written = log.parsed().single()
        assertEquals(LabRadarKinds.FRAME, written.text(LabFields.K))
        assertEquals("ble.service_data.scan_response", written.text("tech"))
        assertEquals(40L, written["ago"]?.jsonPrimitive?.long)
        assertEquals(token, written["svc"]?.jsonObject?.get(GameAir.SERVICE_UUID)?.jsonPrimitive?.content)
        assertEquals("1107", written.text("hex"))
        assertEquals(8, written.text("peer")?.length)
        assertFalse("AA:BB" in log.lines().single())
    }

    @Test
    fun theShadowAndTheAirAndTheRegion() {
        val log = log()
        val trace = LabRadioTrace(log)
        val mask = HeardFrame(-80, now, RadioApi.COREBLUETOOTH, overflowUuids = listOf(OverflowArea.uuid(3)))
        trace.shadow("ble.overflow", listOf(token, "0a1b2c3e"), mask, SightingVia.OVERFLOW_UUIDS)
        trace.air(
            AirSummary(now - 1_000, 1_000, ours = 4, ibeacons = 1, masks = 2, apple = 7, other = 0, mapOf(96 to 2)),
        )
        trace.region("enter", "inside")
        val (shadow, air, region) = log.parsed()
        assertEquals("ble.overflow", shadow.text("tech"))
        assertEquals(token, shadow.text("token"))
        assertEquals(2, shadow["tokens"]?.jsonArray?.size)
        assertEquals("overflow_uuids", shadow.text("via"))
        assertEquals(LabRadarKinds.AIR, air.text(LabFields.K))
        assertEquals(2, air["bits"]?.jsonObject?.get("96")?.jsonPrimitive?.int)
        assertEquals(7, air["apple"]?.jsonPrimitive?.int)
        assertEquals("ble.ibeacon.region", region.text("tech"))
        assertEquals("enter", region.text("event"))
        assertEquals("inside", region.text("state"))
    }

    @Test
    fun aReadingSaysItsChannel() {
        val log = log()
        log.rx(token, -60, RadioApi.ANDROID_LE, SightingVia.MANUFACTURER_DATA, tech = "ble.service_data.mfr")
        val rx = log.parsed().first()
        assertEquals("ble.service_data.mfr", rx.text("tech"))
        assertEquals("manufacturer_data", rx.text("via"))
    }
}
