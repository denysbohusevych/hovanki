package app.hovanki.client.lab

import app.hovanki.client.diagnostics.Diagnostics
import app.hovanki.client.diagnostics.DiagnosticsBench
import app.hovanki.client.session.FakeLocationProvider
import app.hovanki.radar.AirFrame
import app.hovanki.radar.AirHost
import app.hovanki.radar.AirSecond
import app.hovanki.radar.AirTally
import app.hovanki.radar.Decoded
import app.hovanki.radar.ProximityRadio
import app.hovanki.radar.RadarCaps
import app.hovanki.radar.RadarCatalog
import app.hovanki.radar.RadarChannel
import app.hovanki.radar.RadarRole
import app.hovanki.radar.RadarService
import app.hovanki.radar.RadarTrace
import app.hovanki.radar.RadioApi
import app.hovanki.radar.RadioSighting
import app.hovanki.radar.SightingVia
import app.hovanki.radar.lab.HostLabAir
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.rules.OverflowArea
import app.hovanki.shared.rules.OverflowCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The radar's channels in the lab's log (docs/radio-lab.md §4.1): `adv`, `frame`, `air`, `rx` with the channel. */
class LabRadarTest {
    private var now = 1_790_000_000_000L

    private fun log() = LabLog(isEnabled = true, { now }, { 5_000L }, random = Random(1)).also { it.isRecording = true }

    private fun LabLog.parsed(): List<JsonObject> = lines().map { Json.parseToJsonElement(it).jsonObject }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun anAdvertisementSaysItsChannelAndItsLayout() {
        val log = log()
        val trace = LabRadioTrace(log)
        trace.advertise("start", "ble.service_data.scan_response", TOKEN, "adv 18 (uuid128 18) + rsp 22 (svcdata 22)")
        trace.advertise("dropped", "ble.name", TOKEN, "adv 18 (uuid128 18)", "no local name on android")
        trace.advertise("start", "ble.overflow", TOKEN, "adv 50 (uuid128 50)")

        val (start, dropped, overflow) = log.parsed()
        assertEquals("adv", start.text(LabFields.K))
        assertEquals("start", start.text("action"))
        assertEquals("hider_service_data", start.text("mode"), "the older readers know the mode")
        assertEquals("ble.service_data.scan_response", start.text("tech"))
        assertEquals(TOKEN, start.text("token"))
        assertEquals("adv 18 (uuid128 18) + rsp 22 (svcdata 22)", start.text("layout"))
        assertNull(start["error"])
        assertEquals("hider_name", dropped.text("mode"))
        assertEquals("no local name on android", dropped.text("error"))
        assertEquals("ble.overflow", overflow.text("mode"), "a channel without an older name: its id")
    }

