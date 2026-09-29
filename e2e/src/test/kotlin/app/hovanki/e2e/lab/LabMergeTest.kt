package app.hovanki.e2e.lab

import app.hovanki.client.lab.ClockEstimate
import app.hovanki.client.lab.Gravity
import app.hovanki.client.lab.LabLog
import app.hovanki.client.lab.LabPlaces
import app.hovanki.client.lab.MotionFeatures
import app.hovanki.client.lab.Orientation
import app.hovanki.client.radio.RadioApi
import app.hovanki.client.radio.SightingVia
import app.hovanki.shared.protocol.Activity
import app.hovanki.shared.rules.OverflowArea
import app.hovanki.shared.rules.OverflowProbe
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The merge on made-up logs of an iPhone and the Mac (docs/radio-lab.md §4.5). */
class LabMergeTest {
    /** A device's lab log on a clock the test moves; [offset]: how far its clock is behind the server's. */
    private class Device(label: String, val offset: Long) {
        var server = START
        val log = LabLog(isEnabled = true, { server - offset }, { server - START }).apply {
            isRecording = true
            setLabel(label)
        }

        fun at(serverMillis: Long, write: LabLog.() -> Unit) {
            server = serverMillis
            log.write()
        }

        fun measured() =
            log.setClock(ClockEstimate(offset, rttMillis = 30, samples = 5, measuredAtMono = server - START))
    }

    private fun logs(): Pair<Device, Device> {
        val phone = Device("A", offset = 1_000)
        val mac = Device("mac", offset = 0)
        phone.at(START) {
            session("iPhone13,2", "iOS 26.0", "1.0 (1)", "abc123", null)
            tick(0)
            // Before the clock was measured: the merge puts it on the server's time anyway.
            adv("start", "hider_name", "aaaa0001")
        }
        phone.at(START + 100) { phone.measured() }
        mac.at(START) {
            session("MacBook", "Mac OS X 15", "e2e beacon-lab", "abc123", "sniff")
            mac.measured()
            adv("start", "hider_name", "cccc0001")
            tick(0)
        }
        phone.at(START + 1_000) {
            mark(
                "pocket: front pocket, walk",
                by = "scenario",
                step = 2,
                place = LabPlaces.POCKET_FRONT,
                action = "walk",
            )
            carry("in_hand")
            tick(1)
            adv("start", "overflow_probe", payload = OverflowProbe.PATTERN.sorted().joinToString(","))
        }
        for (i in 0 until 5) {
            mac.at(START + 1_200 + i * 400L) {
                rx("aaaa0001", -60 - i, RadioApi.MAC_COREBLUETOOTH, SightingVia.NAME, "PEER-1")
                tick(i + 1L)
            }
            phone.at(START + 1_300 + i * 400L) {
                rx("cccc0001", -70, RadioApi.COREBLUETOOTH, SightingVia.NAME, "PEER-2")
            }
        }
        phone.at(START + 2_000) {
            carry("in_pocket")
            motion(MotionFeatures(0.2, Gravity(0.0, -0.9, -0.3), Orientation.UPRIGHT, Activity.WALKING))
            haptic("core_haptics", "error", error = "engine stopped", group = 1)
        }
        mac.at(START + 3_500) {
            mask(
                OverflowProbe.PATTERN + 100,
                -66,
                RadioApi.MAC_COREBLUETOOTH,
                hex = OverflowArea.maskOf(OverflowProbe.PATTERN + 100).toHex(),
                peer = "P",
            )
        }
        // The phone suspended for 10 s, then ticks again.
        phone.at(START + 13_000) {
            life("did_become_active")
            tick(2)
            rx("cccc0001", -71, RadioApi.COREBLUETOOTH, SightingVia.NAME, "PEER-2")
        }
        mac.at(START + 13_000) { mark("done", by = "mac") }
        return phone to mac
    }

    private fun merged(): LabMerge {
        val (phone, mac) = logs()
        return LabMerge(
            listOf(
                "phone.jsonl" to phone.log.export().jsonl,
                "mac.jsonl" to (mac.log.export().jsonl + "not json\n"),
            ),
        )
    }

    @Test
    fun devicesAndTokensAreTold() {
        val merge = merged()
        assertEquals(listOf("A", "mac"), merge.devices.map { it.dev })
        assertEquals("iPhone13,2", merge.devices[0].model)
        assertEquals(mapOf("aaaa0001" to setOf("A"), "cccc0001" to setOf("mac")), merge.owners)
        assertEquals(listOf("mac.jsonl: 1 lines that are not lab events"), merge.problems())
    }

    @Test
    fun everyEventIsOnTheServersClock() {
        val merge = merged()
        // The phone's first events were written with its own clock, 1 s behind: they line up with the Mac's.
        val first = merge.events.filter { it.k == "session" }
        assertEquals(listOf(START, START), first.map { it.t })
        assertTrue(merge.events.zipWithNext().all { (a, b) -> a.t <= b.t })
        val timeline = merge.timeline()
        assertTrue("==== " in timeline && "MARK pocket: front pocket, walk" in timeline, timeline)
        assertTrue("rx aaaa0001 from A -60 dBm mac_corebluetooth/name" in timeline, timeline)
    }

    @Test
    fun theSummaryGoesByStretchAndDirection() {
        val summary = merged().summary()
        assertTrue("| A → mac | mac_corebluetooth/name | 5 |" in summary, summary)
        assertTrue("| mac → A | corebluetooth/name | 5 |" in summary, summary)
        // The phone's long silence shows next to the direction it heard in.
        assertTrue("did_become_active" in summary && "no ticks for" in summary, summary)
    }

    @Test
    fun thePocketsTruthMeetsTheCarryMonitor() {
        val merge = merged()
        val phone = merge.carryMatrices().getValue("A")
        val pocket = phone.getValue("in_pocket")
        assertEquals(1, pocket["in_hand"], "$phone")
        assertTrue((pocket["in_pocket"] ?: 0) >= 10, "$phone")
        val csv = merge.carryCsv().lines()
        assertTrue(csv.first().startsWith("t_utc,dev,place"))
        assertTrue(
            csv.any {
                ",A,pocket_front,walk,in_pocket,in_pocket,0.2,upright,walking," in it
            },
            csv.joinToString("\n"),
        )
    }

    @Test
    fun masksMeetWhatTheProbeSent() {
        val rows = merged().masksCsv().lines().filter { it.isNotBlank() }
        assertEquals(2, rows.size, rows.joinToString("\n"))
        val row = rows[1].split(',')
        assertEquals("5a5a0000000000000000000008000000", row[6], "the mask as it came")
        assertEquals("true", row[10], rows[1])
        assertEquals("100", row[11], "the extra bit")
        assertEquals("", row[12], "none missing")
        assertEquals("A", row[9])
    }

    @Test
    fun theFilesAreWritten() {
        val out = Files.createTempDirectory("lab-merge").toFile()
        LabCli.write(merged(), out)
        assertEquals(
            setOf("timeline.txt", "summary.md", "carry.csv", "masks.csv", "haptics.csv"),
            out.list()!!.toSet(),
        )
        assertTrue("core_haptics,error" in out.resolve("haptics.csv").readText())
        assertEquals(2, LabCli.run(listOf("merge")))
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val START = 1_790_000_000_000L
    }
}
