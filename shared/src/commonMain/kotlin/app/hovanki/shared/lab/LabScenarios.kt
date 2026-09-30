package app.hovanki.shared.lab

import app.hovanki.shared.protocol.LabScenarioSummary
import app.hovanki.shared.protocol.LabStepView

/** The places and actions the marks and the scenarios use: the merge's truth for the pocket (docs/radio-lab.md §7). */
object LabPlaces {
    const val HAND = "hand"
    const val HAND_LOCKED = "hand_locked"
    const val POCKET_FRONT = "pocket_front"
    const val POCKET_BACK = "pocket_back"
    const val JACKET = "jacket"
    const val BACKPACK = "backpack"
    const val TABLE_UP = "table_up"
    const val TABLE_DOWN = "table_down"
    const val POCKET_PROXIMITY = "pocket_proximity"

    val ALL = listOf(HAND, HAND_LOCKED, POCKET_FRONT, POCKET_BACK, JACKET, BACKPACK, TABLE_UP, TABLE_DOWN)

    /** Places that are «in the pocket» for the carry classifier's truth. */
    val CARRIED_HIDDEN = setOf(POCKET_FRONT, POCKET_BACK, JACKET, BACKPACK, POCKET_PROXIMITY)

    const val STAND = "stand"
    const val WALK = "walk"
    const val SIT = "sit"
    const val RUN = "run"
    const val LIE = "lie"

    val ACTIONS = listOf(STAND, WALK, SIT, RUN)

    val DISTANCES = listOf(0.5, 1.0, 3.0, 5.0, 10.0, 20.0, 40.0)
}

/** Where the automatic run is: the screen on, the moment to lock the phone, the phone locked. */
enum class RunPhase { SCREEN, LOCK, LOCKED }

/** What the overflow probe advertises (docs/radio-lab.md §5). */
sealed interface ProbeMode {
    /** `OverflowProbe.PATTERN`. */
    data object Pattern : ProbeMode

    /** Only bit [bit]. */
    data class Bit(val bit: Int) : ProbeMode

    /** A token, Manchester-coded as in ADR 0016 §2.1 (`OverflowCode`). */
    data object Token : ProbeMode
}

/**
 * What a device does in a step. [hider]: the game's radio as a hider with the run's token (it also scans: the game's
 * service by CoreBluetooth, the seekers' iBeacon by ranging); on the Mac, advertise as a hider. [seeker]: advertise the
 * seeker's iBeacon frame instead. [probe]: the overflow probe. [rotateToken]: the probe's token changes when the step
 * starts. [listen]: listen to everything. [screenOff]: the screen off by the proximity sensor. [pulse]: the lab's pulse
 * by haptics. [inGame]: «as in a game», GPS in the background as in a round. [phase]: whether the phone is to be
 * locked. The vibration test is a test of its own, not a step.
 *
 * [techniques]: the radar's channels the step runs, by id (docs/adr/0017-radar-techniques-and-big-run.md, section
 * 2.1: `ble.name`, `ble.service_data.bare`…), instead of the game's; the radio runs as a hider unless [seeker]. Empty:
 * as the switches say. The ids are not checked here (the catalog is in `:radar`): the phone notes one it doesn't
 * know and leaves it out. Part of the advertisement: a locked step keeps its lock step's.
 */
data class PhoneSetup(
    val hider: Boolean = false,
    val seeker: Boolean = false,
    val probe: ProbeMode? = null,
    val rotateToken: Boolean = false,
    val listen: Boolean = false,
    val screenOff: Boolean = false,
    val pulse: Boolean = false,
    val inGame: Boolean = true,
    val phase: RunPhase = RunPhase.SCREEN,
    val techniques: Set<String> = emptySet(),
) {
    /** The part a locked phone can't change: its advertisement. */
    internal val advertisement: List<Any?> get() = listOf(hider, seeker, probe, techniques)

    /** In a few words, for the console: `hider · probe token · listen`. */
    fun describe(): String = listOfNotNull(
        "hider".takeIf { hider },
        "seeker's iBeacon".takeIf { seeker },
        techniques.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(", ", prefix = "channels "),
        probe?.let {
            when (it) {
                ProbeMode.Pattern -> "probe 0x5A"
                is ProbeMode.Bit -> "probe bit ${it.bit}"
                ProbeMode.Token -> "probe token"
            }
        },
        "new probe token".takeIf { rotateToken },
        "listen".takeIf { listen },
        "screen off".takeIf { screenOff },
        "pulse".takeIf { pulse },
        "lock now".takeIf { phase == RunPhase.LOCK },
        "locked".takeIf { phase == RunPhase.LOCKED },
    ).joinToString(" · ").ifEmpty { "quiet" }
}

/** One label's part in a step: its [setup], what the tester does ([hint]) and the truth for the merge ([place]…). */
data class DeviceStep(
    val setup: PhoneSetup = PhoneSetup(),
    val hint: String = "",
    val place: String? = null,
    val action: String? = null,
)

/**
 * A step of a run: every label's part ([devices], by label; a label not named does nothing). [seconds]: how long it
 * lasts, null: until somebody presses «Next». [distances]: how far apart the devices are, meters, by the pair's key
 * ([pairKey]).
 */
data class RunStep(
    val id: String,
    val title: String,
    val seconds: Int?,
    val devices: Map<String, DeviceStep>,
    val hint: String = "",
    val distances: Map<String, Double> = emptyMap(),
) {
    companion object {
        /** `A|B`: the two labels sorted. */
        fun pairKey(a: String, b: String): String = if (a <= b) "$a|$b" else "$b|$a"
    }
}

/**
 * A plan of the radio lab (docs/radio-lab-tests.md, docs/adr/0017-radar-techniques-and-big-run.md §5): steps the
 * devices of the run all follow by the server's clock from the same start, each label with its own setup. A phone
 * can't change its advertisement in the background, so a label's locked steps keep what its lock step set; they
 * change only what the others send and what the phone does besides advertising. [version]: one hex digit, the Mac
 * learns it from the phone's announcement ([LabRunScripts.of]).
 */
