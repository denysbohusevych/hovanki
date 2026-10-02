package app.hovanki.client.lab

import app.hovanki.device.Gravity
import app.hovanki.device.lab.LabSensorReading
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.lab.LabRadarKinds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `carry.v2` in the shadow of a field game (ADR 0018 §4, the lab's classifier): written when it changes. */
class CarryShadowTest {
    private var now = 1_790_000_000_000L
    private var mono = 0L
    private val log = LabLog(isEnabled = true, { now }, { mono })
    private val shadow = CarryShadow(log)

    private fun states(): List<Triple<String?, String?, String?>> = log.lines()
        .map { Json.parseToJsonElement(it).jsonObject }
        .filter { it[LabFields.K]?.jsonPrimitive?.content == LabRadarKinds.SHADOW }
        .map { event ->
            Triple(
                event["tech"]?.jsonPrimitive?.content,
                event["state"]?.jsonPrimitive?.content,
                event["reason"]?.jsonPrimitive?.content,
            )
        }

    /** [seconds] seconds of the phone held upright, [appState] the screen, moving by [std] g. */
    private fun seconds(seconds: Int, appState: String, std: Double = 0.01) {
        repeat(seconds) {
            repeat(10) { tenth ->
                val wobble = if (tenth % 2 == 0) std else -std
                shadow.onSensor(LabSensorReading.Motion(now + tenth * 100L, 1.0 + wobble, Gravity(0.0, -1.0, 0.0)))
            }
            now += 1_000
            mono += 1_000
            shadow.second(appState)
        }
    }

    @Test
    fun theClassifierIsWrittenWhenItChanges() {
        log.isRecording = true
        seconds(3, appState = "active")
        // The screen goes off, the phone upright: the pocket at once, and it stays there.
        seconds(5, appState = "background")

        assertEquals(
            listOf(
                Triple(CarryShadow.CARRY_V2, "in_hand", "screen_on"),
                Triple(CarryShadow.CARRY_V2, "in_pocket", "dark+upright"),
            ),
            states(),
        )
    }

    @Test
    fun nothingIsWrittenWhileTheLogIsNot() {
        seconds(2, appState = "screen_off")
        assertTrue(log.lines().isEmpty())
        log.isRecording = true
        seconds(1, appState = "screen_off")
        assertEquals(listOf(CarryShadow.CARRY_V2), states().map { it.first })
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
