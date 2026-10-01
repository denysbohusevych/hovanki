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
) {
    /** The part a locked phone can't change: its advertisement. */
    internal val advertisement: Triple<Boolean, Boolean, ProbeMode?> get() = Triple(hider, seeker, probe)

    /** In a few words, for the console: `hider · probe token · listen`. */
    fun describe(): String = listOfNotNull(
        "hider".takeIf { hider },
        "seeker's iBeacon".takeIf { seeker },
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

    /** How far apart the e2e bots stand: A, B 2 m east of A, droid 2 m north of A. */
    private val E2E_DISTANCES = mapOf(
        RunStep.pairKey("A", "B") to 2.0,
        RunStep.pairKey("A", "droid") to 2.0,
        RunStep.pairKey("B", "droid") to 2.8,
    )

    /**
     * Version 3: three phones in the hand 2 m apart, 22 s; the e2e bots' run (`LabRunTest`). The distances are the
     * report's truth for the bands (they don't change what the phones do).
     */
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
                distances = E2E_DISTANCES,
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
                distances = E2E_DISTANCES,
            ),
            RunStep(
                "all_again",
                "Everybody advertises again",
                8,
                E2E_LABELS.associateWith { inHand(PhoneSetup(hider = true)) },
                distances = E2E_DISTANCES,
            ),
        ),
    )

    private val TOUCH_LABELS = listOf("A", "B")
    private val APART = mapOf(RunStep.pairKey("A", "B") to 2.0)

    /**
     * Version 1: the touch calibration's block (docs/adr/0017-radar-techniques-and-big-run.md §3), two phones in the
     * hand, both advertising as hiders: 2 m apart, three touches with a step back between them (each confirmed by
     * both with «We touched»: the detector's truth), 2 m apart again; 61 s. The big run's block (more touches, the
     * backs and edges, the drift at the end) is ADR 0017 §6's.
     */
    val TOUCH = LabRunScript(
        id = "touch",
        version = 1,
        title = "Touch: two phones, 61 s",
        labels = TOUCH_LABELS,
        steps = listOf(
            RunStep(
                "apart",
                "Stand 2 m apart",
                8,
                TOUCH_LABELS.associateWith { inHand(PhoneSetup(hider = true)) },
                hint = "Screens on, phones in the hand.",
                distances = APART,
            ),
            RunStep(
                "touch",
                "Touch the phones three times",
                45,
                TOUCH_LABELS.associateWith { inHand(PhoneSetup(hider = true)) },
                hint = "Step close, touch the phones back to back once, both press «We touched», step back; " +
                    "three times, about 10 s apart.",
                distances = mapOf(RunStep.pairKey("A", "B") to 0.0),
            ),
            RunStep(
                "apart_again",
                "2 m apart again",
                8,
                TOUCH_LABELS.associateWith { inHand(PhoneSetup(hider = true)) },
                distances = APART,
            ),
        ),
    )

    val ALL: List<LabRunScript> = listOf(RADIO, E2E, TOUCH)

    fun byId(id: String): LabRunScript? = ALL.firstOrNull { it.id == id }

    /** The local run with the Mac (the phone's announcement carries only the version): [RADIO] only. */
    fun of(version: Int): LabRunScript? = RADIO.takeIf { it.version == version }
}
