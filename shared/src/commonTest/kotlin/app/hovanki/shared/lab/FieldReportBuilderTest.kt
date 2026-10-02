package app.hovanki.shared.lab

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GeoPoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The field report of a made-up game (docs/adr/0018-field-test-build.md §6): three phones and the server, their logs
 * written by hand in schema 2, and every section, every detector, the aliases and the exports read back.
 */
class FieldReportBuilderTest {
    /** One device's log as the phone (or the server) writes it. */
    private class Log(val label: String, val offset: Long = 0) {
        private val written = ArrayList<Pair<Long, String>>()
        private var seq = 0L

        /** In the order of time, as a phone writes them (the test writes some later). */
        val lines: List<String> get() = written.sortedBy { it.first }.map { it.second }

        fun event(t: Long, k: String, app: String = "active", vararg fields: Pair<String, Any?>) {
            val json = buildJsonObject {
                put(LabFields.T, JsonPrimitive(t))
                put(LabFields.DT, JsonPrimitive(t - offset))
                put(LabFields.MONO, JsonPrimitive(t - T0))
                put(LabFields.DEV, JsonPrimitive(label))
                put(LabFields.K, JsonPrimitive(k))
                put(LabFields.APP, JsonPrimitive(app))
                put(LabFields.RUN, JsonPrimitive(RUN))
                put(LabFields.SEQ, JsonPrimitive(seq++))
                for ((key, value) in fields) {
                    value ?: continue
                    put(key, json(value))
                }
            }
            written += t to json.toString()
        }

        private fun json(value: Any): JsonElement = when (value) {
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Collection<*> -> JsonArray(value.map { json(it!!) })
            else -> JsonPrimitive(value.toString())
        }

        fun fix(t: Long, where: GeoPoint, acc: Double = 5.0, app: String = "active") = event(
            t,
            FieldKinds.GPS,
            app,
            GpsFields.LAT to where.lat,
            GpsFields.LON to where.lon,
            GpsFields.ACC to acc,
            GpsFields.AGE to 200,
        )

        fun rx(
            t: Long,
            token: String,
            rssi: Int,
            tech: String = "ble.service_data.scan_response",
            app: String = "active",
        ) = event(
            t,
            FieldKinds.RX,
            app,
            RxFields.TOKEN to token,
            RxFields.API to "android",
            RxFields.VIA to "service_data",
            RxFields.TECH to tech,
            RxFields.COUNT to 3,
            RxFields.RSSI to rssi,
            RxFields.MAX to rssi + 2,
        )

        fun sync(t: Long, ok: Boolean, millis: Long? = null, transport: String = SyncFields.POLL) = event(
            t,
            FieldKinds.SYNC,
            "active",
            SyncFields.TRANSPORT to transport,
            SyncFields.OK to ok,
            SyncFields.MILLIS to millis,
            SyncFields.BYTES to if (ok) 2_000 else null,
            SyncFields.ERROR to if (ok) null else "timeout",
        )
    }

    private val a = Log(A_ID, offset = 300)
    private val b = Log(B_ID)
    private val c = Log(C_ID)
    private val server = Log(FieldKinds.SERVER_DEVICE)

    private fun phone(log: Log, model: String, os: String, token: String, layout: String?) {
        log.event(
            T0 - 60_000,
            FieldKinds.SESSION,
            "active",
            "schema" to 2,
            "model" to model,
            "os" to os,
            "build" to "1.0",
        )
        log.event(T0 - 59_000, "clock", "active", "offset" to log.offset, "rtt" to 40)
        log.event(
            T0 + 60_000,
            LabRadarKinds.ADV,
            "active",
            "action" to "start",
            "mode" to if (layout == null) "hider_name" else "hider_service_data",
            "tech" to if (layout == null) "ble.name" else "ble.service_data.$layout",
            "token" to token,
        )
    }

