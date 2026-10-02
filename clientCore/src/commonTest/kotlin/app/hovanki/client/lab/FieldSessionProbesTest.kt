package app.hovanki.client.lab

import app.hovanki.client.network.Transport
import app.hovanki.client.network.testSession
import app.hovanki.client.network.testSnapshot
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.storage.FakeSecureStore
import app.hovanki.device.ActivityMonitor
import app.hovanki.device.CarryMonitor
import app.hovanki.device.lab.LabBattery
import app.hovanki.device.lab.LabProbes
import app.hovanki.device.lab.LabSensorReading
import app.hovanki.shared.lab.FieldKinds
import app.hovanki.shared.lab.GpsFields
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.lab.PermFields
import app.hovanki.shared.lab.SyncFields
import app.hovanki.shared.lab.UiFields
import app.hovanki.shared.protocol.Activity
import app.hovanki.shared.protocol.Carry
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LabCapabilities
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.Platform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the field log adds of the phone itself (docs/field-test.md, the gaps of step 2): speed and bearing of a fix,
 * the size of a sync's answer, the sensors thinned, the permissions when they change, the screens and the actions.
 */
class FieldSessionProbesTest {
    private val storage = ClientStorage(FakeSecureStore())

    private class Probes : LabProbes {
        val sensors = MutableSharedFlow<LabSensorReading>(extraBufferCapacity = 64)
        val battery = MutableSharedFlow<LabBattery>(extraBufferCapacity = 64)
        val thermal = MutableSharedFlow<String>(extraBufferCapacity = 64)

        override fun appState(): String = "screen_on"

        override fun lifecycle(): Flow<String> = MutableSharedFlow()

        override fun sensors(): Flow<LabSensorReading> = sensors

        override fun battery(): Flow<LabBattery> = battery

        override fun thermal(): Flow<String> = thermal
    }

    private class Phone(
        val field: FieldSession,
        val log: LabLog,
        val probes: Probes,
        val carry: MutableSharedFlow<Carry>,
        val activity: MutableSharedFlow<Activity>,
        val permissions: MutableMap<String, String>,
    ) {
        val events: List<JsonObject> get() = log.lines().map { Json.parseToJsonElement(it).jsonObject }

        fun of(kind: String): List<JsonObject> = events.filter { it.kind == kind }
    }

    private fun TestScope.phone(): Phone {
        val log = LabLog(isEnabled = false, { DEVICE + currentTime }, { currentTime })
        val probes = Probes()
        val carry = MutableSharedFlow<Carry>(extraBufferCapacity = 8)
        val activity = MutableSharedFlow<Activity>(extraBufferCapacity = 8)
        val permissions = mutableMapOf(PermFields.LOCATION to "always", PermFields.CAMERA to "denied")
        val field = FieldSession(
            log = log,
            api = FakeLabApi(),
            storage = storage,
            scope = backgroundScope,
            isFieldBuild = true,
            about = { LabAbout("Pixel 8", "Android 16", "1.0 (1) preview", "abc1234") },
            capabilities = { LabCapabilities(platform = Platform.ANDROID) },
            probes = probes,
            carryMonitor = object : CarryMonitor {
                override fun carry(): Flow<Carry> = carry
            },
            activityMonitor = object : ActivityMonitor {
                override fun activity(): Flow<Activity> = activity
            },
            permissions = { permissions.toMap() },
            permissionsEveryMillis = 1_000,
        )
        field.giveConsent(1L)
        return Phone(field, log, probes, carry, activity, permissions)
    }

    private fun Phone.round() {
        field.onSnapshot(testSession, testSnapshot(phase = GamePhase.SEEKING, serverTimeMillis = SERVER))
    }

    @Test
    fun aFixCarriesItsSpeedAndBearingAndTheSyncItsSize() = runTest {
        val phone = phone()
        phone.round()
        runCurrent()
        phone.field.onFix(
            LocationSample(
                GeoPoint(50.4501, 30.5234),
                6.0,
                DEVICE + currentTime,
                speedMetersPerSecond = 1.44,
                bearingDegrees = 271.6,
            ),
        )
        phone.field.onSyncSent()
        phone.field.onSyncBytes(12_345)
        phone.field.onSynced(Transport.POLLING, testSnapshot(phase = GamePhase.SEEKING))
        // The size belongs to one sync: the next one without a measure has none.
        phone.field.onSyncSent()
        phone.field.onSynced(Transport.POLLING, testSnapshot(phase = GamePhase.SEEKING))

        val gps = phone.of(FieldKinds.GPS).single()
        assertEquals(1.4, gps.double(GpsFields.SPEED))
        assertEquals(272.0, gps.double(GpsFields.BEARING))
        val (first, second) = phone.of(FieldKinds.SYNC)
        assertEquals(12_345.0, first.double(SyncFields.BYTES))
        assertEquals(null, second[SyncFields.BYTES])
    }

