package app.hovanki.server.lab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The live view of a run: the devices' last state and who heard whom, from the events uploaded so far. */
class LabLiveTest {
    private val live = LabLive(LabProperties(liveWindow = Duration.ofSeconds(30)))
    private val now = 1_800_000_000_000L
    private val a = device("dev-a", "A", "aaaa0001")
    private val b = device("dev-b", "B", "bbbb0002")
    private val droid = device("dev-d", "droid", "dddd0003")

    @Test
    fun devicesShowWhatTheySaidLast() {
        live.accept(
            "run",
            a.id,
            listOf(
                event("""{"t":${now - 5_000},"k":"clock","app":"active","offset":-42,"seq":1}"""),
                event("""{"t":${now - 4_000},"k":"bt","app":"active","state":"on","seq":2}"""),
                event("""{"t":${now - 3_000},"k":"battery","app":"background","level":0.8,"seq":3}"""),
                event("""{"t":${now - 2_000},"k":"step","app":"background","index":1,"id":"probe","seq":4}"""),
            ),
            now,
        )

        val view = live.view("run", listOf(a, b), now)

        assertEquals(now, view.atMillis)
        val shown = view.devices.first { it.label == "A" }
        assertEquals(now - 2_000, shown.lastEventAtMillis)
        assertEquals(-42L, shown.clockOffsetMillis)
        assertEquals("background", shown.appState)
        assertEquals("on", shown.bluetooth)
        assertEquals(0.8, shown.batteryLevel)
        assertEquals(1, shown.stepIndex)
        // A device that sent nothing yet is listed without anything.
        val silent = view.devices.first { it.label == "B" }
        assertNull(silent.lastEventAtMillis)
        assertEquals(listOf("A", "B"), view.devices.map { it.label })
    }

    @Test
    fun pairsByTheSendersRadarTokens() {
        // B hears A five times and droid once in the last 10 s; A heard B 20 s ago only; somebody unknown and a
        // reading without a token.
        val heardByB = (1..5).map { rx(now - it * 1_000L, "aaaa0001", -60 - it, it) } +
            rx(now - 2_000, "dddd0003", -80, 6, via = "service_data") +
            rx(now - 3_000, "ffff9999", -90, 7) +
            rx(now - 4_000, null, -95, 8)
        live.accept("run", b.id, heardByB, now)
        live.accept("run", a.id, listOf(rx(now - 20_000, "bbbb0002", -70, 1)), now)
        // Older than the live window: dropped.
        live.accept("run", droid.id, listOf(rx(now - 40_000, "aaaa0001", -50, 1)), now)

        val pairs = live.view("run", listOf(a, b, droid), now).pairs

        assertEquals(
            listOf(
                // Somebody unknown is never named by the token: it may be a real game's.
                Triple("?", "B", "android_le/service_data"),
                Triple("A", "B", "android_le/service_data"),
                Triple("B", "A", "android_le/service_data"),
                Triple("droid", "B", "android_le/service_data"),
            ).sortedWith(compareBy({ it.first }, { it.second }, { it.third })),
            pairs.map { Triple(it.from, it.to, it.channel) },
        )
        val aToB = pairs.first { it.from == "A" && it.to == "B" }
        assertEquals(5, aToB.heardInLast10s)
        assertEquals(-63, aToB.medianRssi)
        // Heard in the window, not in the last 10 s.
        val bToA = pairs.first { it.from == "B" }
        assertEquals(0, bToA.heardInLast10s)
        assertNull(bToA.medianRssi)

        // As time goes on, the readings leave the window.
        val later = live.view("run", listOf(a, b, droid), now + 60_000)
        assertEquals(emptyList(), later.pairs)
    }

    @Test
    fun whateverThePhonesSendTheViewStaysSmall() {
        // A clock far ahead: its readings would never leave the window, so they are not kept.
        live.accept("run", a.id, listOf(rx(now + 10 * 86_400_000L, "bbbb0002", -60, 1)), now)
        assertEquals(emptyList(), live.view("run", listOf(a, b), now).pairs)
        assertNull(live.view("run", listOf(a, b), now).devices.first().lastEventAtMillis)

        // A flood: the newest readings, so many at most.
        val flood = (1..LabLive.MAX_READINGS + 10).map { rx(now - 1_000, "bbbb0002", -60, it) }
        live.accept("run", a.id, flood, now)
        assertEquals(LabLive.MAX_READINGS, live.view("run", listOf(a, b), now).pairs.single().heardInLast10s)
    }

    @Test
    fun aSummaryCountsAsItsReadings() {
        // A second of 9 readings (median -70) and one of a single reading at -50: ten heard, the median of all ten.
        val summaries = listOf(
            event(
                """{"t":${now - 2_000},"k":"rx","app":"active","token":"aaaa0001","rssi":-70,"n":9,"max":-61,""" +
                    """"api":"android_le","via":"service_data","seq":1}""",
            ),
            event(
                """{"t":${now - 1_000},"k":"rx","app":"active","token":"aaaa0001","rssi":-50,"n":1,"max":-50,""" +
                    """"api":"android_le","via":"service_data","seq":2}""",
            ),
        )
        live.accept("run", b.id, summaries, now)

        val pair = live.view("run", listOf(a, b), now).pairs.single()
        assertEquals(10, pair.heardInLast10s)
        assertEquals(-70, pair.medianRssi)
    }

    @Test
    fun aGamesLogShowsItsDevicesWithoutPairs() {
        // A game's senders are its rotating tokens, which no device of the run has: nothing to name them by.
        live.accept(
            "game",
            a.id,
            listOf(rx(now - 1_000, "a1b2c3d4", -60, 1), event("""{"t":${now - 500},"k":"bt","state":"on","seq":2}""")),
            now,
            pairs = false,
        )

        val view = live.view("game", listOf(a, b), now)
        assertEquals(emptyList(), view.pairs)
        assertEquals("on", view.devices.first { it.label == "A" }.bluetooth)
        assertEquals(now - 500, view.devices.first { it.label == "A" }.lastEventAtMillis)
    }

    @Test
    fun aRunNothingCameFromIsForgotten() {
        live.accept("run", a.id, listOf(rx(now - 1_000, "bbbb0002", -60, 1)), now)
        live.accept("other", b.id, listOf(rx(now - 1_000, "aaaa0001", -60, 1)), now + LabLive.IDLE_MILLIS)
        assertEquals(2, live.size)
        live.view("other", listOf(b), now + LabLive.IDLE_MILLIS + 1)
        assertEquals(1, live.size)
        assertEquals(emptyList(), live.view("run", listOf(a, b), now + LabLive.IDLE_MILLIS + 1).pairs)
    }

    @Test
    fun aDroppedRunStartsOver() {
        live.accept("run", a.id, listOf(rx(now - 1_000, "bbbb0002", -60, 1)), now)
        live.drop("run")
        val view = live.view("run", listOf(a, b), now)
        assertEquals(emptyList(), view.pairs)
        assertNull(view.devices.first().lastEventAtMillis)
    }

    private fun device(id: String, label: String, radarToken: String) =
        LabDeviceRecord(id = id, runId = "run", label = label, radarToken = radarToken, joinedAt = Instant.EPOCH)

    private fun event(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun rx(t: Long, token: String?, rssi: Int, seq: Int, via: String = "service_data"): JsonObject {
        val tokenJson = token?.let { "\"$it\"" } ?: "null"
        return event(
            """{"t":$t,"k":"rx","app":"active","token":$tokenJson,"rssi":$rssi,""" +
                """"api":"android_le","via":"$via","seq":$seq}""",
        )
    }
}