    /**
     * The game: lobby, hiding at +1 min, seeking at +2 min, finished at +30 min. A (Android) and B (iPhone) stay 3 m
     * apart and hear each other; C (Android) is 300 m away, jumps 600 m, then is silent with the app on screen; B is
     * silent in the background. Claims: A on C refused far away (fine), B on A refused at 3 m (an anomaly), A on B taken
     * and confirmed though the proximity rule in the shadow would have refused it.
     */
    private fun game(): List<Log> {
        phone(a, "Pixel 8", "Android 16", "aaaa0001", "scan_response")
        phone(b, "iPhone15,2", "iOS 26.0", "bbbb0001", null)
        phone(c, "Galaxy S24", "Android 15", "cccc0001", "bare")
        server.event(T0 - 60_000, FieldKinds.SESSION, "active", "schema" to 2, "model" to "server")
        server.event(T0 - 60_000, "clock", "active", "offset" to 0)
        server.event(
            T0 + 60_000,
            ServerKinds.PHASE,
            "active",
            ServerFields.PHASE to "HIDING",
            ServerFields.FROM to "LOBBY",
        )
        server.event(
            T0 + 120_000,
            ServerKinds.PHASE,
            "active",
            ServerFields.PHASE to "SEEKING",
            ServerFields.FROM to "HIDING",
        )

        // The lobby's touch: both pressed «We touched».
        a.event(
            T0 + 30_000,
            FieldKinds.MARK,
            "active",
            "label" to TouchDetector.LABEL_PREFIX + RunStep.pairKey(A_ID, B_ID),
            "by" to "user",
            "action" to TouchDetector.TOUCH_ACTION,
        )
        b.event(
            T0 + 31_000,
            FieldKinds.MARK,
            "active",
            "label" to TouchDetector.LABEL_PREFIX + RunStep.pairKey(B_ID, A_ID),
            "by" to "user",
            "action" to TouchDetector.TOUCH_ACTION,
        )
        a.event(T0, FieldKinds.BATTERY, "active", "level" to 0.9)
        a.event(T0 + 30 * MINUTE, FieldKinds.BATTERY, "active", "level" to 0.8)
        a.event(T0, FieldKinds.PERM, "active", PermFields.LOCATION to "always", PermFields.PRECISE to "on")
        a.event(T0 + MINUTE, "carry", "active", "state" to "in_hand")
        b.event(T0 + MINUTE, "carry", "active", "state" to "in_pocket")

        var t = T0 + 60_000
        while (t < T0 + 30 * MINUTE) {
            val minute = (t - T0) / MINUTE
            a.fix(t, ORIGIN)
            a.event(t, FieldKinds.TICK, "active", "n" to t)
            if (t % 5_000L == 0L) a.sync(t, ok = true, millis = 80 + (t / 1000) % 40)
            // B: silent in the background from +21 to +25 min.
            if (minute !in 21L until 25L) {
                val app = if (minute == 20L && (t - T0) % MINUTE >= 58_000) "background" else "active"
                b.fix(t, ORIGIN.moveBy(eastMeters = 3.0, northMeters = 0.0), app = app)
                b.event(t, FieldKinds.TICK, app, "n" to t)
                a.rx(t, "bbbb0001", -60)
                b.rx(t, "aaaa0001", -62, app = app)
            }
            // C: 300 m away, then 900 m (a jump at +13 min), silent with the app on screen from +16 to +20 min.
            if (minute !in 16L until 20L) {
                val east = if (minute >= 13) 900.0 else 300.0
                c.fix(t, ORIGIN.moveBy(eastMeters = east, northMeters = 0.0))
                c.event(t, FieldKinds.TICK, "active", "n" to t)
            }
            if (t % 10_000L == 0L) {
                server.event(
                    t,
                    FieldKinds.SRV,
                    "active",
                    SrvFields.SYNC_P50 to 20,
                    SrvFields.SYNC_P95 to if (t == T0 + 8 * MINUTE) 1_500 else 50,
                    SrvFields.ERRORS_5XX to if (t == T0 + 9 * MINUTE) 2 else 0,
                    SrvFields.HEAP_MB to 200,
                    SrvFields.CPU to 0.1,
                    SrvFields.SYNCS to 30,
                )
            }
            t += 2_000
        }
        // B's syncs fail for 30 s; one of A's takes 25 s.
        for (i in 0..15) b.sync(T0 + 12 * MINUTE + i * 2_000L, ok = false)
        b.sync(T0 + 12 * MINUTE + 32_000, ok = true, millis = 90, transport = SyncFields.SOCKET)
        a.sync(T0 + 14 * MINUTE + 1, ok = true, millis = 25_000)
        c.event(
            T0 + 15 * MINUTE,
            FieldKinds.ERR,
            "active",
            ErrFields.CLASS to "IllegalStateException",
            ErrFields.WHERE to "radio",
            ErrFields.MESSAGE to "at 52.370216, 4.895168",
            ErrFields.SENTRY_ID to "abc",
        )
        b.event(
            T0 + 11 * MINUTE,
            FieldKinds.MARK,
            "active",
            MarkFields.BY to MarkFields.PLAYER,
            MarkFields.TEXT to "radar silent",
        )
        a.event(
            T0 + 30 * MINUTE + 10_000,
            FieldKinds.SURVEY,
            "active",
            SurveyFields.RATING to 4,
            SurveyFields.BROKEN to listOf("radar"),
            SurveyFields.CARRY to "hand",
        )

        // The server's game.
        server.event(
            T0 + 5 * MINUTE,
            ServerKinds.CLAIM,
            "active",
            ServerFields.SEEKER to A_ID,
            ServerFields.HIDER to C_ID,
            ServerFields.OUTCOME to "TOO_FAR",
            ServerFields.DISTANCE to 290.0,
        )
        server.event(
            T0 + 7 * MINUTE,
            ServerKinds.CLAIM,
            "active",
            ServerFields.SEEKER to B_ID,
            ServerFields.HIDER to A_ID,
            ServerFields.OUTCOME to "TOO_FAR",
            ServerFields.DISTANCE to 31.0,
        )
        server.event(
            T0 + 10 * MINUTE, ServerKinds.CLAIM, "active", ServerFields.CATCH to "c1",
            ServerFields.SEEKER to A_ID, ServerFields.HIDER to B_ID, ServerFields.OUTCOME to "open",
            ServerFields.DISTANCE to 1.0, ServerFields.SHADOW_ACCEPT to false, ServerFields.BURNING_SECONDS to 0,
        )
        server.event(
            T0 + 10 * MINUTE + 30_000,
            ServerKinds.CATCH,
            "active",
            ServerFields.CATCH to "c1",
            ServerFields.SEEKER to A_ID,
            ServerFields.HIDER to B_ID,
            ServerFields.OUTCOME to "confirmed",
            ServerFields.REASON to "code",
        )
        server.event(
            T0 + 6 * MINUTE,
            ServerKinds.FIXES,
            "active",
            ServerFields.PLAYER to A_ID,
            ServerFields.ACCEPTED to 10,
            "refused_IMPLAUSIBLE" to 1,
        )
        server.event(
            T0 + 6 * MINUTE,
            ServerKinds.BAND,
            "active",
            ServerFields.OBSERVER to A_ID,
            ServerFields.HEARD to B_ID,
            ServerFields.BAND to "WARM",
            ServerFields.FROM to "NONE",
            ServerFields.SHADOW_BAND to "COLD",
        )
        server.event(
            T0 + 11 * MINUTE + 5_000,
            ServerKinds.REVEAL,
            "active",
            ServerFields.PLAYER to C_ID,
            ServerFields.EVENT to "start",
            ServerFields.REASON to "GLOW",
        )
        server.event(
            T0 + 30 * MINUTE,
            ServerKinds.PHASE,
            "active",
            ServerFields.PHASE to "FINISHED",
            ServerFields.FROM to "SEEKING",
        )
        return listOf(a, b, c, server)
    }

