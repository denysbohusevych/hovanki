package app.hovanki.e2e.beacon

import app.hovanki.client.lab.LabLog
import app.hovanki.radar.SightingVia
import app.hovanki.shared.rules.OverflowArea
import app.hovanki.shared.rules.OverflowCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Mac in the radio lab: the helper's lines, and what they write into the lab's log (docs/radio-lab.md §6). */
class BeaconLabTest {
    @Test
    fun theHelpersLinesParse() {
        assertEquals(HelperLine.State("on"), HelperLine.parse("state on"))
        val heard = HelperLine.parse("heard 0123abcd -48 name 5F2C-AA") as HelperLine.Heard
        assertEquals(HelperLine.Heard("0123abcd", -48, "name", "5F2C-AA"), heard)
        assertEquals(SightingVia.NAME, heard.via)
        assertEquals(null, (HelperLine.parse("heard 0123abcd -48 ibeacon") as HelperLine.Heard).peer, "old helper")
        // An Android hider's `.mfr` layout (docs/adr/0017-radar-techniques-and-big-run.md §2.3).
        assertEquals(
            SightingVia.SERVICE_DATA,
            (HelperLine.parse("heard 0123abcd -50 mfr P") as HelperLine.Heard).via,
        )
        assertEquals(
            HelperLine.Raw(byteArrayOf(0x01, 0x5a, 0x00), -70, "PEER"),
            HelperLine.parse("raw 015a00 -70 PEER"),
        )
        assertEquals(
            HelperLine.Overflow(listOf("00000000-0000-0000-0000-00000000007C"), -66, "P"),
            HelperLine.parse("overflow 00000000-0000-0000-0000-00000000007C -66 P"),
        )
        assertEquals(HelperLine.Log("advertising 0123abcd"), HelperLine.parse("log advertising 0123abcd"))
        assertEquals(HelperLine.Log("raw zz -1 P"), HelperLine.parse("raw zz -1 P"))
    }

    @Test
    fun masksAndReadingsGoIntoTheLog() {
        val log = LabLog(isEnabled = true).apply {
            isRecording = true
            setLabel("mac")
        }
        val summary = AirSummary()
        val bits = OverflowCode.encode("0a1b2c3d")
        val mask = OverflowArea.maskOf(bits)
        // Another frame first (type 0x10, 2 bytes), then the overflow area.
        BeaconLabCli.onHelperLine(
            log,
            summary,
            HelperLine.Raw(byteArrayOf(0x10, 0x02, 0x00, 0x00, 0x01) + mask, -61, "P1"),
        )
        BeaconLabCli.onHelperLine(log, summary, HelperLine.Heard("89abcdef", -55, "ibeacon", "P2"))
        BeaconLabCli.onHelperLine(log, summary, HelperLine.Raw(byteArrayOf(0x10, 0x02, 0x00, 0x00), -60, "P3"))

        val events = log.lines().map { Json.parseToJsonElement(it).jsonObject }
        val kinds = events.map { it["k"]!!.jsonPrimitive.content }
        assertEquals(listOf("mask", "rx", "band"), kinds, "a frame without a mask writes nothing")
        assertTrue("0a1b2c3d" in events[0]["decoded"].toString())
        assertEquals("mac_corebluetooth", events[1]["api"]!!.jsonPrimitive.content)
        assertEquals("ibeacon", events[1]["via"]!!.jsonPrimitive.content)
        val lines = summary.flush()
        assertTrue(lines.any { "token 0a1b2c3d" in it }, "$lines")
        assertTrue(lines.any { "hears 89abcdef: -55 dBm, 1×, ibeacon" == it }, "$lines")
    }
}
