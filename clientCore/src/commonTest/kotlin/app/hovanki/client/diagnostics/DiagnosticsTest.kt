package app.hovanki.client.diagnostics

import app.hovanki.client.location.LocationProvider
import app.hovanki.client.session.FakeRadio
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.DeviceReport
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.rules.RadarSmoother
import app.hovanki.shared.rules.RadarToken
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The debug build's diagnostics (docs/architecture.md, «Диагностика debug-сборки»). */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsTest {
    private var now = 1_700_000_000_000L
    private val diagnostics = Diagnostics(isEnabled = true) { now }

    @Test
    fun releaseBuildsRecordNothing() {
        val off = Diagnostics.Off
        off.onFix(5.0, isMock = false, fixAtMillis = now)
        off.onSighting("0123abcd", -60, now)
        off.onSyncSent(1, null)
        off.onSynced(now)
        off.note("phase SEEKING")
        assertEquals(DiagnosticsState(), off.state.value)
    }

    @Test
    fun aFixIsItsAccuracyAndPaceNeverItsPlace() {
        diagnostics.onFix(12.4, isMock = false, fixAtMillis = now - 300)
        now += 1_000
        diagnostics.onFix(4.0, isMock = true, fixAtMillis = now - 200)
        now += 1_000
        diagnostics.onFix(8.0, isMock = false, fixAtMillis = now - 100)

        val state = diagnostics.state.value
        val gps = assertNotNull(state.gps)
        assertEquals(8.0, gps.accuracyMeters)
        assertEquals(1_100L, gps.sincePreviousMillis)
        assertEquals(100L, gps.receivedAtMillis - gps.fixAtMillis)
        assertEquals(3, state.gpsFixes)
        assertEquals(8.0, state.medianAccuracy)
        assertEquals(listOf("±12 m", "±4 m, +1.1 s, MOCK", "±8 m, +1.1 s"), state.log.map { it.text })
    }

    @Test
    fun whatTheRadioHearsIsSmoothedLikeTheServer() {
        val server = RadarSmoother()
        val readings = listOf(-80, -72, -65, -62, -61, -90)
        readings.forEachIndexed { i, rssi ->
            val at = now + i * 400L
            diagnostics.onSighting("0123abcd", rssi, at, isRival = true)
            server.add(rssi, at)
        }
        diagnostics.onSighting("89abcdef", -88, now)

        val contacts = diagnostics.state.value.contacts
        assertEquals(listOf("89abcdef", "0123abcd"), contacts.map { it.token }, "the latest first")
        val rival = contacts.last()
        assertEquals(-90, rival.lastRssi)
        assertEquals(-90, rival.minRssi)
        assertEquals(-61, rival.maxRssi)
        assertEquals(readings.size, rival.readings)
        assertEquals(server.levelDbm, rival.levelDbm)
        assertEquals(server.bandAt(now + 2_000L), rival.band)
        assertTrue(rival.isRival)
        // At most a line a second per phone, whatever the scan's pace: readings at 0, 0.4 … 2.0 s give lines at 0 and
        // 1.2 s (the next one is due at 2.2 s).
        val lines = diagnostics.state.value.log.filter { it.text.startsWith("0123abcd") }
        assertEquals(listOf(now, now + 1_200L), lines.map { it.atMillis })
    }

    @Test
    fun theSyncsAreTimed() {
        val device = DeviceReport(platform = Platform.ANDROID, bluetooth = BluetoothState.ON)
        diagnostics.onSyncSent(sightings = 3, device = device)
        now += 250
        diagnostics.onSynced(serverTimeMillis = now + 10_000)

        var state = diagnostics.state.value
        assertEquals(1, state.syncs)
        assertEquals(250L, state.lastSync?.durationMillis)
        assertEquals(3, state.lastSync?.sightings)
        assertEquals(device, state.device)
        assertEquals(10_000L, state.serverOffsetMillis)

        diagnostics.onSyncSent(sightings = 0, device = device)
        now += 5_000
        diagnostics.onSyncFailed(IllegalStateException("timeout"), retryInMillis = 2_000)
        state = diagnostics.state.value
        assertEquals(1, state.syncFailures)
        assertEquals("timeout", state.lastSync?.error)
        assertEquals("sync failed: timeout, retry in 2.0 s", state.log.last().text)
    }

    @Test
    fun theReportCarriesTheNumbers() {
        diagnostics.onFix(6.0, isMock = false, fixAtMillis = now)
        diagnostics.onSighting("0123abcd", -67, now)
        val report = diagnostics.report(listOf("Hovanki 0.1.0 (1) · abc · debug"))

        assertTrue(report.startsWith("Hovanki 0.1.0 (1) · abc · debug\n"))
        assertTrue("0123abcd: last -67 dBm" in report, report)
        assertTrue("last ±6 m" in report, report)

        diagnostics.clear()
        assertEquals(0, diagnostics.state.value.gpsFixes)
        assertTrue(diagnostics.state.value.log.isEmpty())
    }

    @Test
    fun theLogKeepsTheLatestLines() {
        repeat(Diagnostics.MAX_LOG_LINES + 10) { diagnostics.note("line $it") }
        val log = diagnostics.state.value.log
        assertEquals(Diagnostics.MAX_LOG_LINES, log.size)
        assertEquals("line ${Diagnostics.MAX_LOG_LINES + 9}", log.last().text)
    }

    @Test
    fun clockTimesAreUtc() {
        assertEquals("00:00:00.000", Diagnostics.formatClock(0))
        assertEquals("22:13:20.042", Diagnostics.formatClock(1_700_000_000_042L))
    }

    @Test
    fun theBenchAdvertisesAMadeUpTokenAndListens() = runTest {
        val radio = FakeRadio()
        val bench = DiagnosticsBench(radio, SilentLocation(permission = false), diagnostics, backgroundScope)
        assertTrue(RadarToken.isWellFormed(bench.token))

        bench.startRadio(asSeeker = true)
        runCurrent()
        assertEquals(1, radio.collectors)
        assertEquals(bench.token, radio.tokens?.value)
        assertEquals(true, radio.asSeeker)
        assertEquals(BenchRadio(asSeeker = true), bench.radioMode.value)
        assertEquals(bench.token, diagnostics.state.value.ownToken)

        radio.hears("89abcdef", -58, now)
        runCurrent()
        assertEquals(RadarBand.BURNING, diagnostics.state.value.contacts.single().band)

        // Another mode: the radio starts again with it.
        bench.startRadio(asSeeker = false)
        runCurrent()
        assertEquals(1, radio.collectors)
        assertEquals(false, radio.asSeeker)

        bench.stop()
        runCurrent()
        assertEquals(0, radio.collectors)
        assertNull(bench.radioMode.value)
        assertNull(diagnostics.state.value.ownToken)
    }

    @Test
    fun theBenchGpsNeedsThePermission() = runTest {
        val denied = DiagnosticsBench(FakeRadio(), SilentLocation(permission = false), diagnostics, backgroundScope)
        assertFalse(denied.startGps())
        assertFalse(denied.gpsOn.value)

        val location = SilentLocation(permission = true)
        val bench = DiagnosticsBench(FakeRadio(), location, diagnostics, backgroundScope)
        assertTrue(bench.startGps())
        runCurrent()
        assertTrue(bench.gpsOn.value)
        location.fixes.emit(LocationSample(GeoPoint(50.45, 30.52), accuracyMeters = 9.0, timestampMillis = now))
        runCurrent()
        assertEquals(9.0, diagnostics.state.value.gps?.accuracyMeters)

        bench.stopGps()
        runCurrent()
        assertFalse(bench.gpsOn.value)
        assertEquals(0, location.fixes.subscriptionCount.value)
    }

    private class SilentLocation(private val permission: Boolean) : LocationProvider {
        val fixes = MutableSharedFlow<LocationSample>()

        override fun hasPermission() = permission

        override fun locationUpdates(intervalMillis: Long): Flow<LocationSample> = fixes
    }
}