    private val devices = listOf(
        FieldReportDevice(A_ID, "d-a"),
        FieldReportDevice(B_ID, "d-b"),
        FieldReportDevice(C_ID, "d-c"),
        FieldReportDevice(FieldKinds.SERVER_DEVICE, "d-s"),
    )

    /** The report and the digest's lines, computed in windows of [windowMillis]. */
    private fun compute(
        logs: List<Log>,
        windowMillis: Long = FieldReportBuilder.WINDOW_MILLIS,
    ): Pair<FieldReport, List<String>> {
        val digest = ArrayList<String>()
        val builder = FieldReportBuilder(RUN, "game-1", devices, digest = { digest += it })
        val stream = FieldReportStream(builder, windowMillis)
        for ((log, device) in logs.zip(devices)) stream.add(device.deviceId, device.label, log.lines.asSequence())
        stream.finish()
        return builder.report(NOW, final = true) to listOf(builder.digestHeader()) + digest
    }

    @Test
    fun theSummaryCountsThePlayersTheirPhonesAndTheRound() {
        val (report, _) = compute(game())
        val summary = report.summary
        assertEquals(3, summary.players)
        assertEquals(3, summary.devices)
        assertEquals(listOf("P1", "P2", "P3"), report.players.map { it.alias })
        assertEquals(listOf(A_ID, B_ID, C_ID), report.players.map { it.label }, "the ids stay for the raw logs")
        assertEquals(T0 + 60_000, summary.roundStartMillis)
        assertEquals(T0 + 30 * MINUTE, summary.roundEndMillis)
        assertEquals(29 * 60L, summary.roundSeconds)
        assertEquals(1, summary.errors)
        assertEquals(1, summary.sentryEvents)
        assertEquals(listOf(4), summary.ratings)
        assertEquals(listOf(FieldReportCount("radar", 1)), summary.broken)
        assertTrue(summary.models.any { it.name == "iPhone15,2" })
        assertTrue(report.final)
    }

    @Test
    fun theTimelineHasTheGameByAlias() {
        val (report, _) = compute(game())
        val kinds = report.timeline.map { it.kind }
        assertTrue(ServerKinds.PHASE in kinds && ServerKinds.CLAIM in kinds && ServerKinds.CATCH in kinds, "$kinds")
        assertTrue(FieldKinds.MARK in kinds && ServerKinds.REVEAL in kinds)
        assertTrue(FieldAnomalies.SERVER_SLOW in kinds && FieldAnomalies.SERVER_ERRORS in kinds, "srv's bad moments")
        val catch = report.timeline.single { it.kind == ServerKinds.CATCH }
        assertEquals("P1 → P2: confirmed (code)", catch.text)
        val mark = report.marks.single()
        assertEquals("P2", mark.player)
        assertEquals("radar silent", mark.text)
        assertTrue(mark.around.any { "reveal" in it && "P3" in it }, "what happened around the mark: ${mark.around}")
    }

    @Test
    fun everyPlayerHasTheirGpsSyncsBatteryAndRadio() {
        val (report, _) = compute(game())
        val (pa, pb, pc) = report.players
        assertEquals(5.0, pa.gps.accP50)
        assertEquals(10, pa.gps.serverAccepted)
        assertEquals(mapOf("IMPLAUSIBLE" to 1), pa.gps.serverRefused)
        assertEquals("android", pa.platform)
        assertEquals("ios", pb.platform)
        assertEquals(20.0, pa.battery.percentPerHour)
        assertEquals(mapOf(PermFields.LOCATION to "always", PermFields.PRECISE to "on"), pa.permissions)
        assertNotNull(pa.sync.p50)
        assertTrue(pa.sync.p95!! < 1_000, "one slow sync of hundreds")
        assertEquals(2_000L, pa.sync.bytesP50)
        assertEquals(1, pa.sync.stalls)
        assertEquals(1, pb.sync.stalls)
        assertEquals(16, pb.sync.errors)
        assertTrue(pb.sync.socketPercent!! > 0)
        assertTrue(pb.backgroundSeconds >= 4 * 60, "B was in the background for 4 minutes: ${pb.backgroundSeconds}")
        assertEquals(1, pc.gps.jumps)
        assertEquals(1, pc.gps.gaps, "C's 4 silent minutes")
        val heard = pa.heard.single()
        assertEquals("P2", heard.peer)
        assertTrue(heard.seconds > 100)
        assertEquals(listOf("ble.service_data.scan_response"), heard.channels)
        assertEquals(1, pb.marks)
        assertEquals(4, pa.survey?.rating)
        assertEquals(1, pc.reveals)
        assertNull(pa.droppedAtMillis)
    }