class LabRunScript(
    val id: String,
    val version: Int,
    val title: String,
    val labels: List<String>,
    val steps: List<RunStep>,
) {
    init {
        require(version in 0..15) { "a script's version is one hex digit" }
        require(steps.isNotEmpty()) { "a script has steps" }
        require(labels.isNotEmpty() && labels.distinct() == labels) { "labels are named once" }
        for (step in steps) {
            require(step.devices.keys.all { it in labels }) { "${step.id}: a label not in $labels" }
            require(step.seconds == null || step.seconds > 0) { "${step.id}: a step lasts" }
        }
        require(steps.map { it.id }.distinct().size == steps.size) { "step ids are unique" }
        for (label in labels) {
            var lock: PhoneSetup? = null
            for (index in steps.indices) {
                val setup = setupOf(label, index)
                when (setup.phase) {
                    RunPhase.LOCK -> lock = setup

                    RunPhase.LOCKED -> require(lock != null && setup.advertisement == lock.advertisement) {
                        "$label, ${steps[index].id}: a locked step can't change the advertisement"
                    }

                    RunPhase.SCREEN -> lock = null
                }
            }
        }
    }

    /** Every step has a timer: the run goes by itself from the start to the end. */
    val isTimed: Boolean = steps.all { it.seconds != null }

    private val timedTotal: Long? = if (isTimed) steps.sumOf { it.seconds!! * 1000L } else null

    val totalMillis: Long get() = requireNotNull(timedTotal) { "$id: not a timed script" }

    fun startOf(index: Int): Long {
        require(isTimed) { "$id: not a timed script" }
        return steps.take(index).sumOf { it.seconds!! * 1000L }
    }

    /** The step at [elapsedMillis] since the start; null before it or after the end. Timed scripts only. */
    fun at(elapsedMillis: Long): IndexedValue<RunStep>? {
        require(isTimed) { "$id: not a timed script" }
        if (elapsedMillis < 0) return null
        var end = 0L
        for ((index, step) in steps.withIndex()) {
            end += step.seconds!! * 1000L
            if (elapsedMillis < end) return IndexedValue(index, step)
        }
        return null
    }

    /** What [label] does in the step [index]; nothing when the step doesn't name it. */
    fun setupOf(label: String, index: Int): PhoneSetup = steps[index].devices[label]?.setup ?: PhoneSetup()

    fun summary(): LabScenarioSummary = LabScenarioSummary(
        id = id,
        version = version,
        title = title,
        labels = labels,
        steps = steps.size,
        totalSeconds = timedTotal?.let { (it / 1000).toInt() },
    )

    /** The steps for the console: every label's setup in words, and its hint. */
    fun stepViews(): List<LabStepView> = steps.mapIndexed { index, step ->
        LabStepView(
            index = index,
            id = step.id,
            title = step.title,
            seconds = step.seconds,
            hints = labels.associateWith { label ->
                val device = step.devices[label]
                listOf(setupOf(label, index).describe(), device?.hint.orEmpty())
                    .filter { it.isNotEmpty() }
                    .joinToString(": ")
            },
        )
    }

    override fun toString(): String = "LabRunScript($id v$version)"
}

/** The plans built into the app and the server: the server sends only a plan's id and version. */
object LabRunScripts {
    /** What the Mac advertises as a hider (the game's service, the token as the name) in the local run. */
    const val MAC_HIDER_TOKEN = "cafe0001"

    /** What the Mac advertises as a seeker's iBeacon in the local run. */
    const val MAC_BEACON_TOKEN = "cafe0002"

    private const val PHONE = "A"
    private const val MAC = "mac"

    private fun phone(setup: PhoneSetup) = DeviceStep(setup, place = LabPlaces.TABLE_UP, action = LabPlaces.LIE)

    private fun mac(hider: Boolean = false, seeker: Boolean = false) =
        DeviceStep(PhoneSetup(hider = hider, seeker = seeker), place = LabPlaces.TABLE_UP, action = LabPlaces.LIE)

    private fun radioStep(id: String, title: String, seconds: Int, phone: PhoneSetup, mac: DeviceStep, hint: String) =
        RunStep(
            id = id,
            title = title,
            seconds = seconds,
            devices = mapOf(PHONE to phone(phone), MAC to mac),
            hint = hint,
            distances = mapOf(RunStep.pairKey(PHONE, MAC) to 1.0),
        )

    private val pocket = PhoneSetup(hider = true, probe = ProbeMode.Token)

    /** Version 2: 9 minutes of Bluetooth only, the phone (`A`) and the Mac (`mac`) on the table 1 m apart. */
    val RADIO = LabRunScript(
        id = "radio",
        version = 2,
        title = "Radio: the phone and the Mac, 9 min",
        labels = listOf(PHONE, MAC),
        steps = listOf(
            radioStep(
                "baseline",
                "Both advertise as hiders",
                60,
                PhoneSetup(hider = true),
                mac(hider = true),
                "Keep the screen on. Each hears the other's name: the RSSI both ways, on the table.",
            ),
            radioStep(
                "mask_pattern",
                "Overflow mask: 0x5A",
                40,
                PhoneSetup(probe = ProbeMode.Pattern),
                mac(),
                "The Mac should hear bits 1 3 4 6 9 11 12 14.",
            ),
            radioStep(
                "mask_token",
                "Overflow mask: a token",
                40,
                PhoneSetup(probe = ProbeMode.Token),
                mac(),
                "The Mac should decode the probe's token.",
            ),
            radioStep(
                "ibeacon_screen",
                "The Mac as a seeker's iBeacon",
                60,
                PhoneSetup(hider = true),
                mac(seeker = true),
                "Does the phone hear cafe0002 by ranging, on the screen?",
            ),
            radioStep(
                "lock",
                "Lock the phone now",
                45,
                pocket.copy(phase = RunPhase.LOCK),
                mac(seeker = true),
                "Press the side button and leave the phone on the table until it says the run is over.",
            ),
            radioStep(
                "ibeacon_locked",
                "iBeacon while locked",
                120,
                pocket.copy(phase = RunPhase.LOCKED),
                mac(seeker = true),
                "Does ranging go on with the phone locked (H1)? The Mac hears the frozen mask.",
            ),
            radioStep(
                "token_rotates",
                "The token changes in the background",
                90,
                pocket.copy(rotateToken = true, phase = RunPhase.LOCKED),
                mac(),
                "iOS keeps the old advertisement: the Mac should keep decoding the old token (H5).",
            ),
            radioStep(
                "mac_hider_locked",
                "The Mac as a hider while locked",
                90,
                pocket.copy(phase = RunPhase.LOCKED),
                mac(hider = true),
                "Does the locked phone's CoreBluetooth scan hear the Mac's name?",
            ),
        ),
    )

