@file:OptIn(ExperimentalTime::class)

package app.hovanki.client.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.BuildInfo
import app.hovanki.client.diagnostics.BenchRadio
import app.hovanki.client.diagnostics.Diagnostics
import app.hovanki.client.diagnostics.DiagnosticsState
import app.hovanki.client.location.rememberLocationPermissionRequester
import app.hovanki.client.network.Transport
import app.hovanki.client.radio.rememberBluetoothPermissionRequester
import app.hovanki.client.session.SessionState
import app.hovanki.client.ui.common.Panel
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.appSafeDrawingPadding
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.rules.HeartbeatRules
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

// The debug build's diagnostics (docs/architecture.md, «Диагностика debug-сборки»). Developer-facing and never shown in
// other builds, so the texts are plain English here rather than translated resources.

/**
 * A small «DBG» tab at the screen's left edge, over every screen of a debug build; it opens the diagnostics panel.
 * Other builds: nothing.
 */
@Composable
fun DiagnosticsOverlay(modifier: Modifier = Modifier) {
    val buildInfo = koinInject<BuildInfo>()
    if (!buildInfo.isDebug) return
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxSize()) {
        if (open) {
            DiagnosticsPanel(
                onClose = { open = false },
                modifier = Modifier.appSafeDrawingPadding(),
            )
        } else {
            DebugTab(
                onClick = { open = true },
                modifier = Modifier.align(Alignment.CenterStart).appSafeDrawingPadding(),
            )
        }
    }
}

@Composable
private fun DebugTab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = koinViewModel<DiagnosticsViewModel>()
    val measured by viewModel.measured.collectAsStateWithLifecycle()
    // The latest fix's accuracy right on the tab: the one number worth a glance during a round.
    val accuracy = measured.gps?.accuracyMeters?.let { "±${it.roundToInt()}" }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp))
            .background(Palette.Ink.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("DBG", color = Palette.Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        if (accuracy != null) Text(accuracy, color = Color.White, fontSize = 10.sp)
    }
}

