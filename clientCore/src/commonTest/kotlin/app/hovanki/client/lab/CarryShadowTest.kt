package app.hovanki.client.lab

import app.hovanki.device.lab.Gravity
import app.hovanki.device.lab.LabSensorReading
import app.hovanki.shared.lab.CarryTechs
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.lab.LabRadarKinds
import app.hovanki.shared.lab.ShadowFields
import app.hovanki.shared.protocol.Carry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pocket's two classifiers in the shadow (ADR 0017 §2.3): each written when it changes, only while recording. */
class CarryShadowTest {
    private var now = 1_790_000_000_000L
    private val log = LabLog(isEnabled = true, { now }, { now })
    private val shadow = CarryShadow(log)

    private fun states(): List<Triple<String?, String?, String?>> = log.lines()
        .map { Json.parseToJsonElement(it).jsonObject }
        .filter { it[LabFields.K]?.jsonPrimitive?.content == LabRadarKinds.SHADOW }
        .map { event ->
            Triple(
                event[ShadowFields.TECH]?.jsonPrimitive?.content,
                event[ShadowFields.STATE]?.jsonPrimitive?.content,
                event[ShadowFields.WHY]?.jsonPrimitive?.content,
            )
        }

    /** [seconds] seconds of the phone held upright and still, [appState] the screen. */
    private fun seconds(seconds: Int, appState: String, std: Double = 0.01) {
        repeat(seconds) {
            repeat(10) { tenth ->
                val wobble = if (tenth % 2 == 0) std else -std
                shadow.onSensor(LabSensorReading.Motion(now + tenth * 100L, 1.0 + wobble, Gravity(0.0, -1.0, 0.0)))
            }
            now += 1_000
            shadow.second(now, appState)
        }
    }

    @Test
    fun eachClassifierIsWrittenWhenItChanges() {
        log.isRecording = true
        shadow.onCarryV1(Carry.IN_HAND)
        seconds(3, appState = "active")
        shadow.onCarryV1(Carry.IN_HAND)
        // The screen goes off, the phone upright: the candidate says the pocket at once, the game's monitor «unknown».
        shadow.onCarryV1(Carry.UNKNOWN)
        seconds(5, appState = "background")

        assertEquals(
            listOf(
                Triple(CarryTechs.V1, "in_hand", null),
                Triple(CarryTechs.V2, "in_hand", "screen"),
                Triple(CarryTechs.V1, "unknown", null),
                Triple(CarryTechs.V2, "in_pocket", "upright"),
            ),
            states(),
        )
    }

    @Test
    fun nothingIsWrittenWhileTheLogIsNot() {
        shadow.onCarryV1(Carry.IN_POCKET)
        seconds(2, appState = "screen_off")
        assertTrue(log.lines().isEmpty())
        // Recording from now on: the states are written as soon as they are known again.
        log.isRecording = true
        shadow.onCarryV1(Carry.IN_POCKET)
        seconds(1, appState = "screen_off")
        assertEquals(listOf(CarryTechs.V1, CarryTechs.V2), states().map { it.first })
    }

    @Test
    fun theScreenByTheAppsState() {
        assertEquals(true, CarryShadow.screenOn("active"))
        assertEquals(true, CarryShadow.screenOn("screen_on"))
        assertEquals(false, CarryShadow.screenOn("background"))
        assertEquals(false, CarryShadow.screenOn("screen_off"))
        assertEquals(null, CarryShadow.screenOn("-"))
    }
}