    private val E2E_LABELS = listOf("A", "B", "droid")

    private fun inHand(setup: PhoneSetup) = DeviceStep(setup, place = LabPlaces.HAND, action = LabPlaces.STAND)

    /** Version 3: three phones in the hand 2 m apart, 22 s; the e2e bots' run (`LabRunTest`). */
    val E2E = LabRunScript(
        id = "e2e",
        version = 3,
        title = "E2E: three phones, 22 s",
        labels = E2E_LABELS,
        steps = listOf(
            RunStep(
                "all_hiders",
                "Everybody advertises as a hider",
                8,
                E2E_LABELS.associateWith { inHand(PhoneSetup(hider = true)) },
                hint = "Every phone hears every other.",
            ),
            RunStep(
                "probe",
                "A's overflow probe",
                6,
                mapOf(
                    "A" to inHand(PhoneSetup(hider = true, probe = ProbeMode.Token)),
                    "B" to inHand(PhoneSetup(hider = true, listen = true)),
                    "droid" to inHand(PhoneSetup(hider = true, listen = true)),
                ),
                hint = "B and droid listen to everything.",
            ),
            RunStep(
                "all_again",
                "Everybody advertises again",
                8,
                E2E_LABELS.associateWith { inHand(PhoneSetup(hider = true)) },
            ),
        ),
    )

    // The big run (docs/adr/0017-radar-techniques-and-big-run.md §6, docs/radio-lab-tests.md «Большой прогон»).

    private const val A = "A"
    private const val B = "B"
    private const val DROID = "droid"
    private val BIG_LABELS = listOf(A, B, DROID, MAC)

    /**
     * The techniques the big run names, by the catalog's ids (ADR 0017 §2.3). The channels are `:radar`'s
     * `RadarCatalog`; the rest the phone notes as unknown and leaves out (`LabController.setTechniques`) until their
     * code comes: the ids of step 5 of docs/radar-run.md (the audio session, the wake by notifications, Core Haptics
     * with the audio session, GATT, UWB with its Live Activity) are named now, so the run is planned once. The lab's
     * pulse is [PhoneSetup.pulse] (Core Haptics on an iPhone): its id is only the report's name for it.
     */
    private const val SCAN_RESPONSE = "ble.service_data.scan_response"
    private const val BARE = "ble.service_data.bare"
    private const val MFR = "ble.service_data.mfr"
    private const val NAME = "ble.name"
    private const val IBEACON = "ble.ibeacon"
    private const val REGION = "ble.ibeacon.region"
    private const val OVERFLOW = "ble.overflow"
    private const val PULSE_NOTIFY = "pulse.notify_silent_sound"
    private const val PULSE_LIVE_ACTIVITY = "pulse.live_activity"
    private const val PULSE_LIVE_ACTIVITY_DOUBLE = "pulse.live_activity.double"
    private const val MODE_NOTIFICATION_WAKE = "mode.notification_wake"
    private const val MODE_LIVE_ACTIVITY = "mode.live_activity"
    private const val GATT = "gatt.link"
    private const val UWB = "uwb.ni"

    /** The game's channels (`RadarCatalog.game`): each phone sends what its platform can and hears them all. */
    private val GAME = setOf(SCAN_RESPONSE, NAME, IBEACON, REGION)

    /** A phone carried in the run: the game's channels and the mask, which is all a locked iPhone can send. */
    private val CARRIED = GAME + OVERFLOW

    /**
     * Every channel at once: an iPhone sends the name and the mask of it and hears all three Android layouts. Not for
     * the Android as a sender: its three layouts don't fit into one 31-byte advertisement together.
     */
    private val CHANNELS = CARRIED + BARE + MFR

    /** Block 4: UWB in the background needs its Live Activity (ADR 0017 §2.3). */
    private val UWB_LIVE = setOf(UWB, MODE_LIVE_ACTIVITY)

    /** Block 7: every candidate at once; of the competing pulses the phone plays the first that works. */
    private val EVERY_CANDIDATE = CARRIED + GATT + UWB_LIVE + MODE_NOTIFICATION_WAKE +
        PULSE_LIVE_ACTIVITY_DOUBLE + PULSE_LIVE_ACTIVITY + PULSE_NOTIFY

    private val quiet = PhoneSetup()
    private val listening = PhoneSetup(listen = true)

    private fun hider(
        techniques: Set<String> = emptySet(),
        phase: RunPhase = RunPhase.SCREEN,
        pulse: Boolean = false,
    ) = PhoneSetup(hider = true, techniques = techniques, phase = phase, pulse = pulse)

    private fun seeker(techniques: Set<String> = emptySet(), phase: RunPhase = RunPhase.SCREEN) =
        PhoneSetup(seeker = true, techniques = techniques, phase = phase)

    private fun at(setup: PhoneSetup, place: String, action: String, hint: String) =
        DeviceStep(setup, hint, place, action)

    private fun onTable(setup: PhoneSetup, hint: String) = at(setup, LabPlaces.TABLE_UP, LabPlaces.LIE, hint)

    private fun bigStep(
        id: String,
        title: String,
        seconds: Int,
        hint: String,
        distances: Map<String, Double>,
        a: DeviceStep,
        b: DeviceStep,
        droid: DeviceStep,
        mac: DeviceStep? = null,
    ) = RunStep(
        id = id,
        title = title,
        seconds = seconds,
        devices = listOfNotNull(A to a, B to b, DROID to droid, mac?.let { MAC to it }).toMap(),
        hint = hint,
        distances = distances,
    )