    @Test
    fun theRadarHasRssiByDistanceZeroPointsCoverageAndTheShadowRules() {
        val (report, _) = compute(game())
        val radar = report.radar
        val near = radar.rssi.single { it.models == "iPhone15,2 → Pixel 8" }
        assertEquals("0-5", near.bucket)
        assertEquals(-60, near.median)
        assertEquals("in_pocket/in_hand", near.carry)
        assertTrue(radar.pairSeconds.any { it.name == "ios → android" && it.count > 100 })
        val catch = radar.zeroPoints.single { it.kind == FieldReportBuilder.CATCH_ZERO }
        assertEquals("P1" to "P2", catch.a to catch.b)
        assertEquals(-60 + 2, catch.rssiBToA, "the loudest of B heard by A")
        assertEquals(3.0, catch.gpsMeters)
        assertTrue(radar.zeroPoints.any { it.kind == FieldReportBuilder.TOUCH_ZERO }, "the lobby's touch")
        val androidToIos = radar.coverage.single { it.sender == "android/scan_response" && it.listener == "ios" }
        assertTrue(androidToIos.nearSeconds > 1_000, "$androidToIos")
        assertTrue(androidToIos.heardSeconds >= androidToIos.nearSeconds * 0.95, "$androidToIos")
        assertTrue(radar.coverage.none { it.sender == "android/bare" }, "C was never near anybody")
        val rules = radar.shadowRules
        assertEquals(3, rules.claims)
        assertEquals(1, rules.catches)
        assertEquals(1, rules.catchesShadowWouldRefuse)
        assertEquals(1, rules.bandShifted)
        assertEquals(listOf(FieldReportCount("WARM→COLD", 1)), rules.shifts)
        val techniques = assertNotNull(radar.techniques)
        assertTrue(techniques.computed)
        assertEquals(1, techniques.missedTouches, "the presses without knocks: one touch the detector missed")
    }

    @Test
    fun theDetectorsFindEveryAnomalyOfTheGame() {
        val (report, _) = compute(game())
        fun found(kind: String, player: String?) = report.anomalies.filter { it.kind == kind && it.player == player }
        assertEquals(1, found(FieldAnomalies.JOURNAL_GAP, "P3").size, "C silent on screen")
        assertTrue(found(FieldAnomalies.JOURNAL_GAP, "P2").isEmpty(), "B silent in the background is fine")
        assertEquals(1, found(FieldAnomalies.SYNC_STALL, "P1").size)
        assertEquals(1, found(FieldAnomalies.SYNC_STALL, "P2").size)
        assertEquals(1, found(FieldAnomalies.GPS_JUMP, "P3").size)
        assertEquals(1, found(FieldAnomalies.REFUSED_NEAR, "P2").size, "B's claim refused at 3 m")
        assertTrue(found(FieldAnomalies.REFUSED_NEAR, "P1").isEmpty(), "A's claim on C at 300 m was right")
        val error = found(FieldAnomalies.ERROR, "P3").single()
        assertFalse("52.37" in error.detail, "the error's text is scrubbed: ${error.detail}")
        assertEquals(1, found(FieldAnomalies.SERVER_ERRORS, null).size)
        assertEquals(1, found(FieldAnomalies.SERVER_SLOW, null).size)
        assertEquals(report.anomalies.size, report.anomalyCounts.values.sum())
    }

    @Test
    fun theDetectorsOneByOne() {
        assertNotNull(FieldAnomalies.journalGap("P1", 0, 61_000, "active", null))
        assertNull(FieldAnomalies.journalGap("P1", 0, 59_000, "active", null))
        assertNull(FieldAnomalies.journalGap("P1", 0, 600_000, "background", null))
        assertNull(FieldAnomalies.journalGap("P1", 0, 600_000, "active", "did_enter_background"))
        assertNotNull(FieldAnomalies.syncStall("P1", 30_000, waitedMillis = 21_000))
        assertNull(FieldAnomalies.syncStall("P1", 30_000, waitedMillis = 19_000))
        assertNotNull(FieldAnomalies.syncStall("P1", 30_000, failingSinceMillis = 5_000))
        assertNull(FieldAnomalies.syncStall("P1", 30_000, failingSinceMillis = 15_000))
        assertNotNull(FieldAnomalies.gpsJump("P1", 10_000, 150.0, 2_000))
        assertNull(FieldAnomalies.gpsJump("P1", 10_000, 90.0, 1_000), "90 m is no jump")
        assertNull(FieldAnomalies.gpsJump("P1", 100_000, 150.0, 60_000), "150 m in a minute is a run")
        assertNotNull(FieldAnomalies.refusedNear("P1", "P2", 0, "TOO_FAR", 4.0))
        assertNull(FieldAnomalies.refusedNear("P1", "P2", 0, "TOO_FAR", 40.0))
        assertNull(FieldAnomalies.refusedNear("P1", "P2", 0, "open", 4.0), "taken")
        assertNull(FieldAnomalies.refusedNear("P1", "P2", 0, "TOO_FAR", null), "nothing known")
        assertEquals(FieldAnomalies.ERROR, FieldAnomalies.error("P1", 0, "E", "radio", "lat=52.1", null).kind)
        assertEquals(2, FieldAnomalies.server(0, 1, 2_000).size)
        assertTrue(FieldAnomalies.server(0, 0, 900).isEmpty())
    }