    @Test
    fun theSensorsAreWrittenThinned() = runTest {
        val phone = phone()
        phone.round()
        runCurrent()
        phone.carry.emit(Carry.IN_HAND)
        phone.carry.emit(Carry.IN_HAND)
        phone.carry.emit(Carry.IN_POCKET)
        phone.activity.emit(Activity.WALKING)
        phone.activity.emit(Activity.WALKING)
        phone.probes.thermal.emit("nominal")
        phone.probes.thermal.emit("nominal")
        phone.probes.thermal.emit("fair")
        phone.probes.sensors.emit(LabSensorReading.Proximity(false, 5.0, 5.0))
        phone.probes.sensors.emit(LabSensorReading.Proximity(false, 5.0, 5.0))
        phone.probes.sensors.emit(LabSensorReading.Proximity(true, 0.0, 5.0))
        // Motion's every reading is not kept.
        repeat(20) { phone.probes.sensors.emit(LabSensorReading.Motion(it * 100L, 1.0, null)) }
        phone.probes.sensors.emit(LabSensorReading.Light(120.0))
        phone.probes.sensors.emit(LabSensorReading.Light(121.0))
        phone.probes.battery.emit(LabBattery(0.8, "discharging", false))
        phone.probes.battery.emit(LabBattery(0.79, "discharging", false))
        runCurrent()

        assertEquals(listOf("in_hand", "in_pocket"), phone.of("carry").map { it.string("state") })
        assertEquals(listOf("walking"), phone.of("motion").map { it.string("activity") })
        assertEquals(listOf("nominal", "fair"), phone.of(FieldKinds.THERMAL).map { it.string("state") })
        assertEquals(2, phone.of("prox").size)
        assertEquals(1, phone.of("light").size)
        assertEquals(1, phone.of("battery").size)
    }

    @Test
    fun thePermissionsAreWrittenAtTheJoinAndWhenTheyChange() = runTest {
        val phone = phone()
        phone.round()
        runCurrent()
        assertEquals(1, phone.of(FieldKinds.PERM).size)
        advanceTimeBy(5_000)
        assertEquals(1, phone.of(FieldKinds.PERM).size, "nothing changed")
        phone.permissions[PermFields.CAMERA] = "on"
        advanceTimeBy(1_100)
        val perms = phone.of(FieldKinds.PERM)
        assertEquals(2, perms.size)
        assertEquals("on", perms.last().string(PermFields.CAMERA))
        // The same words again, by hand: no new line.
        phone.field.permissions(phone.permissions.toMap())
        assertEquals(2, phone.of(FieldKinds.PERM).size)
    }

    @Test
    fun theScreensOpenBeforeTheLogStartedAreWrittenAtItsStart() = runTest {
        val phone = phone()
        phone.field.ui("game", UiFields.OPEN)
        phone.field.ui("chat", UiFields.OPEN)
        phone.field.ui("chat", UiFields.CLOSE)
        phone.round()
        runCurrent()
        assertEquals(
            listOf("game" to "open"),
            phone.of(FieldKinds.UI).map {
                it.string("screen") to it.string("event")
            },
        )

        phone.field.ui("settings", UiFields.OPEN)
        phone.field.onAction("catch_claim")
        val last = phone.of(FieldKinds.UI).last()
        assertEquals(
            Triple("game", "tap", "catch_claim"),
            Triple(last.string("screen"), last.string("event"), last.string("action")),
        )
        assertTrue(phone.of(FieldKinds.UI).any { it.string("screen") == "settings" })
    }

    private companion object {
        const val DEVICE = 1_790_000_000_000L
        const val SERVER = 1_790_000_000_000L
    }
}

private val JsonObject.kind: String get() = string(LabFields.K).orEmpty()

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.content?.toDouble()
