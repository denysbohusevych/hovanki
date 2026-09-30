package app.hovanki.client.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hovanki.client.BuildInfo
import app.hovanki.client.diagnostics.BenchRadio
import app.hovanki.client.diagnostics.Diagnostics
import app.hovanki.client.diagnostics.DiagnosticsBench
import app.hovanki.client.diagnostics.DiagnosticsState
import app.hovanki.client.location.LocationProvider
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.session.GameSessionManager
import app.hovanki.client.session.SessionState
import app.hovanki.client.share.ShareSheet
import app.hovanki.device.DeviceInfo
import app.hovanki.device.PocketPulse
import app.hovanki.radar.ProximityRadio
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.RadarBand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The debug build's diagnostics panel (docs/architecture.md, «Диагностика debug-сборки»): what [Diagnostics] measured,
 * the game as this phone sees it, and the bench — Bluetooth and GPS outside a game. The bench stops the moment a game
 * starts: the game's radio and GPS take over.
 */
class DiagnosticsViewModel(
    private val diagnostics: Diagnostics,
    private val bench: DiagnosticsBench,
    private val sessionManager: GameSessionManager,
    private val locationProvider: LocationProvider,
    private val radio: ProximityRadio,
    private val deviceInfo: DeviceInfo,
    private val buildInfo: BuildInfo,
    private val serverUrl: ServerUrl,
    private val shareSheet: ShareSheet,
    private val pocketPulse: PocketPulse,
) : ViewModel() {
    val measured: StateFlow<DiagnosticsState> = diagnostics.state
    val session: StateFlow<SessionState> = sessionManager.state
    val bluetooth: StateFlow<BluetoothState> = radio.state
    val radarEnabled: StateFlow<Boolean> = sessionManager.radarEnabled
    val pulse: StateFlow<RadarBand> = sessionManager.pulse
    val benchRadio: StateFlow<BenchRadio?> = bench.radioMode
    val benchGps: StateFlow<Boolean> = bench.gpsOn
    val benchToken: String = bench.token

    private val mutableTriedPulse = MutableStateFlow(RadarBand.NONE)

    /** The pulse the developer is feeling outside a round ([tryPulse]); [RadarBand.NONE]: quiet. */
    val triedPulse: StateFlow<RadarBand> = mutableTriedPulse.asStateFlow()

    /** The build, the phone and the server, for the panel's top and the report's header. */
    val about: List<String>
        get() = listOf(
            "Hovanki ${buildInfo.label}${if (buildInfo.isEmulator) " · emulator" else ""}",
            "Phone: ${deviceInfo.platform} ${deviceInfo.model ?: "unknown model"}, UWB ${deviceInfo.hasUwb}, " +
                "motion sensor ${deviceInfo.hasActivitySensor}",
            "Server: ${serverUrl.value}",
        )

    init {
        viewModelScope.launch {
            sessionManager.state
                .map { it.session != null }
                .distinctUntilChanged()
                .filter { it }
                .collect { bench.stop() }
        }
        // A round beats its own pulse: the one being tried stops when it starts.
        viewModelScope.launch {
            sessionManager.state
                .map { it.isInRound() }
                .distinctUntilChanged()
                .filter { it }
                .collect { tryPulse(RadarBand.NONE) }
        }
    }

    /** Beats the pulse of [band] on this phone, as a round would, until told [RadarBand.NONE]; not in a round. */
    fun tryPulse(band: RadarBand) {
        if (band != RadarBand.NONE && session.value.isInRound()) return
        if (band == RadarBand.NONE && mutableTriedPulse.value == RadarBand.NONE) return
        mutableTriedPulse.value = band
        pocketPulse.set(band)
    }

    override fun onCleared() {
        tryPulse(RadarBand.NONE)
    }

    fun hasLocationPermission(): Boolean = locationProvider.hasPermission()

    /** Looks at the phone's Bluetooth again: after the permission dialog (on iOS, the first look asks). */
    fun refreshBluetooth() {
        radio.refresh()
    }

    fun setBenchRadio(mode: BenchRadio?) {
        if (mode == null || session.value.session != null) bench.stopRadio() else bench.startRadio(mode.asSeeker)
    }

    /** False when the location permission is missing. */
    fun setBenchGps(on: Boolean): Boolean {
        if (!on || session.value.session != null) {
            bench.stopGps()
            return true
        }
        return bench.startGps()
    }

    fun share() {
        shareSheet.share(diagnostics.report(about))
    }

    fun clear() {
        diagnostics.clear()
    }
}

/** Hiding or seeking: the round's radio and pulse run. */
internal fun SessionState.isInRound(): Boolean =
    session != null && (snapshot?.phase == GamePhase.HIDING || snapshot?.phase == GamePhase.SEEKING)