    @Test
    fun windowsGiveTheNumbersOfOnePass() {
        val (whole, wholeDigest) = compute(game(), FieldReportStream.MAX_WINDOW)
        for (window in listOf(FieldReportBuilder.WINDOW_MILLIS, 60_000L)) {
            val (windowed, digest) = compute(gameAgain(), window)
            assertEquals(whole.copy(windows = 0), windowed.copy(windows = 0), "windows of $window ms")
            assertTrue(windowed.windows > 1)
            // An anomaly found after its time went out is stamped later, its start in `since`: the same line else.
            val unshifted = digest.map(::unshifted)
            assertEquals(
                wholeDigest.sorted(),
                unshifted.sorted(),
                "the digest in windows of $window ms: only one pass ${(wholeDigest - unshifted.toSet()).take(5)}, " +
                    "only windows ${(unshifted - wholeDigest.toSet()).take(5)}",
            )
            val times = digest.drop(1).map { line ->
                kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject.getValue("t").jsonPrimitive.content
                    .toLong()
            }
            assertEquals(times.sorted(), times, "the digest's `t` never goes back (windows of $window ms)")
        }
    }

    /** A digest line as one pass writes it: an anomaly's `since` back as its `t`. */
    private fun unshifted(line: String): String {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject
        val since = json["since"] ?: return line
        return kotlinx.serialization.json.JsonObject(
            json.entries.filter { it.key != "since" }
                .associate { (key, value) -> key to if (key == "t") since else value },
        ).toString()
    }

    @Test
    fun theCapsKeepTheGamesEndAndEveryKindOfAnomaly() {
        phone(a, "Pixel 8", "Android 16", "aaaa0001", "scan_response")
        server.event(T0, ServerKinds.PHASE, "active", ServerFields.PHASE to "HIDING", ServerFields.FROM to "LOBBY")
        // A long and noisy game: more reveals and slow seconds of the server than the timeline and the list take.
        val noisy = 1_500
        for (i in 0 until noisy) {
            val t = T0 + 60_000 + i * 2_000L
            server.event(t, ServerKinds.REVEAL, "active", ServerFields.PLAYER to A_ID, ServerFields.EVENT to "start")
            server.event(t + 1, FieldKinds.SRV, "active", SrvFields.SYNC_P95 to 2_000)
        }
        val end = T0 + 60_000 + noisy * 2_000L
        // Late in it: the phone's syncs stall, its player marks, the game ends.
        a.sync(end - 40_000, ok = false)
        a.sync(end - 5_000, ok = true, millis = 100)
        a.event(end - 10_000, FieldKinds.MARK, "active", MarkFields.BY to MarkFields.PLAYER, MarkFields.TEXT to "hm")
        server.event(end, ServerKinds.PHASE, "active", ServerFields.PHASE to "FINISHED", ServerFields.FROM to "HIDING")
        val (report, _) = compute(listOf(a, b, c, server))

        assertTrue(report.timeline.any { it.text == "HIDING → FINISHED" }, "the game's end is in the timeline")
        assertEquals(
            FieldReportBuilder.MAX_TIMELINE_PER_KIND,
            report.timeline.count { it.kind == ServerKinds.REVEAL },
        )
        assertTrue(report.timelineDropped > 0)
        assertEquals(
            FieldReportBuilder.MAX_ANOMALIES_PER_KIND,
            report.anomalies.count { it.kind == FieldAnomalies.SERVER_SLOW },
        )
        assertEquals(noisy, report.anomalyCounts[FieldAnomalies.SERVER_SLOW])
        assertTrue(report.anomalies.any { it.kind == FieldAnomalies.SYNC_STALL }, "the late stall is listed")
        // Around the late mark: what the caps left out of the lists, too.
        val around = report.marks.single().around
        assertTrue(around.any { "${ServerKinds.REVEAL} P1 P1 start" in it }, "$around")
        assertTrue(around.any { FieldAnomalies.SERVER_SLOW in it }, "$around")
    }

    @Test
    fun theLiveReportsProblemsAreTheLatestCounts() {
        val builder = FieldReportBuilder(RUN, "game-1", devices)
        builder.streamCounts(badLines = 3, outside = 0)
        builder.report(NOW, final = false)
        builder.streamCounts(badLines = 5, outside = 2)
        val problems = builder.report(NOW, final = false).problems
        assertEquals(
            listOf(
                "5 lines that are no events",
                "2 events outside the game's time, left out",
                "The live report: the last minutes may be missing",
            ),
            problems,
        )
    }

    @Test
    fun aDistanceIsInterpolatedOnlyBetweenFixesCloseTogether() {
        fun catchMeters(fixGapMillis: Long): Double? {
            val seeker = Log(A_ID)
            val hider = Log(B_ID)
            val srv = Log(FieldKinds.SERVER_DEVICE)
            val start = T0 + MINUTE
            srv.event(T0, ServerKinds.PHASE, "active", ServerFields.PHASE to "SEEKING", ServerFields.FROM to "HIDING")
            for (t in start - 1_000..start + fixGapMillis + 1_000 step 1_000) hider.fix(t, ORIGIN)
            seeker.fix(start, ORIGIN.moveBy(eastMeters = 0.0, northMeters = 10.0))
            seeker.fix(start + fixGapMillis, ORIGIN.moveBy(eastMeters = 0.0, northMeters = 30.0))
            srv.event(
                start + fixGapMillis / 2,
                ServerKinds.CATCH,
                "active",
                ServerFields.CATCH to "c1",
                ServerFields.SEEKER to A_ID,
                ServerFields.HIDER to B_ID,
                ServerFields.OUTCOME to "confirmed",
            )
            val (report, _) = compute(listOf(seeker, hider, Log(C_ID), srv))
            return report.radar.zeroPoints.single { it.kind == FieldReportBuilder.CATCH_ZERO }.gpsMeters
        }
        val between = assertNotNull(catchMeters(8_000), "8 s apart: between the fixes")
        assertTrue(between in 19.5..20.5, "$between m")
        // 19 s apart, the catch 9.5 s from either: where the seeker was is no guess.
        assertNull(catchMeters(19_000))
    }

