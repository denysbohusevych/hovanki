package app.hovanki.client.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.diagnostics.BenchRadio
import app.hovanki.client.lab.LabController
import app.hovanki.client.lab.LabPlaces
import app.hovanki.client.lab.LabPulse
import app.hovanki.client.lab.LabRunState
import app.hovanki.client.lab.LabScenarios
import app.hovanki.client.lab.ProbeMode
import app.hovanki.client.lab.RunPhase
import app.hovanki.client.location.rememberLocationPermissionRequester
import app.hovanki.client.radio.rememberBluetoothPermissionRequester
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.PopTextField
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.rules.OverflowArea
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel

// The radio lab (docs/radio-lab.md §5): every experiment behind a switch, all into one log with the server's time.
// Developer-facing, debug builds only: plain English like the rest of the diagnostics.

/** The lab's tab of the diagnostics panel. */
@Composable
internal fun LabContent() {
    val viewModel = koinViewModel<LabViewModel>()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    // Timers on the screen need a clock that moves while nothing else does.
    var now by remember { mutableLongStateOf(viewModel.monoNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = viewModel.monoNow()
            delay(LAB_TICK_MILLIS)
        }
    }
    val inGame = session.session != null
    HeaderCard(viewModel, running, inGame, now)
    RunCard(viewModel, inGame, now)
    if (!running) return
    AsInGameCard(viewModel)
    LabRadioCard(viewModel, now)
    ScreenAndVibrationCard(viewModel)
    MarksCard(viewModel)
    ScenarioCard(viewModel, now)
}