    /** The three phones' pairs, metres, and the Mac's where it lies still ([mac]: a label to its distance). */
    private fun apart(ab: Double, aDroid: Double, bDroid: Double, vararg mac: Pair<String, Double>) = mapOf(
        RunStep.pairKey(A, B) to ab,
        RunStep.pairKey(A, DROID) to aDroid,
        RunStep.pairKey(B, DROID) to bDroid,
    ) + mac.associate { (label, meters) -> RunStep.pairKey(label, MAC) to meters }

    private fun metres(meters: Double): String = if (meters % 1.0 == 0.0) "${meters.toInt()} m" else "$meters m"

    /** Block 0, 10 min: everybody in, the clocks, the permissions; the admin moves on once the live view is green. */
    private fun preparation(): List<RunStep> {
        val iPhone = "Charge ≥ 60 %, Low Power Mode and Focus off, Bluetooth on in Settings, location «Always», " +
            "notifications on. Stay on the Lab screen."
        return listOf(
            bigStep(
                "b0_prepare",
                "Block 0: preparation",
                600,
                "Everybody joined; the live view is all green: every device uploads, its clock offset is known, " +
                    "Bluetooth is on. Press «Next» once it is; red: fix it now, while everybody is here.",
                emptyMap(),
                onTable(quiet, iPhone),
                onTable(quiet, iPhone),
                onTable(
                    quiet,
                    "Charge ≥ 60 %, battery saver off, the app not battery-optimised, location, «Nearby devices» " +
                        "and notifications allowed. Stay on the Lab screen.",
                ),
                onTable(quiet, "run.sh --lab --run <code>, the label mac; caffeinate keeps it awake."),
            ),
        )
    }

    /**
     * A touch of a pair (ADR 0017 §3), one per step: the button's marks of a pair within 2 s are one touch, so the
     * three touches of block 1 are three steps. No distances: the pair moves in and out of the touch, and the touch
     * marks are the truth here.
     */
    private fun touch(id: String, first: String, second: String, how: String, seconds: Int): RunStep {
        val phones = listOf(A, B, DROID)
        val third = phones.single { it != first && it != second }
        fun toucher(other: String) = at(
            hider(),
            LabPlaces.HAND,
            LabPlaces.STAND,
            "Touch $how with $other once, firmly, then press «Touched with $other».",
        )
        val devices = mapOf(
            first to toucher(second),
            second to toucher(first),
            third to onTable(hider(), "Lie still on the table, 2 m away."),
        )
        return bigStep(
            id,
            "Touch: $first and $second, $how",
            seconds,
            "$first and $second: touch $how once; press «Touched with …» on both right after. $third lies still.",
            emptyMap(),
            devices.getValue(A),
            devices.getValue(B),
            devices.getValue(DROID),
        )
    }

    private val PAIRS = listOf(A to B, A to DROID, B to DROID)

    /** Block 1, 5 min: every pair three times, backs, edges, backs (`calib.touch`: repeatability, orientation). */
    private fun touches(): List<RunStep> = PAIRS.flatMap { (first, second) ->
        listOf("backs", "edges", "backs").mapIndexed { index, how ->
            touch("b1_touch_${first}_${second}_${index + 1}", first, second, how, 33)
        }
    }

    /** Block 8, 3 min: every pair once more, backs: the calibration's drift. */
    private fun touchesAgain(): List<RunStep> =
        PAIRS.map { (first, second) -> touch("b8_touch_${first}_$second", first, second, "backs", 60) }