    /** The same game's logs once more (the logs are made by [game] of fresh ones). */
    private fun gameAgain(): List<Log> = FieldReportBuilderTest().game()

    @Test
    fun theExportsNameNoPlayerAndNoPlace() {
        val (report, digest) = compute(game())
        val markdown = FieldReportMarkdown.render(report)
        for (text in listOf(markdown, digest.joinToString("\n"))) {
            for (secret in listOf(A_ID, B_ID, C_ID, "\"lat\"", "\"lon\"", "52.37", "4.895")) {
                assertFalse(secret in text, "«$secret» in an export")
            }
        }
        assertTrue("| P1 | Pixel 8 |" in markdown, markdown)
        assertTrue("## 5. Anomalies" in markdown && FieldAnomalies.GPS_JUMP in markdown)
    }

    @Test
    fun theDigestHasTheMinutesTheGameAndTheMarks() {
        val (_, digest) = compute(game())
        val lines = digest.map { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }
        val header = lines.first()
        assertEquals("digest", header["k"]?.jsonPrimitive?.content)
        val minutes = lines.filter { it["k"]?.jsonPrimitive?.content == FieldDigest.MINUTE }
        for (p in listOf("P1", "P2", "P3")) {
            assertTrue(minutes.count { it["p"]?.jsonPrimitive?.content == p } >= 20, "$p's minutes")
        }
        val kinds = lines.mapNotNull { it["k"]?.jsonPrimitive?.content }.toSet()
        for (kind in listOf(
            ServerKinds.PHASE,
            ServerKinds.CLAIM,
            ServerKinds.CATCH,
            FieldKinds.SRV,
            FieldKinds.MARK,
            "anomaly",
            FieldKinds.SURVEY,
        )) {
            assertTrue(kind in kinds, "$kind in the digest: $kinds")
        }
        val claim = lines.first { it["k"]?.jsonPrimitive?.content == ServerKinds.CLAIM }
        assertEquals("P1", claim[ServerFields.SEEKER]?.jsonPrimitive?.content)
        val times = lines.drop(1).map { it["t"]!!.jsonPrimitive.content.toLong() }
        assertEquals(times.sorted(), times, "in time order")
        assertTrue(minutes.any { (it["peers"]?.jsonPrimitive?.content?.toInt() ?: 0) > 0 }, "the radio's peers")
    }

    private fun rows(digest: List<String>, kind: String = FieldPairs.KIND) =
        digest.map { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }
            .filter { it["k"]?.jsonPrimitive?.content == kind }

    @Test
    fun aPairRowHasTheDistanceBothWaysTheCarryAndTheBand() {
        val (_, digest) = compute(game())
        val header = kotlinx.serialization.json.Json.parseToJsonElement(digest.first()).jsonObject
        assertEquals(FieldPairs.ROWS_PER_MINUTE, header["pairs"]!!.jsonObject["cap_per_minute"]!!.jsonPrimitive.int)
        val pairs = rows(digest)
        // C is 300 m away and never heard: no row with it. A and B are 3 m apart and hear each other every minute.
        assertTrue(pairs.all { it["a"]!!.jsonPrimitive.content == "P1" && it["b"]!!.jsonPrimitive.content == "P2" })
        val row = pairs.first { it["t"]!!.jsonPrimitive.long == T0 + 10 * MINUTE }
        assertEquals("10:10", row["minute"]!!.jsonPrimitive.content)
        assertEquals(3.0, row["gps_m_median"]!!.jsonPrimitive.double, 0.2)
        assertEquals(3.0, row["gps_m_min"]!!.jsonPrimitive.double, 0.2)
        assertEquals(5.0, row["gps_acc_a"]!!.jsonPrimitive.double)
        assertEquals(5.0, row["gps_acc_b"]!!.jsonPrimitive.double)
        // a's signal as b heard it, and b's as a heard it.
        assertEquals(-62, row["rssi_ab_median"]!!.jsonPrimitive.int)
        assertEquals(-60, row["rssi_ba_median"]!!.jsonPrimitive.int)
        assertTrue(row["readings_ab"]!!.jsonPrimitive.int > 0 && row["readings_ba"]!!.jsonPrimitive.int > 0)
        assertEquals("in_hand", row["carry_a"]!!.jsonPrimitive.content)
        assertEquals("in_pocket", row["carry_b"]!!.jsonPrimitive.content)
        assertEquals("android", row["plat_a"]!!.jsonPrimitive.content)
        assertEquals("ios", row["plat_b"]!!.jsonPrimitive.content)
        assertEquals("Pixel 8", row["model_a"]!!.jsonPrimitive.content)
        assertEquals("iPhone15,2", row["model_b"]!!.jsonPrimitive.content)
        assertEquals("ble.service_data.scan_response", row["channels"]!!.jsonArray.single().jsonPrimitive.content)
        // The band the game showed from +6 min on, the shadow's other one; before it none was shown.
        assertEquals("warm", row["band"]!!.jsonPrimitive.content)
        assertEquals("cold", row["shadow_band"]!!.jsonPrimitive.content)
        assertTrue(pairs.first { it["t"]!!.jsonPrimitive.long == T0 + 3 * MINUTE }["band"] == null)
        // B was silent from +21 to +25 min: no rows then (the GPS reaches 3 s over the gap's edge).
        assertTrue(pairs.none { it["t"]!!.jsonPrimitive.long in T0 + 22 * MINUTE until T0 + 24 * MINUTE })
        for (text in digest) {
            for (secret in listOf(A_ID, B_ID, C_ID, "\"lat\"", "\"lon\"", "52.37", "4.895")) {
                assertFalse(secret in text, "«$secret» in the digest")
            }
        }
    }

