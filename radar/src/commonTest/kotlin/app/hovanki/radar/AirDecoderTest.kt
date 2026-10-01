package app.hovanki.radar

import app.hovanki.shared.rules.OverflowArea
import app.hovanki.shared.rules.OverflowCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a host makes of the frames it hears (ADR 0017 §2.2, §4; ADR 0018 §4 B): the game's sightings, and with a
 * journal the frames of ours whole, the shadow's readings and once a second everybody else's.
 */
class AirDecoderTest {
    private val token = "0a1b2c3d"
    private val service = GameAir.SERVICE_UUID

    @Test
    fun aGameChannelsTokenIsASightingWithItsChannel() {
        val decoder = AirDecoder(RadioTrace.None)
        val frame = HeardFrame(-61, 1_000, RadioApi.ANDROID_LE, peer = "AA:BB", serviceData = mapOf(service to token))
        val sighting = decoder.decode(frame).single()
        assertEquals(
            RadioSighting(
                token,
                -61,
                1_000,
                RadioApi.ANDROID_LE,
                SightingVia.SERVICE_DATA,
                "AA:BB",
                "ble.service_data.bare",
            ),
            sighting,
        )
    }

    @Test
    fun aMaskNeverReachesTheGame() {
        val mask = maskFrame(OverflowCode.encode(token), atMillis = 0)
        val silent = RecordingTrace(listening = false)
        assertTrue(AirDecoder(silent).decode(mask).isEmpty())
        assertTrue(silent.lines.isEmpty(), "without a journal the shadow says nothing: ${silent.lines}")

        val journal = RecordingTrace(listening = true)
        assertTrue(AirDecoder(journal).decode(mask).isEmpty())
        assertEquals(listOf("frame ble.overflow", "shadow ble.overflow [$token] overflow_raw -80"), journal.lines)
    }

    @Test
    fun othersFramesAreCountedOnceASecond() {
        val journal = RecordingTrace(listening = true)
        val decoder = AirDecoder(journal)
        decoder.decode(maskFrame(setOf(96), atMillis = 0))
        decoder.decode(maskFrame(setOf(96, 97), atMillis = 300))
        decoder.decode(HeardFrame(-70, 400, RadioApi.ANDROID_LE, manufacturerData = mapOf(0x004C to "1005031c")))
        decoder.decode(HeardFrame(-70, 500, RadioApi.ANDROID_LE, serviceData = mapOf(service to token)))
        assertTrue(journal.summaries.isEmpty(), "within the second nothing yet")
        decoder.decode(HeardFrame(-70, 1_200, RadioApi.ANDROID_LE, localName = "speaker"))
        val summary = journal.summaries.single()
        assertEquals(
            AirSummary(
                0,
                1_200,
                ours = 1,
                ibeacons = 0,
                masks = 2,
                apple = 1,
                other = 0,
                mapOf(
                    96 to 2,
                    97 to 1,
                ),
            ),
            summary,
        )
        decoder.flush(5_000, force = true)
        assertEquals(1, journal.summaries.last().other)
    }

    private fun maskFrame(bits: Set<Int>, atMillis: Long): HeardFrame {
        val hex = OverflowArea.maskOf(bits).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return HeardFrame(-80, atMillis, RadioApi.ANDROID_LE, manufacturerData = mapOf(0x004C to "01$hex"))
    }

    private class RecordingTrace(private val listening: Boolean) : RadioTrace {
        val lines = ArrayList<String>()
        val summaries = ArrayList<AirSummary>()
        override val isListening: Boolean get() = listening

        override fun frame(frame: HeardFrame, tech: String) {
            lines += "frame $tech"
        }

        override fun shadow(tech: String, tokens: List<String>, frame: HeardFrame, via: SightingVia) {
            lines += "shadow $tech $tokens ${via.key} ${frame.rssi}"
        }

        override fun air(summary: AirSummary) {
            summaries += summary
        }
    }
}