    /**
     * Block 2, 20 min: the table, the triangle of 1 m, the Mac in its middle. Each channel alone, then all of them;
     * the overflow probe on screen and locked (H4, H5); both iPhones locked with droid's iBeacon for ranging (H1) and,
     * after a pause, the region; droid's three layouts heard by locked iPhones (H7); droid locked as a listener. An
     * iPhone is unlocked by a screen step that keeps its advertisement: it can't change one while locked.
     */
    private fun table(): List<RunStep> {
        val triangle = apart(1.0, 1.0, 1.0, A to 0.6, B to 0.6, DROID to 0.6)
        val listen = "Listen to everything, screen on."
        val macListens = onTable(listening, "Listen.")
        fun step(
            id: String,
            title: String,
            seconds: Int,
            hint: String,
            a: DeviceStep,
            b: DeviceStep,
            droid: DeviceStep,
        ) = bigStep(id, title, seconds, hint, triangle, a, b, droid, macListens)

        val layouts = listOf(
            SCAN_RESPONSE to "the token in the scan response",
            BARE to "service data without the UUID list",
            MFR to "the manufacturer's data",
        )
        val probeA = PhoneSetup(probe = ProbeMode.Token)
        val lockedBoth = hider(CHANNELS)
        return buildList {
            add(
                bigStep(
                    "b2_baseline",
                    "Table: everybody as a hider",
                    45,
                    "The three phones in a triangle of 1 m on the table, screens up, the Mac in the middle.",
                    triangle,
                    onTable(hider(), "Triangle of 1 m, screen up. Everybody hears everybody."),
                    onTable(hider(), "Triangle of 1 m, screen up. Everybody hears everybody."),
                    onTable(hider(), "Triangle of 1 m, screen up. Everybody hears everybody."),
                    onTable(PhoneSetup(hider = true, listen = true), "In the middle of the triangle, advertise."),
                ),
            )
            for ((layout, what) in layouts) {
                val short = layout.substringAfterLast('.')
                add(
                    step(
                        "b2_droid_$short",
                        "Table: droid's layout $short",
                        70,
                        "droid advertises $what alone; A, B and the Mac listen: who hears it on screen?",
                        onTable(listening, listen),
                        onTable(listening, listen),
                        onTable(hider(setOf(layout)), "Advertise $what ($short), screen on."),
                    ),
                )
            }
            add(
                step(
                    "b2_iphones_name",
                    "Table: the iPhones' names",
                    70,
                    "A and B advertise the token as the name; droid and the Mac listen.",
                    onTable(hider(setOf(NAME)), "Advertise the name, screen on."),
                    onTable(hider(setOf(NAME)), "Advertise the name, screen on."),
                    onTable(listening, listen),
                ),
            )
            add(
                step(
                    "b2_all_channels",
                    "Table: every channel at once",
                    75,
                    "A and B run every channel, droid the game's and the mask: does one channel drown another?",
                    onTable(hider(CHANNELS), "Every channel, screen on."),
                    onTable(hider(CHANNELS), "Every channel, screen on."),
                    onTable(hider(CARRIED), "The game's channels, screen on."),
                ),
            )
            add(
                step(
                    "b2_probe_screen",
                    "Table: A's overflow probe on screen",
                    60,
                    "A's probe sends a token in the overflow mask; B lists the bits on screen, droid reads them raw.",
                    onTable(probeA, "The overflow probe, screen on."),
                    onTable(listening, "Listen, screen on: the mask's bits come as UUIDs."),
                    onTable(listening, "Listen: the mask comes raw."),
                ),
            )
            add(
                step(
                    "b2_a_lock",
                    "Table: lock A",
                    45,
                    "Lock A and leave it on the table; B and droid keep listening.",
                    onTable(probeA.copy(phase = RunPhase.LOCK), "Lock the phone, leave it on the table."),
                    onTable(listening, listen),
                    onTable(listening, listen),
                ),
            )
            add(
                step(
                    "b2_a_mask_locked",
                    "Table: A's mask while locked",
                    90,
                    "H4: B and droid should decode A's token from the mask of a locked iPhone.",
                    onTable(probeA.copy(phase = RunPhase.LOCKED), "Stay locked."),
                    onTable(listening, listen),
                    onTable(listening, listen),
                ),
            )
            add(
                step(
                    "b2_a_token_rotates",
                    "Table: A's token changes while locked",
                    75,
                    "H5: the probe's token changes now; iOS keeps the old mask until A is unlocked.",
                    onTable(probeA.copy(rotateToken = true, phase = RunPhase.LOCKED), "Stay locked."),
                    onTable(listening, listen),
                    onTable(listening, listen),
                ),
            )
            add(
                step(
                    "b2_a_unlock",
                    "Table: unlock A",
                    20,
                    "Unlock A: its mask should turn to the new token.",
                    onTable(probeA, "Unlock the phone, leave it on the table."),
                    onTable(listening, listen),
                    onTable(listening, listen),
                ),
            )
            add(
                step(
                    "b2_iphones_lock",
                    "Table: lock A and B, droid's iBeacon on",
                    45,
                    "droid starts the seeker's iBeacon; lock A and B once the phones say so.",
                    onTable(lockedBoth.copy(phase = RunPhase.LOCK), "Lock the phone, leave it on the table."),
                    onTable(lockedBoth.copy(phase = RunPhase.LOCK), "Lock the phone, leave it on the table."),
                    onTable(seeker(setOf(IBEACON)).copy(listen = true), "The seeker's iBeacon, screen on."),
                ),
            )
            add(
                step(
                    "b2_ranging_locked",
                    "Table: ranging on the locked iPhones",
                    120,
                    "H1: do A and B keep ranging droid's iBeacon longer than 60 s after the lock?",
                    onTable(lockedBoth.copy(phase = RunPhase.LOCKED), "Stay locked."),
                    onTable(lockedBoth.copy(phase = RunPhase.LOCKED), "Stay locked."),
                    onTable(seeker(setOf(IBEACON)).copy(listen = true), "The seeker's iBeacon, screen on."),
                ),
            )
            for ((layout, what) in layouts) {
                val short = layout.substringAfterLast('.')
                add(
                    step(
                        "b2_locked_$short",
                        "Table: locked iPhones hear droid's $short",
                        45,
                        "droid's iBeacon is off (the region empties for the next step); it advertises $what: " +
                            "do the locked iPhones hear it?",
                        onTable(lockedBoth.copy(phase = RunPhase.LOCKED), "Stay locked."),
                        onTable(lockedBoth.copy(phase = RunPhase.LOCKED), "Stay locked."),
                        onTable(hider(setOf(layout)).copy(listen = true), "Advertise $what ($short), screen on."),
                    ),
                )
            }
            add(
                step(
                    "b2_region_locked",
                    "Table: the region wakes the locked iPhones",
                    90,
                    "droid's iBeacon comes back: the locked iPhones should enter its region within 30 s.",
                    onTable(lockedBoth.copy(phase = RunPhase.LOCKED), "Stay locked."),
                    onTable(lockedBoth.copy(phase = RunPhase.LOCKED), "Stay locked."),
                    onTable(seeker(setOf(IBEACON)).copy(listen = true), "The seeker's iBeacon, screen on."),
                ),
            )
            add(
                step(
                    "b2_droid_lock",
                    "Table: unlock A and B, lock droid",
                    45,
                    "Unlock A and B; turn droid's screen off with the power button.",
                    onTable(lockedBoth, "Unlock the phone, leave it on the table."),
                    onTable(lockedBoth, "Unlock the phone, leave it on the table."),
                    onTable(listening.copy(phase = RunPhase.LOCK), "Screen off with the power button, on the table."),
                ),
            )
            add(
                step(
                    "b2_droid_locked",
                    "Table: droid listens with the screen off",
                    60,
                    "Does droid's scan with the screen off still hear the iPhones' names and masks?",
                    onTable(lockedBoth, "Screen on, on the table."),
                    onTable(lockedBoth, "Screen on, on the table."),
                    onTable(listening.copy(phase = RunPhase.LOCKED), "Stay off."),
                ),
            )
        }
    }