@Composable
private fun DiagnosticsPanel(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = koinViewModel<DiagnosticsViewModel>()
    val measured by viewModel.measured.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val bluetooth by viewModel.bluetooth.collectAsStateWithLifecycle()
    val radarEnabled by viewModel.radarEnabled.collectAsStateWithLifecycle()
    val pulse by viewModel.pulse.collectAsStateWithLifecycle()
    val benchRadio by viewModel.benchRadio.collectAsStateWithLifecycle()
    val benchGps by viewModel.benchGps.collectAsStateWithLifecycle()
    val triedPulse by viewModel.triedPulse.collectAsStateWithLifecycle()
    // «How long ago» needs a clock that moves while nothing else does.
    var now by remember { mutableLongStateOf(deviceNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = deviceNow()
            delay(TICK_MILLIS)
        }
    }
    var labShown by remember { mutableStateOf(false) }
    Panel(title = "Diagnostics", onClose = onClose, modifier = modifier) {
        ScreenColumn {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false to "Diagnostics", true to "Lab").forEach { (lab, title) ->
                    PopButton(
                        text = title,
                        onClick = { labShown = lab },
                        height = 40.dp,
                        style = if (labShown == lab) PopStyle.Dark else PopStyle.Outline,
                        textStyle = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (labShown) {
                LabContent()
            } else {
                viewModel.about.forEach { SecondaryText(it) }
                GpsCard(measured, now, viewModel, benchGps, inGame = session.session != null)
                RadioCard(
                    measured = measured,
                    now = now,
                    bluetooth = bluetooth,
                    radarEnabled = radarEnabled,
                    pulse = pulse,
                    benchRadio = benchRadio,
                    inGame = session.session != null,
                    viewModel = viewModel,
                )
                PulseCard(triedPulse, inRound = session.isInRound(), viewModel = viewModel)
                GameCard(session, measured, now)
                LogCard(measured, viewModel)
            }
        }
    }
}

@Composable
private fun GpsCard(
    measured: DiagnosticsState,
    now: Long,
    viewModel: DiagnosticsViewModel,
    benchGps: Boolean,
    inGame: Boolean,
) {
    var noPermission by remember { mutableStateOf(false) }
    val requestLocation = rememberLocationPermissionRequester { granted ->
        noPermission = !granted
        if (granted) noPermission = !viewModel.setBenchGps(true)
    }
    Section("GPS") {
        val gps = measured.gps
        if (gps == null) {
            Line("no fix yet")
        } else {
            val interval = gps.sincePreviousMillis?.let { "${Diagnostics.seconds(it)} s" } ?: "—"
            Line("last fix ±${gps.accuracyMeters.roundToInt()} m, ${ago(now, gps.fixAtMillis)} ago")
            Line("interval $interval, mock ${gps.isMock}")
            Line("delivered ${Diagnostics.seconds(gps.receivedAtMillis - gps.fixAtMillis)} s after the fix")
        }
        val median = measured.medianAccuracy?.let {
            " · median ±${it.roundToInt()} m of ${measured.recentAccuracy.size}"
        }
        Line("${measured.gpsFixes} fixes${median.orEmpty()}")
        BenchSwitch(
            text = "GPS outside a game, every second",
            checked = benchGps,
            enabled = !inGame,
            onCheckedChange = { on ->
                if (on && !viewModel.hasLocationPermission()) {
                    requestLocation()
                } else {
                    noPermission = !viewModel.setBenchGps(on)
                }
            },
        )
        if (noPermission) SecondaryText("No location permission.")
    }
}

@Composable
private fun RadioCard(
    measured: DiagnosticsState,
    now: Long,
    bluetooth: BluetoothState,
    radarEnabled: Boolean,
    pulse: RadarBand,
    benchRadio: BenchRadio?,
    inGame: Boolean,
    viewModel: DiagnosticsViewModel,
) {
    val requestBluetooth = rememberBluetoothPermissionRequester { viewModel.refreshBluetooth() }
    Section("Bluetooth") {
        Line("state $bluetooth · radar on this phone $radarEnabled · pulse $pulse")
        val own = measured.ownToken?.let { "$it as ${if (measured.radioAsSeeker) "seeker (iBeacon)" else "hider"}" }
        Line("advertising ${own ?: "nothing"}")
        if (measured.contacts.isEmpty()) {
            Line("heard nobody yet")
        } else {
            SecondaryText("token (R: the other team's) · last dBm (min…max) · smoothed · band · readings · ago")
            measured.contacts.forEach { contact ->
                val level = contact.levelDbm?.let(Diagnostics::formatDbm) ?: "—"
                val rival = if (contact.isRival) " R" else ""
                Line(
                    "${contact.token}$rival ${contact.lastRssi} (${contact.minRssi}…${contact.maxRssi}) $level " +
                        "${contact.band.name} ×${contact.readings} ${ago(now, contact.lastAtMillis)}",
                    color = bandColor(contact.band),
                )
            }
        }
        SecondaryText(
            "Two phones on the bench hear each other: this one advertises ${viewModel.benchToken}. " +
                "Smoothed and banded like the server, without the pocket's −12 dB.",
        )
        BenchSwitch(
            text = "Radio outside a game, as a hider",
            checked = benchRadio == BenchRadio(asSeeker = false),
            enabled = !inGame && bluetooth == BluetoothState.ON,
            onCheckedChange = { on -> viewModel.setBenchRadio(if (on) BenchRadio(asSeeker = false) else null) },
        )
        BenchSwitch(
            text = "Radio outside a game, as a seeker (iBeacon)",
            checked = benchRadio == BenchRadio(asSeeker = true),
            enabled = !inGame && bluetooth == BluetoothState.ON,
            onCheckedChange = { on -> viewModel.setBenchRadio(if (on) BenchRadio(asSeeker = true) else null) },
        )
        if (bluetooth != BluetoothState.ON) {
            PopButton(
                text = "Ask for Bluetooth",
                onClick = {
                    viewModel.refreshBluetooth()
                    requestBluetooth()
                },
                style = PopStyle.Outline,
                height = 44.dp,
            )
        }
    }
}

/** The heartbeat of each band on this phone, to feel it without a game. */
@Composable
private fun PulseCard(triedPulse: RadarBand, inRound: Boolean, viewModel: DiagnosticsViewModel) {
    Section("Pulse") {
        listOf(RadarBand.WARM, RadarBand.HOT, RadarBand.BURNING).forEach { band ->
            val beat = HeartbeatRules.beat(band) ?: return@forEach
            val strength = "${(beat.softAmplitude * 100).roundToInt()}% → ${(beat.strongAmplitude * 100).roundToInt()}%"
            Line("${band.name}: every ${Diagnostics.seconds(beat.periodMillis)} s, $strength")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(RadarBand.WARM, RadarBand.HOT, RadarBand.BURNING).forEach { band ->
                PopButton(
                    text = band.name.lowercase(),
                    onClick = { viewModel.tryPulse(band) },
                    enabled = !inRound,
                    height = 40.dp,
                    style = if (triedPulse == band) PopStyle.Dark else PopStyle.Outline,
                    textStyle = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        PopButton(
            text = "Stop",
            onClick = { viewModel.tryPulse(RadarBand.NONE) },
            enabled = triedPulse != RadarBand.NONE,
            height = 40.dp,
            style = PopStyle.Quiet,
        )
        if (inRound) SecondaryText("The round beats its own pulse.")
    }
}

@Composable
private fun GameCard(session: SessionState, measured: DiagnosticsState, now: Long) {
    Section("Game") {
        val snapshot = session.snapshot
        if (snapshot == null) {
            Line("not in a game")
        } else {
            Line("${snapshot.phase} · ${snapshot.me.role} ${snapshot.me.status} · ${session.connectionStatus}")
            Line("sync every ${snapshot.settings.rules.syncIntervalSeconds} s · features ${snapshot.settings.features}")
            snapshot.me.radar?.let { radar ->
                val names = snapshot.players.associate { it.id to it.name }
                val contacts = radar.contacts.joinToString { contact ->
                    "${contact.playerId?.let { names[it] ?: it.value } ?: "?"} ${contact.band}"
                }
                Line("server's radar: ${contacts.ifEmpty { "nobody" }}")
            }
        }
        val via = measured.transport?.let { if (it == Transport.SOCKET) " by socket" else " by polling" }.orEmpty()
        Line("${measured.syncs} syncs$via, ${measured.syncFailures} failed")
        measured.lastSync?.let { sync ->
            val took = sync.durationMillis?.let { "$it ms" } ?: "—"
            val result = sync.error?.let { "failed: $it" } ?: "ok"
            Line("last ${ago(now, sync.atMillis)} ago, $took, $result")
        }
        measured.serverOffsetMillis?.let { Line("server clock ${if (it >= 0) "+" else ""}$it ms from the phone's") }
        measured.device?.let { device ->
            Line(
                "told the server: bluetooth ${device.bluetooth}, carry ${device.carry}, activity ${device.activity}, " +
                    "on screen ${device.onScreen}, model ${device.model ?: "—"}",
            )
        }
    }
}

@Composable
private fun LogCard(measured: DiagnosticsState, viewModel: DiagnosticsViewModel) {
    Section("Log") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PopButton(text = "Share", onClick = viewModel::share, height = 44.dp, style = PopStyle.Dark)
            PopButton(text = "Clear", onClick = viewModel::clear, height = 44.dp, style = PopStyle.Outline)
        }
        SecondaryText(
            "Times are UTC. Nothing here leaves the phone unless shared; no coordinates, only accuracy. " +
                "${measured.log.size} lines kept, the newest first.",
        )
        measured.log.asReversed().take(SHOWN_LOG_LINES).forEach { line ->
            Line("${Diagnostics.formatClock(line.atMillis)} ${line.kind.name.padEnd(5)} ${line.text}")
        }
    }
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    PopCard(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
internal fun Line(text: String, color: Color = Palette.Ink) {
    Text(text = text, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
}

@Composable
internal fun BenchSwitch(text: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) Palette.Ink else Palette.Ink3,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Ink,
                checkedTrackColor = Palette.Lime,
                checkedBorderColor = Palette.Ink,
                uncheckedThumbColor = Palette.Ink3,
                uncheckedTrackColor = Palette.Paper,
                uncheckedBorderColor = Palette.Ink,
            ),
        )
    }
}

private fun bandColor(band: RadarBand): Color = when (band) {
    RadarBand.NONE -> Palette.Ink3
    RadarBand.WARM -> Palette.OrangeInk
    RadarBand.HOT, RadarBand.BURNING -> Palette.PinkInk
}

private fun ago(now: Long, atMillis: Long): String = "${Diagnostics.seconds((now - atMillis).coerceAtLeast(0))} s"

private fun deviceNow(): Long = Clock.System.now().toEpochMilliseconds()

private const val TICK_MILLIS = 500L
private const val SHOWN_LOG_LINES = 200