    @Test
    fun aBandFarFromTheGpsDistanceIsTheWorstMinute() {
        val logs = game()
        // A hears C (300 m away by GPS) at +8 min and the game shows «burning» for it for two minutes.
        a.rx(T0 + 8 * MINUTE + 10_000, "cccc0001", -58)
        a.rx(T0 + 9 * MINUTE + 10_000, "cccc0001", -58)
        server.event(
            T0 + 8 * MINUTE,
            ServerKinds.BAND,
            "active",
            ServerFields.OBSERVER to A_ID,
            ServerFields.HEARD to C_ID,
            ServerFields.BAND to "BURNING",
            ServerFields.FROM to "NONE",
            ServerFields.SHADOW_BAND to "NONE",
        )
        server.event(
            T0 + 10 * MINUTE,
            ServerKinds.BAND,
            "active",
            ServerFields.OBSERVER to A_ID,
            ServerFields.HEARD to C_ID,
            ServerFields.BAND to "NONE",
            ServerFields.FROM to "BURNING",
            ServerFields.SHADOW_BAND to "NONE",
        )
        val (report, digest) = compute(logs)
        val worst = report.radar.worstMinutes
        assertEquals(listOf(T0 + 8 * MINUTE, T0 + 9 * MINUTE), worst.map { it.atMillis }, "A and B's warm is no lie")
        val first = worst.first()
        assertEquals("P1" to "P3", first.a to first.b)
        assertEquals("burning", first.band)
        // 300 m for «burning» (10 m) is 290 m off, less the 5 m accuracy's tolerance.
        assertEquals(285.0, first.disagreementM!!, 1.0)
        assertTrue(rows(digest).any { it["band_off_m"] != null })
        val far = report.radar.btVsGps.filter { it.bucket == "40+" }
        assertTrue(far.isNotEmpty() && far.all { it.bandSeconds > 0 && it.bandAgree == 0 }, "$far")
        val near = report.radar.btVsGps.filter { it.bucket == "0-5" }
        assertTrue(near.any { it.bandSeconds > 0 && it.bandAgree == it.bandSeconds }, "$near")
        val markdown = FieldReportMarkdown.render(report)
        assertTrue("### Bluetooth против GPS" in markdown, markdown)
        assertTrue("The 2 worst minutes" in markdown && "| burning |" in markdown, markdown)
        for (secret in listOf(A_ID, B_ID, C_ID, "\"lat\"", "52.37", "4.895")) assertFalse(secret in markdown)
    }

    @Test
    fun theBandsJudgementCountsTheGpsError() {
        assertNull(FieldPairs.disagreement(null, 5.0))
        assertNull(FieldPairs.disagreement("hot", null))
        assertEquals(0.0, FieldPairs.disagreement("burning", 8.0))
        assertEquals(40.0, FieldPairs.disagreement("burning", 50.0))
        assertEquals(30.0, FieldPairs.disagreement("burning", 50.0, toleranceMeters = 10.0))
        assertEquals(0.0, FieldPairs.disagreement("hot", 3.0))
        assertEquals(17.0, FieldPairs.disagreement("none", 3.0))
        assertEquals(0.0, FieldPairs.disagreement("NONE", 100.0))
        assertEquals(0.0, FieldPairs.disagreement("warm", 5.0))
        assertEquals(0.0, FieldPairs.disagreement("burning", 12.0, toleranceMeters = 5.0))
    }

    @Test
    fun theDigestOfFiftyPlayersStaysWithinItsCap() {
        val count = 50
        val minutes = 6
        val phones = (0 until count).map { Log("player-$it") }
        val everyone = phones.mapIndexed { i, log ->
            val android = i % 2 == 0
            phone(
                log,
                if (android) "Pixel 8" else "iPhone15,2",
                if (android) "Android 16" else "iOS 26.0",
                token(i),
                null,
            )
            FieldReportDevice(log.label, "d-$i")
        }
        val game = Log(FieldKinds.SERVER_DEVICE)
        game.event(T0, ServerKinds.PHASE, "active", ServerFields.PHASE to "HIDING", ServerFields.FROM to "LOBBY")
        var t = T0 + 60_000
        while (t < T0 + minutes * MINUTE) {
            for ((i, log) in phones.withIndex()) {
                // A grid 5 m apart, all of them within 60 m of each other.
                log.fix(t, ORIGIN.moveBy(eastMeters = (i % 10) * 5.0, northMeters = (i / 10) * 5.0))
                if (t % 15_000L == 0L) {
                    for (k in 1..10) log.rx(t, token((i + k) % count), -70 - k)
                }
            }
            t += 5_000
        }
        val digest = ArrayList<String>()
        val builder = FieldReportBuilder(RUN, "game-50", everyone, digest = { digest += it })
        val stream = FieldReportStream(builder)
        for ((log, device) in phones.zip(everyone)) stream.add(device.deviceId, device.label, log.lines.asSequence())
        stream.add("d-server", FieldKinds.SERVER_DEVICE, game.lines.asSequence())
        stream.finish()
        val report = builder.report(NOW, final = true)
        val pairs = rows(digest)
        val byMinute = pairs.groupBy { it["t"]!!.jsonPrimitive.long }
        assertTrue(byMinute.isNotEmpty())
        for ((minute, ofMinute) in byMinute) {
            assertTrue(ofMinute.size <= FieldPairs.ROWS_PER_MINUTE, "$minute: ${ofMinute.size} pair rows")
        }
        // 1225 pairs are within 60 m every minute: the cap cuts and says how many.
        val cut = rows(digest, FieldPairs.CUT_KIND)
        assertTrue(cut.isNotEmpty() && cut.all { it["dropped"]!!.jsonPrimitive.int > 0 })
        for (line in cut) {
            val minute = line["t"]!!.jsonPrimitive.long
            assertEquals(1225, byMinute.getValue(minute).size + line["dropped"]!!.jsonPrimitive.int)
        }
        // The heard pairs come first.
        val first = byMinute.getValue(T0 + 2 * MINUTE)
        assertTrue(first.all { (it["readings_ab"]!!.jsonPrimitive.int + it["readings_ba"]!!.jsonPrimitive.int) > 0 })
        val bytes = pairs.sumOf { it.toString().length + 1 }
        assertTrue(bytes <= minutes * FieldPairs.ROWS_PER_MINUTE * 700L, "pair rows: $bytes bytes in all")
        assertEquals(count, report.summary.players)
        assertTrue(report.radar.btVsGps.isNotEmpty())
        assertTrue(report.radar.worstMinutes.size <= FieldPairs.WORST)
    }