    /**
     * Block 3, 15 min: vibration and modes (H2, H3). The iPhones stand at the table 1 m from droid, whose iBeacon is
     * «burning» for the lab's pulse. Each way a locked iPhone feels, the best first (docs/radio-lab.md §12: two Live
     * Activity alerts 300 ms apart won; Core Haptics, impact, the soundless notification and the silent ringtone
     * never reached the pocket, so they are not run): a locked step with the pulse, then a
     * screen step with the lab's vibration test, the pulse off so the test's strikes can be counted. The proximity
     * sensor's dark screen is [PhoneSetup.screenOff] (`mode.proximity_screen`), not an id.
     */
    private fun vibration(): List<RunStep> {
        val near = apart(1.0, 1.0, 1.0)
        val beacon = onTable(seeker(CARRIED).copy(listen = true), "The seeker's iBeacon on the table, screen on.")
        val notify = GAME + PULSE_NOTIFY
        val live = GAME + MODE_LIVE_ACTIVITY + PULSE_LIVE_ACTIVITY
        val liveDouble = GAME + MODE_LIVE_ACTIVITY + PULSE_LIVE_ACTIVITY_DOUBLE
        val wake = CARRIED + MODE_NOTIFICATION_WAKE
        val test = "Unlock, start the vibration test (Lab → Vibration test: the lab's own test, not this step), " +
            "lock within 15 s and pocket the phone; after «Vibration test over» unlock and mark what you felt in every group."

        fun both(id: String, title: String, seconds: Int, hint: String, iPhone: DeviceStep) =
            bigStep(id, title, seconds, hint, near, iPhone, iPhone, beacon)

        fun variant(id: String, what: String, techniques: Set<String>): List<RunStep> = listOf(
            both(
                "b3_${id}_pulse",
                "Vibration: $what, the pulse in the pocket",
                60,
                "A and B locked in the front pocket, 1 m from droid: the pulse should beat by $what.",
                at(
                    hider(techniques, RunPhase.LOCK, pulse = true),
                    LabPlaces.POCKET_FRONT,
                    LabPlaces.STAND,
                    "Lock, front pocket, stand 1 m from droid. Does the pulse beat?",
                ),
            ),
            both(
                "b3_${id}_test",
                "Vibration: $what, the vibration test",
                90,
                "The vibration test by $what in the pocket; mark what you felt in every group afterwards.",
                at(hider(techniques), LabPlaces.POCKET_FRONT, LabPlaces.STAND, test),
            ),
        )

        return buildList {
            add(
                both(
                    "b3_setup",
                    "Vibration: the pulse on screen",
                    60,
                    "A and B in the hand at the table, 1 m from droid and from each other: the pulse beats on screen.",
                    at(hider(notify, pulse = true), LabPlaces.HAND, LabPlaces.STAND, "In the hand: feel the pulse."),
                ),
            )
            addAll(variant("live_double", "two Live Activity alerts", liveDouble))
            addAll(variant("live_activity", "the Live Activity's alert", live))
            addAll(variant("notify", "a silent-sound notification", notify))
            add(
                both(
                    "b3_proximity",
                    "Vibration: the screen off by the proximity sensor",
                    180,
                    "H3: A and B unlocked in the pocket, the screen dark by the proximity sensor: no lock, the " +
                        "name and the pulse go on.",
                    at(
                        hider(notify, pulse = true).copy(screenOff = true),
                        LabPlaces.POCKET_PROXIMITY,
                        LabPlaces.STAND,
                        "Don't lock: pocket the phone screen to the leg, the sensor darkens it. Does the pulse beat?",
                    ),
                ),
            )
            add(
                both(
                    "b3_notification_wake",
                    "Modes: the notifications' wake",
                    150,
                    "A and B locked in the pocket get a notification every few seconds: do droid's masks and the " +
                        "iPhones' ranging come within 10 s of each?",
                    at(
                        hider(wake, RunPhase.LOCK, pulse = true),
                        LabPlaces.POCKET_FRONT,
                        LabPlaces.STAND,
                        "Lock, front pocket, stand 1 m from droid. Ignore the notifications.",
                    ),
                ),
            )
            add(
                both(
                    "b3_unlock",
                    "Vibration: unlock",
                    20,
                    "Take A and B out and unlock them; the next block starts with both on screen.",
                    at(hider(wake), LabPlaces.HAND, LabPlaces.STAND, "Take the phone out and unlock it."),
                ),
            )
        }
    }

    /**
     * Block 4, 20 min: two iPhones locked in the pockets, GATT, UWB (with its Live Activity) and both, the pair 1, 5,
     * 15, 5, 1 m apart with droid the witness lying halfway (`infer.witness`). The truth is per step, so the pair's
     * converging and diverging is steps at fixed distances; between the techniques the phones are unlocked by a step
     * that keeps the advertisement.
     */
    private fun twoPockets(): List<RunStep> {
        val variants = listOf(
            Triple("gatt", "GATT", CARRIED + GATT),
            Triple("uwb", "UWB with the Live Activity", CARRIED + UWB_LIVE),
            Triple("both", "GATT and UWB", CARRIED + GATT + UWB_LIVE),
        )
        val witness = onTable(
            seeker(CARRIED).copy(listen = true),
            "The witness: lie halfway between A and B, screen on.",
        )
        return variants.flatMap { (id, what, techniques) ->
            val legs = listOf(1.0 to 60, 5.0 to 75, 15.0 to 110, 5.0 to 75, 1.0 to 60)
            legs.mapIndexed { index, (meters, seconds) ->
                val lock = index == 0
                val back = index > legs.size / 2
                val iPhone = at(
                    hider(techniques, if (lock) RunPhase.LOCK else RunPhase.LOCKED),
                    LabPlaces.POCKET_FRONT,
                    LabPlaces.STAND,
                    if (lock) {
                        "On screen first ($what starts), then lock, front pocket; ${metres(meters)} from the other " +
                            "iPhone, droid halfway."
                    } else {
                        "Locked in the pocket: walk to ${metres(meters)} from the other iPhone, droid halfway, stand."
                    },
                )
                bigStep(
                    "b4_${id}_${metres(meters).replace(" ", "")}" + if (back) "_back" else "",
                    "Two pockets, $what: ${metres(meters)}",
                    seconds,
                    "A and B locked in the pockets ${metres(meters)} apart, droid on the ground halfway: does $what " +
                        "keep the pair, and does droid witness it?",
                    apart(meters, meters / 2, meters / 2),
                    iPhone,
                    iPhone,
                    witness,
                )
            } + bigStep(
                "b4_${id}_unlock",
                "Two pockets, $what: unlock",
                20,
                "Take A and B out and unlock them, 1 m apart.",
                apart(1.0, 0.5, 0.5),
                at(hider(techniques), LabPlaces.HAND, LabPlaces.STAND, "Take the phone out and unlock it."),
                at(hider(techniques), LabPlaces.HAND, LabPlaces.STAND, "Take the phone out and unlock it."),
                witness,
            )
        }
    }

