package app.hovanki.client.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.catchcode.CatchCodeScanner
import app.hovanki.client.diagnostics.BenchRadio
import app.hovanki.client.lab.LabController
import app.hovanki.client.lab.LabFollowState
import app.hovanki.client.lab.LabPulse
import app.hovanki.client.lab.LabRunState
import app.hovanki.client.lab.LabScenarios
import app.hovanki.client.location.rememberLocationPermissionRequester
import app.hovanki.client.radio.rememberBluetoothPermissionRequester
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.PopTextField
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.theme.Palette
import app.hovanki.device.lab.HapticKind
import app.hovanki.shared.lab.LabJoinCode
import app.hovanki.shared.lab.LabPlaces
import app.hovanki.shared.lab.ProbeMode
import app.hovanki.shared.lab.RunPhase
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.LabRunAction
import app.hovanki.shared.protocol.LabRunStatus
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
    val follow by viewModel.follow.collectAsStateWithLifecycle()
    val following = follow?.left == false
    ServerRunCard(viewModel, inGame, now)
    HeaderCard(viewModel, running, inGame, following, now)
    RunCard(viewModel, inGame, following, now)
    VibrationCard(viewModel, inGame)
    if (!running) return
    AsInGameCard(viewModel)
    LabRadioCard(viewModel, now)
    ScreenAndPulseCard(viewModel)
    MarksCard(viewModel)
    ScenarioCard(viewModel, now)
}

/**
 * A run of the lab on the server (docs/adr/0017-radar-techniques-and-big-run.md §5): an admin makes it in the admin's
 * «Радиолаба», the phones join by its code (typed or its QR), follow its plan by the server's clock and upload their
 * logs; the buttons are the admin's and every phone's.
 */
@Composable
private fun ServerRunCard(viewModel: LabViewModel, inGame: Boolean, @Suppress("UNUSED_PARAMETER") tick: Long) {
    val follow by viewModel.follow.collectAsStateWithLifecycle()
    val followError by viewModel.followError.collectAsStateWithLifecycle()
    val current = follow
    Section("Server run") {
        if (current == null || current.left) {
            JoinServerRun(viewModel, inGame, followError)
        } else {
            FollowedRun(viewModel, current, followError)
        }
    }
}