@Composable
private fun HeaderCard(viewModel: LabViewModel, running: Boolean, inGame: Boolean, now: Long) {
    val label by viewModel.label.collectAsStateWithLifecycle()
    val clock by viewModel.clock.collectAsStateWithLifecycle()
    val count by viewModel.count.collectAsStateWithLifecycle()
    Section("Lab") {
        SecondaryText(
            "Experiments of docs/radio-lab.md: one log per device on the server's clock. Outside a game only; " +
                "export after every experiment (iOS may drop the app and its memory).",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("A", "B", "droid").forEach { option ->
                PopButton(
                    text = option,
                    onClick = { viewModel.setLabel(option) },
                    height = 40.dp,
                    style = if (label == option) PopStyle.Dark else PopStyle.Outline,
                    textStyle = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val offset = clock?.let {
            val age = (now - it.measuredAtMono) / 1000
            "clock ${if (it.offsetMillis >= 0) "+" else ""}${it.offsetMillis} ms (rtt ${it.rttMillis} ms, ${age}s ago)"
        } ?: "clock not measured yet"
        Line("device $label · $offset")
        Line("$count events")
        BenchSwitch(
            text = "Lab running",
            checked = running,
            enabled = !inGame || running,
            onCheckedChange = viewModel::setRunning,
        )
        if (inGame) SecondaryText("In a game: the lab is off.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PopButton(text = "Export", onClick = viewModel::export, height = 44.dp, style = PopStyle.Dark)
            PopButton(text = "Clear", onClick = viewModel::clear, height = 44.dp, style = PopStyle.Outline)
        }
    }
}

/**
 * The automatic radio run (docs/radio-lab-tests.md): one button, the steps change by themselves, the Mac follows
 * (`run.sh --lab --auto`); the tester locks the phone when told and unlocks it at the end.
 */
@Composable
private fun RunCard(viewModel: LabViewModel, inGame: Boolean, @Suppress("UNUSED_PARAMETER") tick: Long) {
    val run by viewModel.run.collectAsStateWithLifecycle()
    val runError by viewModel.runError.collectAsStateWithLifecycle()
    val bluetooth by viewModel.bluetooth.collectAsStateWithLifecycle()
    val requestBluetooth = rememberBluetoothPermissionRequester { viewModel.refreshBluetooth() }
    val requestLocation = rememberLocationPermissionRequester { viewModel.startRun() }
    val current = run
    Section("Radio run (automatic)") {
        if (current == null || current.finished) {
            SecondaryText(
                "About 10 minutes, the phone and the Mac on the table 1 m apart. On the Mac first: " +
                    "e2e/mac-beacon/run.sh --lab --auto. Then press the button, keep the screen on, lock the phone " +
                    "when it says so and leave it until the notification.",
            )
            runError?.let { SecondaryText("⚠ $it") }
            if (current != null) RunResult(viewModel, current)
            PopButton(
                text = if (current == null) "Start the radio run" else "Run again",
                onClick = {
                    when {
                        bluetooth != BluetoothState.ON -> {
                            viewModel.refreshBluetooth()
                            requestBluetooth()
                        }

                        !viewModel.hasLocationPermission() -> requestLocation()

                        else -> viewModel.startRun()
                    }
                },
                enabled = !inGame,
                height = 52.dp,
                style = PopStyle.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            if (bluetooth != BluetoothState.ON) SecondaryText("Bluetooth is $bluetooth: the button asks for it first.")
        } else {
            val now = viewModel.serverNow()
            val step = current.step
            if (step == null) {
                Line("Starting in ${seconds(current.startAtServer - now)} s: the Mac joins when it hears the phone.")
            } else {
                val stepEnd = current.startAtServer + current.script.startOf(current.index) + step.seconds * 1000L
                Line("Step ${current.index + 1} of ${current.script.steps.size}: ${step.title}")
                if (step.phase == RunPhase.LOCK) {
                    Text(
                        "LOCK THE PHONE NOW · ${seconds(stepEnd - now)} s",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Palette.PinkInk,
                    )
                }
                if (step.hint.isNotEmpty()) SecondaryText(step.hint)
                Line("${clock(stepEnd - now)} left in the step · ${clock(current.endsAtServer - now)} in all")
            }
            Line(
                "Mac: " + if (current.macHeard) "heard ✓" else "not heard yet (is run.sh --lab --auto running?)",
            )
            if (current.index >= 3) Line("Mac's iBeacon: " + if (current.macBeaconHeard) "heard ✓" else "not heard")
            current.warnings.forEach { SecondaryText("⚠ $it") }
            PopButton(text = "Stop the run", onClick = viewModel::stopRun, height = 40.dp, style = PopStyle.Outline)
        }
    }
}

@Composable
private fun RunResult(viewModel: LabViewModel, run: LabRunState) {
    Line(if (run.stopped) "The run was stopped." else "The run is over (token ${run.token}).")
    Line("Mac heard: ${if (run.macHeard) "yes" else "no"} · Mac's iBeacon: ${if (run.macBeaconHeard) "yes" else "no"}")
    run.warnings.forEach { SecondaryText("⚠ $it") }
    if (run.hadHapticTest && viewModel.hapticGroups > 0) {
        SecondaryText("Which vibration groups did you hear or feel while it was locked?")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..viewModel.hapticGroups).forEach { group ->
                ChoiceButton("$group", false, Modifier.weight(1f)) { viewModel.felt(group) }
            }
        }
    }
    SecondaryText("Then Export, and AirDrop the file to the Mac; the Mac's log of the run is already there.")
    PopButton(text = "Export", onClick = viewModel::export, height = 44.dp, style = PopStyle.Dark)
}

private fun seconds(millis: Long): Long = (millis.coerceAtLeast(0) + 999) / 1000

private fun clock(millis: Long): String {
    val total = seconds(millis)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

@Composable
private fun AsInGameCard(viewModel: LabViewModel) {
    val inGame by viewModel.inGame.collectAsStateWithLifecycle()
    var noPermission by remember { mutableStateOf(false) }
    val requestLocation = rememberLocationPermissionRequester { granted ->
        noPermission = !granted
        if (granted) viewModel.setInGame(true)
    }
    Section("As in a game") {
        BenchSwitch(
            text = "GPS every second, in the background too",
            checked = inGame,
            enabled = true,
            onCheckedChange = { on ->
                if (on && !viewModel.hasLocationPermission()) requestLocation() else viewModel.setInGame(on)
            },
        )
        SecondaryText("In a round GPS keeps the app alive in the background; without it the background tells nothing.")
        if (noPermission) SecondaryText("No location permission.")
    }
}

@Composable
private fun LabRadioCard(viewModel: LabViewModel, now: Long) {
    val bluetooth by viewModel.bluetooth.collectAsStateWithLifecycle()
    val benchRadio by viewModel.benchRadio.collectAsStateWithLifecycle()
    val probe by viewModel.probe.collectAsStateWithLifecycle()
    val probeToken by viewModel.probeToken.collectAsStateWithLifecycle()
    val rotateAt by viewModel.rotateAt.collectAsStateWithLifecycle()
    val listening by viewModel.listening.collectAsStateWithLifecycle()
    val requestBluetooth = rememberBluetoothPermissionRequester { viewModel.refreshBluetooth() }
    val bluetoothOn = bluetooth == BluetoothState.ON
    Section("Radio") {
        Line("Bluetooth $bluetooth · bench token ${viewModel.benchToken}")
        BenchSwitch(
            text = "As a hider (the game's radio)",
            checked = benchRadio == BenchRadio(asSeeker = false),
            enabled = bluetoothOn,
            onCheckedChange = { on -> viewModel.setBenchRadio(if (on) BenchRadio(asSeeker = false) else null) },
        )
        BenchSwitch(
            text = "As a seeker, iBeacon (the game's radio)",
            checked = benchRadio == BenchRadio(asSeeker = true),
            enabled = bluetoothOn,
            onCheckedChange = { on -> viewModel.setBenchRadio(if (on) BenchRadio(asSeeker = true) else null) },
        )
        if (viewModel.canListen) {
            BenchSwitch(
                text = "Listen to everything (overflow masks, iBeacon)",
                checked = listening,
                enabled = bluetoothOn,
                onCheckedChange = viewModel::setListening,
            )
        }
        if (viewModel.canProbe) ProbeControls(viewModel, probe, probeToken, rotateAt, now, bluetoothOn)
        if (!bluetoothOn) {
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

@Composable
private fun ProbeControls(
    viewModel: LabViewModel,
    probe: ProbeMode?,
    probeToken: String,
    rotateAt: Long?,
    now: Long,
    bluetoothOn: Boolean,
) {
    var bit by remember { mutableIntStateOf(0) }
    SecondaryText("Overflow probe: the game's service and the table's UUIDs; locked, only the mask stays.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceButton("off", probe == null, Modifier.weight(1f)) { viewModel.setProbe(null) }
        ChoiceButton("0x5A", probe == ProbeMode.Pattern, Modifier.weight(1f), bluetoothOn) {
            viewModel.setProbe(ProbeMode.Pattern)
        }
        ChoiceButton("bit $bit", probe is ProbeMode.Bit, Modifier.weight(1f), bluetoothOn) {
            viewModel.setProbe(ProbeMode.Bit(bit))
        }
        ChoiceButton("token", probe == ProbeMode.Token, Modifier.weight(1f), bluetoothOn) {
            viewModel.setProbe(ProbeMode.Token)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(-10, -1, 1, 10).forEach { step ->
            ChoiceButton(if (step > 0) "+$step" else "$step", false, Modifier.weight(1f)) {
                bit = nextBit(bit, step)
                if (probe is ProbeMode.Bit) viewModel.setProbe(ProbeMode.Bit(bit))
            }
        }
    }
    Line("probe token $probeToken")
    val rotating = rotateAt?.let { "changes in ${((it - now) / 1000).coerceAtLeast(0)} s" }
    PopButton(
        text = rotating ?: "Change the token in 60 s",
        onClick = viewModel::rotateProbeToken,
        enabled = probe == ProbeMode.Token && rotateAt == null,
        height = 40.dp,
        style = PopStyle.Outline,
    )
}

@Composable
private fun ScreenAndVibrationCard(viewModel: LabViewModel) {
    val screenOff by viewModel.screenOff.collectAsStateWithLifecycle()
    val pulse by viewModel.pulse.collectAsStateWithLifecycle()
    val hapticTest by viewModel.hapticTest.collectAsStateWithLifecycle()
    Section("Screen and vibration") {
        if (viewModel.canTurnScreenOff) {
            BenchSwitch(
                text = "Screen off by the proximity sensor",
                checked = screenOff,
                enabled = true,
                onCheckedChange = viewModel::setScreenOff,
            )
        }
        SecondaryText("Vibration test: 10 s to lock the phone, then groups of 3 beats: ${viewModel.hapticKinds}.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PopButton(
                text = if (hapticTest == null) "Vibration test" else "Stop",
                onClick = { if (hapticTest == null) viewModel.startHapticTest() else viewModel.stopHapticTest() },
                enabled = viewModel.hapticGroups > 0,
                height = 44.dp,
                style = PopStyle.Dark,
            )
        }
        hapticTest?.let { Line(it) }
        if (viewModel.hapticGroups > 0) {
            SecondaryText("Felt, after unlocking:")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..viewModel.hapticGroups).forEach { group ->
                    ChoiceButton("$group", false, Modifier.weight(1f)) { viewModel.felt(group) }
                }
            }
        }
        SecondaryText("Pulse by the lab's loudest band:")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabPulse.entries.forEach { option ->
                ChoiceButton(option.name.lowercase(), pulse == option, Modifier.weight(1f)) {
                    viewModel.setPulse(option)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarksCard(viewModel: LabViewModel) {
    var text by remember { mutableStateOf("") }
    Section("Marks") {
        SecondaryText("Distance")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LabPlaces.DISTANCES.forEach { meters ->
                val shown = if (meters % 1.0 == 0.0) meters.toInt().toString() else meters.toString()
                ChoiceButton("$shown m", false) { viewModel.mark("$shown m", distance = meters) }
            }
        }
        SecondaryText("Where the phone is")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LabPlaces.ALL.forEach { place -> ChoiceButton(place, false) { viewModel.mark(place, place = place) } }
        }
        SecondaryText("What you do")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LabPlaces.ACTIONS.forEach { action ->
                ChoiceButton(action, false) { viewModel.mark(action, action = action) }
            }
        }
        PopTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            label = { Text("Any text") },
            modifier = Modifier.fillMaxWidth(),
        )
        PopButton(
            text = "Mark",
            onClick = {
                viewModel.mark(text.trim())
                text = ""
            },
            enabled = text.isNotBlank(),
            height = 40.dp,
            style = PopStyle.Outline,
        )
    }
}

@Composable
private fun ScenarioCard(viewModel: LabViewModel, now: Long) {
    val run by viewModel.scenario.collectAsStateWithLifecycle()
    Section("Scenario") {
        val current = run
        if (current == null) {
            LabScenarios.ALL.forEach { scenario ->
                PopButton(
                    text = "${scenario.title} · ${scenario.steps.size} steps",
                    onClick = { viewModel.startScenario(scenario) },
                    height = 40.dp,
                    style = PopStyle.Outline,
                    textStyle = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            val step = current.step
            val left = current.remainingMillis(now).coerceAtLeast(0) / 1000
            Line(current.scenario.title)
            Line("Step ${current.index + 1} of ${current.scenario.steps.size}: ${step.label}")
            if (step.hint.isNotEmpty()) SecondaryText(step.hint)
            Line(if (current.elapsed) "time is up" else "${left / 60}:${(left % 60).toString().padStart(2, '0')} left")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PopButton(
                    text = if (current.isLast) "Finish" else "Next",
                    onClick = viewModel::nextStep,
                    height = 44.dp,
                    style = if (current.elapsed) PopStyle.Primary else PopStyle.Dark,
                )
                PopButton(text = "Stop", onClick = viewModel::stopScenario, height = 44.dp, style = PopStyle.Outline)
            }
        }
    }
}

@Composable
private fun ChoiceButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    PopButton(
        text = text,
        onClick = onClick,
        enabled = enabled,
        height = 40.dp,
        style = if (selected) PopStyle.Dark else PopStyle.Outline,
        textStyle = MaterialTheme.typography.labelMedium,
        modifier = modifier,
    )
}

/** The next bit the probe may set, skipping the Apple Watch's. */
private fun nextBit(bit: Int, step: Int): Int {
    var next = (bit + step).mod(OverflowArea.BITS)
    if (next in LabController.FORBIDDEN_BITS) next = (next + if (step > 0) 1 else -1).mod(OverflowArea.BITS)
    return next
}

private const val LAB_TICK_MILLIS = 500L