    private fun token(i: Int) = (i + 1).toString(16).padStart(8, '0')

    @Test
    fun aRawSliceTakesDevicesAndTime() {
        val slice = FieldRawSlice(setOf(B_ID), fromMillis = 1_000, toMillis = 2_000)
        assertTrue(slice.includes("d-b", B_ID))
        assertFalse(slice.includes("d-a", A_ID))
        assertTrue(FieldRawSlice().includes("d-a", A_ID))
        assertTrue(slice.keeps("""{"t":1500,"dt":1,"k":"tick"}"""))
        assertFalse(slice.keeps("""{"t":2500,"dt":1,"k":"tick"}"""))
        assertTrue(slice.keeps("""{"dt":1,"k":"tick","t":1000}"""), "t anywhere")
        assertTrue(slice.keeps("""{"k":"session"}"""), "a line without a time stays")
    }

    @Test
    fun theLiveReportFollowsTheFedLines() {
        val logs = game()
        val builder = FieldReportBuilder(RUN, "game-1", devices)
        val stream = FieldReportStream(builder, windowMillis = 60_000L)
        // The logs arrive in two halves, as the phones upload them.
        for ((log, device) in logs.zip(devices)) {
            stream.feed(device.deviceId, device.label, log.lines.take(log.lines.size / 2).asSequence())
        }
        stream.advance(T0 + 40 * MINUTE)
        val first = builder.report(NOW, final = false)
        assertFalse(first.final)
        for ((log, device) in logs.zip(devices)) {
            stream.feed(device.deviceId, device.label, log.lines.drop(log.lines.size / 2).asSequence())
        }
        stream.advance(T0 + 40 * MINUTE)
        val second = builder.report(NOW, final = false)
        assertTrue(second.players.first().gps.fixes > first.players.first().gps.fixes)
        assertEquals(3, second.summary.players)
    }

    @Test
    fun theMergeTellsTheServersEventsFromThePhones() {
        val logs = game()
        a.event(T0 + 6 * MINUTE, "band", "active", "token" to "bbbb0001", "band" to "warm")
        val merge = LabMerge(logs.map { it.label to it.lines.joinToString("\n") })
        val summary = merge.summary()
        assertTrue("Bands at the end (the lab's smoothing): $B_ID → $A_ID: warm" in summary, summary)
        assertTrue("Bands at the end (the game's server): $B_ID → $A_ID: WARM (shadow COLD)" in summary, summary)
        val timeline = merge.timeline()
        assertTrue("CLAIM $A_ID → $B_ID · open · GPS ≥ 1.0 m · proximity in the shadow: no" in timeline, timeline)
        assertTrue("server band $B_ID → $A_ID NONE → WARM (shadow COLD)" in timeline)
        assertTrue(
            LabMerge.isServer(
                merge.events.first {
                    it.k == ServerKinds.BAND && it.dev == FieldKinds.SERVER_DEVICE
                },
            ),
        )
    }

    @Test
    fun theStreamReadsALogAsTheMergeDoes() {
        val log = Log("A", offset = 500)
        log.event(T0 + 1, FieldKinds.SESSION, "active", "schema" to 2)
        log.event(T0 + 2, FieldKinds.TICK)
        log.event(T0 + 3, "clock", "active", "offset" to 500)
        log.event(T0 + 4, FieldKinds.TICK)
        val read = LabEvents.read(log.lines.asSequence()).first.map { it.t to it.k }
        val streamed = LabEvents.stream(log.lines.asSequence()).toList().map { it.t to it.k }
        assertEquals(read, streamed)
    }

    private companion object {
        const val RUN = "run-1"
        const val A_ID = "player-a-123"
        const val B_ID = "player-b-456"
        const val C_ID = "player-c-789"
        const val MINUTE = 60_000L

        /** 2026-10-01 10:00 UTC, a whole window. */
        const val T0 = 1_790_848_800_000L
        const val NOW = T0 + 3_600_000L
        val ORIGIN = GeoPoint(52.370216, 4.895168)
    }
}
