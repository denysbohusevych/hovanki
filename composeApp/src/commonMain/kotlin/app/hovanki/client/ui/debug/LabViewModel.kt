package app.hovanki.client.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.diagnostics.BenchRadio
import app.hovanki.client.lab.ClockEstimate
import app.hovanki.client.lab.HapticKind
import app.hovanki.client.lab.LabController
import app.hovanki.client.lab.LabLog
import app.hovanki.client.lab.LabPulse
import app.hovanki.client.lab.LabRun
import app.hovanki.client.lab.LabRunState
import app.hovanki.client.lab.LabRunner
import app.hovanki.client.lab.LabScenario
import app.hovanki.client.lab.ProbeMode
import app.hovanki.client.location.LocationProvider
import app.hovanki.client.radio.ProximityRadio
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.session.SessionState
import app.hovanki.shared.protocol.BluetoothState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The radio lab's tab of the debug build's diagnostics (docs/radio-lab.md §5). The lab itself ([LabController]) lives
 * on when the panel closes; it stops when a game appears, as the bench does.
 */
class LabViewModel(
    private val lab: LabController,
    private val runner: LabRunner,
    private val sessionManager: GameSessionManager,
    private val radio: ProximityRadio,
    private val locationProvider: LocationProvider,
) : ViewModel() {
    val running: StateFlow<Boolean> = lab.running
    val label: StateFlow<String> = lab.log.label
    val clock: StateFlow<ClockEstimate?> = lab.log.clock
    val count: StateFlow<Long> = lab.log.count
    val inGame: StateFlow<Boolean> = lab.inGame
    val probe: StateFlow<ProbeMode?> = lab.probe
    val probeToken: StateFlow<String> = lab.probeToken
    val rotateAt: StateFlow<Long?> = lab.rotateAt
    val listening: StateFlow<Boolean> = lab.listening
    val screenOff: StateFlow<Boolean> = lab.screenOff
    val pulse: StateFlow<LabPulse> = lab.pulse
    val hapticTest: StateFlow<String?> = lab.hapticTest
    val felt: StateFlow<Set<Int>> = lab.felt
    val scenario: StateFlow<LabRun?> = lab.scenarios.run
    val benchRadio: StateFlow<BenchRadio?> = lab.bench.radioMode
    val bluetooth: StateFlow<BluetoothState> = radio.state
    val session: StateFlow<SessionState> = sessionManager.state
    val run: StateFlow<LabRunState?> = runner.state
    val runError: StateFlow<String?> = runner.error

    val benchToken: String get() = lab.bench.token
    val canProbe: Boolean get() = lab.canProbe
    val canListen: Boolean get() = lab.canListen
    val canTurnScreenOff: Boolean get() = lab.canTurnScreenOff
    val hapticKinds: List<HapticKind> get() = lab.hapticKinds

    private val mutableLastMark = MutableStateFlow<String?>(null)

    /** The last mark put by hand, with its time: the buttons' answer on the screen. */
    val lastMark: StateFlow<String?> = mutableLastMark.asStateFlow()

    init {
        viewModelScope.launch {
            sessionManager.state
                .map { it.session != null }
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    runner.stop()
                    lab.stop()
                }
        }
    }

    /** The lab's monotonic clock, for the timers on the screen. */
    fun monoNow(): Long = lab.log.monoNow()

    /** The server's clock as the lab knows it: the run's timers. */
    fun serverNow(): Long = lab.log.serverNow()

    fun setRunning(on: Boolean) {
        if (on && session.value.session == null) lab.start() else lab.stop()
    }

    fun setLabel(label: String) = lab.setLabel(label)

    fun hasLocationPermission(): Boolean = locationProvider.hasPermission()

    fun setInGame(on: Boolean) = lab.setInGame(on)

    fun refreshBluetooth() = radio.refresh()

    fun setBenchRadio(mode: BenchRadio?) = lab.setBenchRadio(mode?.asSeeker)

    fun setProbe(mode: ProbeMode?) = lab.setProbe(mode)

    fun rotateProbeToken() = lab.rotateProbeToken()

    fun setListening(on: Boolean) = lab.setListening(on)

    fun setScreenOff(on: Boolean) = lab.setScreenOff(on)

    fun setPulse(pulse: LabPulse) = lab.setPulse(pulse)

    /**
     * The vibration test as a test of its own: the lab starts if it is off; [keepAwake] keeps the app alive locked by
     * «as in a game» (GPS), off for the attempt without it.
     */
    fun startHapticTest(keepAwake: Boolean) {
        if (session.value.session != null) return
        if (!running.value) lab.start()
        lab.setInGame(keepAwake)
        lab.startHapticTest()
    }

    fun stopHapticTest() = lab.stopHapticTest()

    fun toggleFelt(group: Int) = lab.toggleFelt(group)

    fun mark(label: String, place: String? = null, action: String? = null, distance: Double? = null) {
        lab.mark(label, place, action, distance)
        mutableLastMark.value = "$label · ${LabLog.formatUtc(lab.log.serverNow()).substringAfter(' ').take(8)} UTC"
    }

    fun startScenario(scenario: LabScenario) = lab.scenarios.start(scenario)

    fun nextStep() = lab.scenarios.next()

    fun stopScenario() = lab.scenarios.stop()

    /** The automatic radio run (docs/radio-lab-tests.md); not in a game. */
    fun startRun() {
        if (session.value.session == null) runner.start()
    }

    fun stopRun() = runner.stop()

    fun export() {
        viewModelScope.launch { lab.export() }
    }

    fun clear() = lab.clear()
}