@Composable
private fun JoinServerRun(viewModel: LabViewModel, inGame: Boolean, error: String?) {
    val logLabel by viewModel.label.collectAsStateWithLifecycle()
    val bluetooth by viewModel.bluetooth.collectAsStateWithLifecycle()
    val localRun by viewModel.run.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }
    var label by remember(logLabel) { mutableStateOf(logLabel) }
    var scanning by remember { mutableStateOf(false) }
    val requestBluetooth = rememberBluetoothPermissionRequester { viewModel.refreshBluetooth() }
    val requestLocation = rememberLocationPermissionRequester { viewModel.joinRun(code, label) }
    val localRunning = localRun?.finished == false
    SecondaryText(
        "An admin makes the run in the admin («Радиолаба») and shows its code. Join as this phone's label; the " +
            "steps change by themselves on every phone and the log goes to the server.",
    )
    PopTextField(
        value = code,
        onValueChange = { text ->
            code = text.uppercase().filter { it in LabJoinCode.ALPHABET }.take(LabJoinCode.LENGTH)
        },
        singleLine = true,
        label = { Text("Code") },
        modifier = Modifier.fillMaxWidth(),
    )
    PopTextField(
        value = label,
        onValueChange = { label = it.trim().take(LABEL_MAX_LENGTH) },
        singleLine = true,
        label = { Text("Label: A, B, droid…") },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PopButton(
            text = if (scanning) "Close the camera" else "Scan QR",
            onClick = { scanning = !scanning },
            height = 44.dp,
            style = PopStyle.Outline,
            modifier = Modifier.weight(1f),
        )
        PopButton(
            text = "Join",
            onClick = {
                when {
                    bluetooth != BluetoothState.ON -> {
                        viewModel.refreshBluetooth()
                        requestBluetooth()
                    }

                    !viewModel.hasLocationPermission() -> requestLocation()

                    else -> viewModel.joinRun(code, label)
                }
            },
            enabled = !inGame && !localRunning && code.length == LabJoinCode.LENGTH && label.isNotBlank(),
            height = 44.dp,
            style = PopStyle.Primary,
            modifier = Modifier.weight(1f),
        )
    }
    if (scanning) {
        // The camera in the card: the panel scrolls, so not full screen as the game's scanner.
        Box(modifier = Modifier.fillMaxWidth().height(SCANNER_HEIGHT).clip(RoundedCornerShape(16.dp))) {
            CatchCodeScanner(
                onScanned = { text ->
                    viewModel.runCodeFromQr(text)?.let {
                        code = it
                        scanning = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    if (bluetooth != BluetoothState.ON) SecondaryText("Bluetooth is $bluetooth: «Join» asks for it first.")
    if (localRunning) SecondaryText("The local radio run is on: stop it first.")
    if (inGame) SecondaryText("In a game: no lab.")
    error?.let { SecondaryText("⚠ $it") }
}

@Composable
private fun FollowedRun(viewModel: LabViewModel, run: LabFollowState, error: String?) {
    val pending by viewModel.uploadPending.collectAsStateWithLifecycle()
    val uploadError by viewModel.uploadError.collectAsStateWithLifecycle()
    val now = viewModel.serverNow()
    val status = run.plan.status
    Line("${run.script.title} · ${run.code} · as ${run.label}")
    Line("${status.name.lowercase()} · token ${run.radarToken}")
    val step = run.step
    when {
        run.finished -> Line("The run is over: unlock the phone. Leave when the upload is done.")

        step == null -> Line("Waiting for the start: the admin or any phone presses Next.")

        else -> {
            Line("Step ${run.plan.stepIndex + 1} of ${run.script.steps.size}: ${step.title}")
            val setup = run.setup
            if (setup?.phase == RunPhase.LOCK) {
                Text("LOCK THE PHONE NOW", style = MaterialTheme.typography.headlineSmall, color = Palette.PinkInk)
            }
            setup?.let { Line("this phone: ${it.describe()}") }
            val hint = run.device?.hint.orEmpty().ifEmpty { step.hint }
            if (hint.isNotEmpty()) SecondaryText(hint)
            val endsAt = run.stepEndsAtMillis
            Line(
                when {
                    endsAt != null -> "${clock(endsAt - now)} left in the step"
                    status == LabRunStatus.PAUSED -> "paused"
                    else -> "until Next"
                },
            )
        }
    }
    Line("upload: $pending pending")
    uploadError?.let { SecondaryText("⚠ upload: $it") }
    error?.let { SecondaryText("⚠ $it") }
    run.warnings.forEach { SecondaryText("⚠ $it") }
    if (!run.finished) {
        val started = status == LabRunStatus.RUNNING || status == LabRunStatus.PAUSED
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceButton("Next", false, Modifier.weight(1f)) { viewModel.advanceRun(LabRunAction.NEXT) }
            ChoiceButton("Repeat", false, Modifier.weight(1f), enabled = started) {
                viewModel.advanceRun(LabRunAction.REPEAT)
            }
            if (status == LabRunStatus.PAUSED) {
                ChoiceButton("Resume", false, Modifier.weight(1f)) { viewModel.advanceRun(LabRunAction.RESUME) }
            } else {
                ChoiceButton("Pause", false, Modifier.weight(1f), enabled = started) {
                    viewModel.advanceRun(LabRunAction.PAUSE)
                }
            }
        }
    }
    PopButton(text = "Leave", onClick = viewModel::leaveRun, height = 40.dp, style = PopStyle.Outline)
}

@Composable
private fun HeaderCard(viewModel: LabViewModel, running: Boolean, inGame: Boolean, following: Boolean, now: Long) {
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
            enabled = (!inGame || running) && !following,
            onCheckedChange = viewModel::setRunning,
        )
        if (inGame) SecondaryText("In a game: the lab is off.")
        if (following) SecondaryText("In a server run: leave it to stop the lab.")
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
private fun RunCard(
    viewModel: LabViewModel,
    inGame: Boolean,
    following: Boolean,
    @Suppress("UNUSED_PARAMETER") tick: Long,
) {
    val run by viewModel.run.collectAsStateWithLifecycle()
    val runError by viewModel.runError.collectAsStateWithLifecycle()
    val bluetooth by viewModel.bluetooth.collectAsStateWithLifecycle()
    val requestBluetooth = rememberBluetoothPermissionRequester { viewModel.refreshBluetooth() }
    val requestLocation = rememberLocationPermissionRequester { viewModel.startRun() }
    val current = run
    Section("Radio run (automatic)") {
        if (current == null || current.finished) {
            SecondaryText(
                "Bluetooth only, about 9 minutes, the phone and the Mac on the table 1 m apart. On the Mac first: " +
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
                enabled = !inGame && !following,
                height = 52.dp,
                style = PopStyle.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            if (following) SecondaryText("In a server run: the local run waits until you leave it.")
            if (bluetooth != BluetoothState.ON) SecondaryText("Bluetooth is $bluetooth: the button asks for it first.")
        } else {
            val now = viewModel.serverNow()
            val step = current.step
            if (step == null) {
                Line("Starting in ${seconds(current.startAtServer - now)} s: the Mac joins when it hears the phone.")
            } else {
                val stepEnd =
                    current.startAtServer + current.script.startOf(current.index) + (step.seconds ?: 0) * 1000L
                Line("Step ${current.index + 1} of ${current.script.steps.size}: ${step.title}")
                if (current.setup?.phase == RunPhase.LOCK) {
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

/**
 * The vibration test on its own (H2): lock when told, then bursts of 1, 2, 3 and 4 beats, one kind each; the count
 * tells which kind got through even when another stays silent. Afterwards the tester marks the counts they felt.
 */
@Composable
private fun VibrationCard(viewModel: LabViewModel, inGame: Boolean) {
    val hapticTest by viewModel.hapticTest.collectAsStateWithLifecycle()
    val felt by viewModel.felt.collectAsStateWithLifecycle()
    val kinds = viewModel.hapticKinds
    Section("Vibration test (separate)") {
        if (kinds.isEmpty()) {
            SecondaryText("No vibration to test on this phone.")
        } else {
            VibrationTest(viewModel, inGame, hapticTest, felt)
        }
    }
}

@Composable
private fun VibrationTest(viewModel: LabViewModel, inGame: Boolean, hapticTest: String?, felt: Set<Int>) {
    var keepAwake by remember { mutableStateOf(true) }
    val kinds = viewModel.hapticKinds
    SecondaryText(
        "15 s to lock the phone, then bursts 6 s apart; count the beats in each burst. " +
            "A notification says when it is over.",
    )
    kinds.forEachIndexed { index, kind ->
        Line("${index + 1} ${if (index == 0) "beat" else "beats"} · ${kind.label}")
    }
    BenchSwitch(
        text = "Keep the app awake by GPS («as in a game»)",
        checked = keepAwake,
        enabled = hapticTest == null,
        onCheckedChange = { keepAwake = it },
    )
    PopButton(
        text = if (hapticTest == null) "Start the vibration test" else "Stop",
        onClick = {
            if (hapticTest == null) viewModel.startHapticTest(keepAwake) else viewModel.stopHapticTest()
        },
        enabled = !inGame,
        height = 48.dp,
        style = if (hapticTest == null) PopStyle.Primary else PopStyle.Outline,
        modifier = Modifier.fillMaxWidth(),
    )
    hapticTest?.let { Line(it) }
    SecondaryText("After unlocking, tap every count you felt (tap again to take it back):")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        kinds.indices.forEach { index ->
            val group = index + 1
            val selected = group in felt
            ChoiceButton(if (selected) "$group ✓" else "$group", selected, Modifier.weight(1f)) {
                viewModel.toggleFelt(group)
            }
        }
    }
    Line(if (felt.isEmpty()) "Felt: nothing marked" else "Felt: ${felt.sorted().joinToString()}")
}

private val HapticKind.label: String
    get() = when (this) {
        HapticKind.CORE_HAPTICS -> "Core Haptics"
        HapticKind.CORE_HAPTICS_AUDIO -> "Core Haptics, audio session"
        HapticKind.IMPACT -> "impact"
        HapticKind.NOTIFY_SILENT_SOUND -> "notification, silent sound"
        HapticKind.NOTIFY_NO_SOUND -> "notification, no sound"
        HapticKind.VIBRATOR -> "vibration motor"
    }

@Composable
private fun ScreenAndPulseCard(viewModel: LabViewModel) {
    val screenOff by viewModel.screenOff.collectAsStateWithLifecycle()
    val pulse by viewModel.pulse.collectAsStateWithLifecycle()
    Section("Screen and pulse") {
        if (viewModel.canTurnScreenOff) {
            BenchSwitch(
                text = "Screen off by the proximity sensor",
                checked = screenOff,
                enabled = true,
                onCheckedChange = viewModel::setScreenOff,
            )
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
    val lastMark by viewModel.lastMark.collectAsStateWithLifecycle()
    Section("Marks") {
        Line("Last mark: ${lastMark ?: "none yet"}")
        TouchRow(viewModel)
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

/**
 * «Touched with …» (docs/adr/0017-radar-techniques-and-big-run.md §3, the touch calibration): knock this phone back to
 * back with another one, then press that one's label at once. In a server run a button per other device of the run;
 * outside one, the other device's label typed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TouchRow(viewModel: LabViewModel) {
    val follow by viewModel.follow.collectAsStateWithLifecycle()
    val own by viewModel.label.collectAsStateWithLifecycle()
    val others = viewModel.touchLabels(follow)
    SecondaryText("Touched with … (back to back, one knock, then press at once)")
    if (others != null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            others.forEach { other -> ChoiceButton(other, false) { viewModel.touched(other) } }
        }
    } else {
        var other by remember { mutableStateOf("") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PopTextField(
                value = other,
                onValueChange = { other = it.trim().take(LABEL_MAX_LENGTH) },
                singleLine = true,
                label = { Text("Other label") },
                modifier = Modifier.weight(1f),
            )
            PopButton(
                text = "Touched",
                onClick = { viewModel.touched(other) },
                enabled = other.isNotBlank() && other != own,
                height = 44.dp,
                style = PopStyle.Outline,
            )
        }
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
private const val LABEL_MAX_LENGTH = 12
private val SCANNER_HEIGHT = 320.dp