    @Test
    fun aFrameIsWrittenWholeWithWhatTheChannelsRead() {
        val log = log()
        val trace = LabRadioTrace(log)
        val frame = AirFrame(
            atMillis = now - 40,
            rssi = -63,
            api = RadioApi.ANDROID_LE,
            peer = "AA:BB:CC:DD:EE:FF",
            name = "hv0a1b",
            serviceUuids = listOf(RadarService.UUID),
            overflowUuids = listOf(OverflowArea.uuid(3), OverflowArea.uuid(70)),
            serviceData = mapOf(RadarService.UUID to byteArrayOf(0x0a, 0x1b, 0x2c, 0x3d)),
            manufacturerData = mapOf(RadarService.APPLE_COMPANY_ID to byteArrayOf(0x02, 0x15)),
            txPower = -7,
            connectable = false,
            raw = byteArrayOf(0x02, 0x01, 0x06),
        )
        val decoded = listOf(
            "ble.service_data.scan_response" to Decoded(TOKEN, SightingVia.SERVICE_DATA),
            "ble.service_data.bare" to Decoded(TOKEN, SightingVia.SERVICE_DATA),
        )
        trace.frame(frame, decoded)

        val event = log.parsed().single()
        assertEquals("frame", event.text(LabFields.K))
        assertEquals("ble.service_data.scan_response", event.text("tech"))
        assertEquals(
            listOf("ble.service_data.scan_response", "ble.service_data.bare"),
            event["techs"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("service_data", event.text("via"))
        assertEquals(TOKEN, event.text("token"))
        assertNull(event["candidates"], "one token: no candidates")
        assertEquals("hv0a1b", event.text("name"))
        assertEquals(listOf(RadarService.UUID), event["uuids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(3, 70), event["overflow"]!!.jsonArray.map { it.jsonPrimitive.int }, "the table's bits")
        assertEquals("0a1b2c3d", event["svcdata"]!!.jsonObject[RadarService.UUID]!!.jsonPrimitive.content)
        assertEquals("0215", event["mfr"]!!.jsonObject["004c"]!!.jsonPrimitive.content)
        assertEquals(-7, event["tx"]!!.jsonPrimitive.int)
        assertEquals("false", event.text("conn"))
        assertEquals(-63, event["rssi"]!!.jsonPrimitive.int)
        assertEquals(log.peerId("AA:BB:CC:DD:EE:FF"), event.text("peer"), "hashed, as rx")
        assertEquals("android_le", event.text("api"))
        assertEquals("020106", event.text("hex"))
        assertEquals(40, event["ago"]!!.jsonPrimitive.int)
        assertFalse("AA:BB" in log.lines().single())

        // A damaged mask: the first reading and all of them.
        trace.frame(frame, listOf("ble.overflow" to Decoded(TOKEN, SightingVia.OVERFLOW_RAW, listOf(TOKEN, OTHER))))
        val mask = log.parsed().last()
        assertEquals(listOf(TOKEN, OTHER), mask["candidates"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull(mask["techs"])
    }

    @Test
    fun theAirIsASecondOfCounts() {
        val log = log()
        LabRadioTrace(log).air(AirSecond(now - 1_000, frames = 12, iBeacons = 2, masks = 1, apple = 9, setOf(70, 3)))

        val event = log.parsed().single()
        assertEquals("air", event.text(LabFields.K))
        assertEquals(12, event["frames"]!!.jsonPrimitive.int)
        assertEquals(2, event["ibeacons"]!!.jsonPrimitive.int)
        assertEquals(1, event["masks"]!!.jsonPrimitive.int)
        assertEquals(9, event["apple"]!!.jsonPrimitive.int)
        assertEquals(listOf(3, 70), event["bits"]!!.jsonArray.map { it.jsonPrimitive.int })
    }

    @Test
    fun theLabListensOnTheHostAndWritesTheChannel() = runTest {
        val host = FakeHost()
        val listening = Lab(this) { log -> HostLabAir(host, LabRadioTrace(log)) }
        listening.controller.start()
        listening.controller.setListening(true)
        runCurrent()
        val hider = AirFrame(
            now,
            -61,
            RadioApi.ANDROID_LE,
            "peer-1",
            serviceUuids = listOf(RadarService.UUID),
            serviceData = mapOf(RadarService.UUID to byteArrayOf(0x0a, 0x1b, 0x2c, 0x3d)),
        )
        val bits = OverflowCode.encode(TOKEN)
        val mask = AirFrame(now, -70, RadioApi.ANDROID_LE, "peer-2", manufacturerData = mapOf(APPLE to masked(bits)))
        host.hear(hider)
        host.hear(mask)
        runCurrent()

        val events = listening.events()
        val rx = events.filter { it.text(LabFields.K) == "rx" }
        assertEquals(listOf("ble.service_data.scan_response", "ble.overflow"), rx.map { it.text("tech") })
        assertEquals(listOf(TOKEN, TOKEN), rx.map { it.text("token") })
        assertEquals("overflow_raw", rx.last().text("via"))
        assertEquals(1, events.count { it.text(LabFields.K) == "mask" })
        // The host's tally: both frames are the radar's own, written whole.
        assertEquals(
            listOf("ble.service_data.scan_response", "ble.overflow"),
            events.filter { it.text(LabFields.K) == "frame" }.map { it.text("tech") },
        )
        assertEquals(RadarCatalog.channels.size + 1, host.channels?.size, "every channel and the Apple listener")
    }

    @Test
    fun aStepsChannelsReachTheBenchRadio() = runTest {
        val lab = Lab(this)
        lab.controller.start()
        lab.controller.setTechniques(setOf("ble.name", "no.such"))
        lab.controller.setBenchRadio(asSeeker = false, token = TOKEN)
        runCurrent()
        assertEquals(setOf("ble.name"), lab.controller.techniques.value, "an unknown id is left out")
        assertEquals(setOf("ble.name"), lab.bench.radioTechniques)

        // A radio on the bench starts again with the new channels, as the same hider with the same token.
        lab.controller.setTechniques(setOf("ble.service_data.bare"))
        runCurrent()
        assertEquals(setOf("ble.service_data.bare"), lab.bench.radioTechniques)
        assertEquals(TOKEN, lab.bench.radioToken)
        val notes = lab.events().filter { it.text(LabFields.K) == "note" }.map { it.text("text").orEmpty() }
        assertTrue("unknown techniques no.such: left out" in notes, "$notes")
        val on = "bench radio on as hider, token $TOKEN, channels ble.service_data.bare"
        assertTrue(on in notes, "$notes")

        lab.controller.stop()
        assertEquals(emptySet(), lab.controller.techniques.value, "the lab stopped: the game's again")
    }

    @Test
    fun theBenchRunsTheRadioWithTheChannelsAndWritesWhichHeard() = runTest {
        val radio = ChannelRadio()
        val log = log()
        val bench = DiagnosticsBench(
            radio,
            FakeLocationProvider(),
            Diagnostics(isEnabled = true),
            backgroundScope,
            Random(1),
            log,
            { now },
        )
        bench.startRadio(asSeeker = true, token = TOKEN, techniques = setOf("ble.ibeacon"))
        runCurrent()
        val heard = RadioSighting(OTHER, -58, now, RadioApi.ANDROID_LE, SightingVia.IBEACON, "p", "ble.ibeacon")
        radio.sightings.emit(heard)
        runCurrent()

        assertEquals(setOf("ble.ibeacon") to true, radio.ran)
        val rx = log.parsed().single { it.text(LabFields.K) == "rx" }
        assertEquals("ble.ibeacon", rx.text("tech"))
        assertEquals(OTHER, rx.text("token"))
    }

    /** A host that hears what the test says, and runs whatever channels its caller gives. */
    private class FakeHost : AirHost {
        override val caps = MutableStateFlow(RadarCaps(Platform.ANDROID, BluetoothState.ON, canReadOverflow = true))
        private val frames = MutableSharedFlow<AirFrame>(extraBufferCapacity = 16)
        var channels: List<RadarChannel>? = null

        fun hear(frame: AirFrame) = check(frames.tryEmit(frame))

        override fun run(
            channels: List<RadarChannel>,
            token: StateFlow<String?>,
            role: RadarRole,
            trace: RadarTrace,
        ): Flow<AirFrame> {
            this.channels = channels
            val tally = AirTally(trace, channels)
            return frames.onEach { tally.heard(it) }
        }
    }

    /** A radio that remembers the channels it ran with. */
    private class ChannelRadio : ProximityRadio {
        override val state = MutableStateFlow(BluetoothState.ON)
        val sightings = MutableSharedFlow<RadioSighting>(extraBufferCapacity = 4)
        var ran: Pair<Set<String>, Boolean>? = null

        override fun run(tokens: StateFlow<String?>, asSeeker: Boolean): Flow<RadioSighting> = sightings

        override fun run(tokens: StateFlow<String?>, asSeeker: Boolean, techniques: Set<String>): Flow<RadioSighting> {
            ran = techniques to asSeeker
            return sightings
        }
    }

    private fun masked(bits: Set<Int>): ByteArray = byteArrayOf(0x01) + OverflowArea.maskOf(bits)

    private companion object {
        const val TOKEN = "0a1b2c3d"
        const val OTHER = "4e5f6071"
        const val APPLE = RadarService.APPLE_COMPANY_ID
    }
}
