@file:OptIn(ExperimentalTime::class)

package app.hovanki.client.diagnostics

import app.hovanki.client.lab.LabLog
import app.hovanki.client.location.LocationProvider
import app.hovanki.radar.ProximityRadio
import app.hovanki.shared.rules.RadarToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The debug build's bench (docs/architecture.md, «Диагностика debug-сборки»): Bluetooth and GPS outside a game, to
 * measure them where the game would be. Two phones on the bench hear each other: each advertises a made-up token, the
 * way a game's hider or seeker would ([startRadio]), and reports the other's dBm into [diagnostics]; walk apart and
 * watch the numbers. GPS reports every fix's accuracy. Only outside a game: the owner stops the bench when a game
 * starts ([stop]), so a round's radio never shares the adapter with it. The radio lab (docs/radio-lab.md §5) builds on
 * it: every reading and fix goes into [lab] too while the lab records. Main thread.
 */
class DiagnosticsBench(
    private val radio: ProximityRadio,
    private val locationProvider: LocationProvider,
    private val diagnostics: Diagnostics,
    private val scope: CoroutineScope,
    random: Random = Random.Default,
    private val lab: LabLog = LabLog.Off,
    private val deviceTimeMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /** What this phone advertises on the bench: a token like a game's, the same until the app restarts. */
    val token: String = random.nextBytes(RadarToken.LENGTH / 2).joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private val mutableRadio = MutableStateFlow<BenchRadio?>(null)

    /** The radio on the bench; null: off. */
    val radioMode: StateFlow<BenchRadio?> = mutableRadio.asStateFlow()

    private val mutableGps = MutableStateFlow(false)
    val gpsOn: StateFlow<Boolean> = mutableGps.asStateFlow()

    private var radioJob: Job? = null
    private var gpsJob: Job? = null

    /**
     * Advertises [token] (the bench's own unless the radio lab's run gives one) as a hider's service, or as a seeker's
     * iBeacon ([asSeeker]), and listens.
     */
    fun startRadio(asSeeker: Boolean, token: String = this.token) {
        stopRadio()
        radio.refresh()
        mutableRadio.value = BenchRadio(asSeeker)
        diagnostics.onRadio(token, asSeeker)
        lab.note("bench radio on as ${if (asSeeker) "seeker" else "hider"}, token $token")
        val job = scope.launch {
            try {
                radio.run(MutableStateFlow(token), asSeeker).collect { sighting ->
                    diagnostics.onSighting(sighting.token, sighting.rssi, sighting.atMillis, via = sighting.via)
                    lab.rx(
                        sighting.token,
                        sighting.rssi,
                        sighting.api,
                        sighting.via,
                        sighting.peer,
                        sighting.atMillis,
                        sighting.tech,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnostics.note("bench radio failed: ${e.message ?: e::class.simpleName}")
            }
        }
        radioJob = job
        // Ended by itself (Bluetooth failed underneath): off. A job replaced meanwhile changes nothing.
        job.invokeOnCompletion { if (radioJob === job) stopRadio() }
    }

    fun stopRadio() {
        radioJob?.cancel()
        radioJob = null
        if (mutableRadio.value != null) {
            diagnostics.onRadio(null, asSeeker = false)
            lab.note("bench radio off")
        }
        mutableRadio.value = null
    }

    /** GPS fixes every [intervalMillis]; needs the location permission. False: no permission. */
    fun startGps(intervalMillis: Long = GPS_INTERVAL_MILLIS): Boolean {
        if (!locationProvider.hasPermission()) return false
        stopGps()
        mutableGps.value = true
        diagnostics.note("bench GPS on")
        val job = scope.launch {
            try {
                locationProvider.locationUpdates(intervalMillis).collect { fix ->
                    diagnostics.onFix(fix.accuracyMeters, fix.isMock, fix.timestampMillis)
                    lab.gps(fix.accuracyMeters, (deviceTimeMillis() - fix.timestampMillis).coerceAtLeast(0))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Permission taken back or location switched off.
                diagnostics.note("bench GPS failed: ${e.message ?: e::class.simpleName}")
            }
        }
        gpsJob = job
        job.invokeOnCompletion { if (gpsJob === job) stopGps() }
        return true
    }

    fun stopGps() {
        gpsJob?.cancel()
        gpsJob = null
        if (mutableGps.value) diagnostics.note("bench GPS off")
        mutableGps.value = false
    }

    fun stop() {
        stopRadio()
        stopGps()
    }

    companion object {
        const val GPS_INTERVAL_MILLIS = 1_000L
    }
}

/** The bench's radio: advertising as a seeker (the iBeacon frame) or a hider (the game's service). */
data class BenchRadio(val asSeeker: Boolean)