    /**
     * Block 5, 15 min: the distances (H6, the bands' thresholds, the smoothings). Two passes of 1–40 m in the hand,
     * then locked in the pocket back from 40 m: the first person stands still by the Mac with A (and B in the second
     * pass), the second walks with B and droid (droid only in the second pass), so every pair gets every distance.
     * droid is a seeker in the first pass and a hider in the second: the iPhones' ranging and their scan by distance.
     */
    private fun distances(): List<RunStep> {
        val steps = DISTANCES_OF_THE_RUN
        fun pass(number: Int, droidSetup: PhoneSetup): List<RunStep> {
            val bWalks = number == 1
            fun step(meters: Double, phase: RunPhase, seconds: Int): RunStep {
                val pocket = phase != RunPhase.SCREEN
                val place = if (pocket) LabPlaces.POCKET_FRONT else LabPlaces.HAND
                val lockHint = when (phase) {
                    RunPhase.SCREEN -> "in the hand"
                    RunPhase.LOCK -> "lock and pocket the phone now"
                    RunPhase.LOCKED -> "locked in the pocket"
                }
                // The second pass starts with the phones locked in the pockets of the first, and B changes hands.
                val unlock = if (number == 2 && phase == RunPhase.SCREEN && meters == steps.first()) "Unlock. " else ""
                val walk = "${unlock}Walk to ${metres(meters)} from the one standing, $lockHint."
                val stand = "${unlock}Stand still by the Mac, $lockHint."
                val handOver = if (unlock.isEmpty()) "" else "B goes to the one standing. "
                val iPhone = hider(CARRIED, phase)
                val far = metres(meters)
                return bigStep(
                    "b5_p${number}_${if (pocket) "pocket" else "hand"}_${far.replace(" ", "")}",
                    "Distances, pass $number: $far, ${if (pocket) "in the pocket" else "in the hand"}",
                    seconds,
                    if (bWalks) {
                        "A stands by the Mac; B and droid are $far away, $lockHint."
                    } else {
                        "A and B stand by the Mac; droid is $far away, $lockHint."
                    },
                    if (bWalks) {
                        apart(meters, meters, 0.3, A to 0.5, B to meters, DROID to meters)
                    } else {
                        apart(0.3, meters, meters, A to 0.5, B to 0.5, DROID to meters)
                    },
                    at(iPhone, place, LabPlaces.STAND, stand),
                    at(iPhone, place, LabPlaces.STAND, if (bWalks) walk else handOver + stand),
                    at(droidSetup.copy(phase = phase), place, LabPlaces.STAND, walk),
                    onTable(listening, "Listen, next to the one standing."),
                )
            }
            val out = steps.map { step(it, RunPhase.SCREEN, 35) }
            val back = steps.reversed().mapIndexed { index, meters ->
                if (index == 0) step(meters, RunPhase.LOCK, 45) else step(meters, RunPhase.LOCKED, 35)
            }
            return out + back
        }
        return pass(1, seeker(CARRIED).copy(listen = true)) + pass(2, hider(CARRIED).copy(listen = true))
    }

    /** Block 5's distances, metres (ADR 0017 §6). */
    private val DISTANCES_OF_THE_RUN = listOf(1.0, 3.0, 5.0, 10.0, 20.0, 40.0)

    /**
     * Block 6, 10 min: the pocket (radio-lab.md §8, E5) on the three phones at once, `carry.v2` in the shadow against
     * the places: the first person carries A, the second B and droid, side by side 1 m apart.
     */
    private fun pocket(): List<RunStep> {
        val walking = apart(1.0, 1.0, 0.3)
        val table = apart(0.5, 0.5, 0.5)
        data class Leg(
            val id: String,
            val place: String,
            val action: String,
            val phase: RunPhase,
            val seconds: Int,
            val hint: String,
        )
        val legs = listOf(
            Leg("hand", LabPlaces.HAND, LabPlaces.WALK, RunPhase.SCREEN, 45, "Unlock, in the hand: walk."),
            Leg("front_walk", LabPlaces.POCKET_FRONT, LabPlaces.WALK, RunPhase.LOCK, 60, "Lock, front pocket: walk."),
            Leg(
                "front_stand",
                LabPlaces.POCKET_FRONT,
                LabPlaces.STAND,
                RunPhase.LOCKED,
                75,
                "Front pocket: stand still.",
            ),
            Leg("front_sit", LabPlaces.POCKET_FRONT, LabPlaces.SIT, RunPhase.LOCKED, 60, "Front pocket: sit down."),
            Leg("back", LabPlaces.POCKET_BACK, LabPlaces.WALK, RunPhase.LOCKED, 50, "Back pocket: walk."),
            Leg("jacket", LabPlaces.JACKET, LabPlaces.WALK, RunPhase.LOCKED, 50, "Jacket pocket: walk."),
            Leg("backpack", LabPlaces.BACKPACK, LabPlaces.WALK, RunPhase.LOCKED, 50, "In the backpack: walk."),
            Leg("table_up", LabPlaces.TABLE_UP, LabPlaces.LIE, RunPhase.LOCKED, 50, "On the table, screen up."),
            Leg("table_down", LabPlaces.TABLE_DOWN, LabPlaces.LIE, RunPhase.LOCKED, 50, "On the table, screen down."),
            Leg(
                "hand_locked",
                LabPlaces.HAND_LOCKED,
                LabPlaces.WALK,
                RunPhase.LOCKED,
                50,
                "Locked in the hand along the body: walk.",
            ),
        )
        val carried = legs.map { leg ->
            val onTheTable = leg.place == LabPlaces.TABLE_UP || leg.place == LabPlaces.TABLE_DOWN
            bigStep(
                "b6_${leg.id}",
                "Pocket: ${leg.id.replace('_', ' ')}",
                leg.seconds,
                "All three: ${leg.hint} A with the first person, B and droid with the second, 1 m apart" +
                    if (onTheTable) "; the phones 0.5 m apart." else ".",
                if (onTheTable) table else walking,
                at(hider(CARRIED, leg.phase), leg.place, leg.action, leg.hint),
                at(hider(CARRIED, leg.phase), leg.place, leg.action, leg.hint),
                at(hider(CARRIED, leg.phase).copy(listen = true), leg.place, leg.action, leg.hint),
            )
        }
        val proximityHint = "Unlock, don't lock again: pocket the phone screen to the leg, the sensor darkens it; walk."
        return carried + bigStep(
            "b6_proximity",
            "Pocket: the screen off by the proximity sensor",
            60,
            "A and B unlocked in the pocket, dark by the proximity sensor; droid in the pocket, screen off by its " +
                "button: walk.",
            walking,
            at(hider(CARRIED).copy(screenOff = true), LabPlaces.POCKET_PROXIMITY, LabPlaces.WALK, proximityHint),
            at(hider(CARRIED).copy(screenOff = true), LabPlaces.POCKET_PROXIMITY, LabPlaces.WALK, proximityHint),
            at(
                hider(CARRIED).copy(listen = true),
                LabPlaces.POCKET_FRONT,
                LabPlaces.WALK,
                "Screen off with the power button, front pocket: walk.",
            ),
        )
    }

    /**
     * Block 7, 10 min: a mini game. droid seeks in the hand; the second person hides with A (front pocket) and B
     * (back pocket), both locked, and stands still while droid comes from 40 m to 1 m and goes away. Every candidate is
     * on, the pulse beats; the report computes the bands a game would have shown.
     */
    private fun miniGame(): List<RunStep> {
        val hidden = { phase: RunPhase -> hider(EVERY_CANDIDATE, phase, pulse = true) }
        val seeking = seeker(CARRIED + GATT).copy(listen = true)
        fun step(id: String, meters: Double?, phase: RunPhase, seconds: Int, hint: String, seekerHint: String) =
            bigStep(
                id,
                "Mini game: ${id.removePrefix("b7_").replace('_', ' ')}",
                seconds,
                hint,
                meters?.let { apart(0.3, it, it) }.orEmpty(),
                at(hidden(phase), LabPlaces.POCKET_FRONT, LabPlaces.STAND, "Hidden in the front pocket: stand still."),
                at(hidden(phase), LabPlaces.POCKET_BACK, LabPlaces.STAND, "Hidden in the back pocket: stand still."),
                at(seeking, LabPlaces.HAND, LabPlaces.WALK, seekerHint),
            )
        val approach = listOf(40.0 to 60, 20.0 to 60, 10.0 to 60, 5.0 to 60, 3.0 to 45, 1.0 to 45)
        val leave = listOf(10.0 to 60, 20.0 to 60, 40.0 to 60)
        return buildList {
            add(
                bigStep(
                    "b7_hide",
                    "Mini game: hide",
                    60,
                    "The hider locks A and B, pockets them (A front, B back) and walks 40 m away; droid waits.",
                    emptyMap(),
                    at(hidden(RunPhase.LOCK), LabPlaces.POCKET_FRONT, LabPlaces.WALK, "Lock, front pocket, walk 40 m."),
                    at(hidden(RunPhase.LOCK), LabPlaces.POCKET_BACK, LabPlaces.WALK, "Lock, back pocket, walk 40 m."),
                    at(seeking, LabPlaces.HAND, LabPlaces.STAND, "The seeker: wait, screen on."),
                ),
            )
            for ((meters, seconds) in approach) {
                add(
                    step(
                        "b7_seek_${metres(meters).replace(" ", "")}",
                        meters,
                        RunPhase.LOCKED,
                        seconds,
                        "droid comes to ${metres(meters)} from the hider: the hider's pulse should quicken.",
                        "Walk to ${metres(meters)} from the hider, stand.",
                    ),
                )
            }
            for ((meters, seconds) in leave) {
                add(
                    step(
                        "b7_leave_${metres(meters).replace(" ", "")}",
                        meters,
                        RunPhase.LOCKED,
                        seconds,
                        "droid goes away to ${metres(meters)}: the pulse should slow down.",
                        "Walk away to ${metres(meters)} from the hider, stand.",
                    ),
                )
            }
            add(
                step(
                    "b7_unlock",
                    1.0,
                    RunPhase.SCREEN,
                    30,
                    "Found: come together, take A and B out and unlock them.",
                    "Walk back to the hider.",
                ).let { step ->
                    step.copy(
                        devices = step.devices + listOf(A, B).associateWith {
                            at(hidden(RunPhase.SCREEN), LabPlaces.HAND, LabPlaces.STAND, "Take out, unlock.")
                        },
                    )
                },
            )
        }
    }

    /**
     * Version 1: the big run of ADR 0017 §6, blocks 0–8 (step ids `b0_`…`b8_`), about 107 min, every step timed so the
     * run goes by itself (the console's «Next» moves on earlier, «Repeat» and «Pause» hold it). Two iPhones (`A`, `B`),
     * an Android (`droid`) and, if there is one, a Mac (`mac`, named where it listens on the table: a run without it
     * just has nobody with that label). Two people. The checklist: docs/radio-lab-tests.md «Большой прогон».
     */
    val BIG_RUN = LabRunScript(
        id = "big_run",
        version = 1,
        title = "Big run: two iPhones, an Android and a Mac, ~107 min",
        labels = BIG_LABELS,
        steps = preparation() + touches() + table() + vibration() + twoPockets() + distances() + pocket() +
            miniGame() + touchesAgain(),
    )

    val ALL: List<LabRunScript> = listOf(RADIO, E2E, BIG_RUN)

    fun byId(id: String): LabRunScript? = ALL.firstOrNull { it.id == id }

    /** The local run with the Mac (the phone's announcement carries only the version): [RADIO] only. */
    fun of(version: Int): LabRunScript? = RADIO.takeIf { it.version == version }
}
