package app.hovanki.client.lab

import app.hovanki.client.errors.ErrorReporter
import app.hovanki.client.errors.NoopErrorReporter
import app.hovanki.client.network.ApiException
import app.hovanki.client.network.Transport
import app.hovanki.client.network.testSession
import app.hovanki.client.network.testSnapshot
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.storage.FakeSecureStore
import app.hovanki.radar.RadioApi
import app.hovanki.radar.RadioSighting
import app.hovanki.radar.SightingVia
import app.hovanki.shared.lab.ErrFields
import app.hovanki.shared.lab.FieldKinds
import app.hovanki.shared.lab.GpsFields
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.lab.LabSchema
import app.hovanki.shared.lab.MarkFields
import app.hovanki.shared.lab.RxFields
import app.hovanki.shared.lab.SurveyFields
import app.hovanki.shared.lab.SyncFields
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LabCapabilities
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.Platform
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The field log on the phone (docs/adr/0018-field-test-build.md §3, docs/field-test.md step 2): only the field build
 * with the tester's consent joins its game's run when the round starts, writes the game thinned and with coordinates,
 * uploads it every 10 s and stops when the phone leaves the game.
 */
class FieldSessionTest {
    private val storage = ClientStorage(FakeSecureStore())

    private class Phone(val field: FieldSession, val log: LabLog, val api: FakeLabApi) {
        val events: List<JsonObject> get() = log.lines().map { Json.parseToJsonElement(it).jsonObject }

        fun kinds(): List<String> = events.map { it.kind }
    }

    private fun TestScope.phone(
        isFieldBuild: Boolean = true,
        api: FakeLabApi = FakeLabApi(),
        errorReporter: ErrorReporter = NoopErrorReporter,
    ): Phone {
        val log = LabLog(isEnabled = false, { DEVICE + currentTime }, { currentTime })
        val field = FieldSession(
            log = log,
            api = api,
            storage = storage,
            scope = backgroundScope,
            isFieldBuild = isFieldBuild,
            about = { LabAbout("Pixel 8", "Android 16", "1.0 (1) preview", "abc1234") },
            capabilities = { LabCapabilities(platform = Platform.ANDROID) },
            permissions = { mapOf("location" to "always") },
            errorReporter = errorReporter,
            retryMillis = 5_000,
        )
        return Phone(field, log, api)
    }

    private fun Phone.round(phase: GamePhase = GamePhase.SEEKING, gameId: GameId = testSession.gameId) =
        field.onSnapshot(testSession.copy(gameId = gameId), testSnapshot(phase = phase).copy(gameId = gameId))

    @Test
    fun onlyTheFieldBuildWithConsentWritesAnything() = runTest {
        val release = phone(isFieldBuild = false)
        release.field.giveConsent(1L)
        release.round()
        runCurrent()
        assertTrue(release.api.fieldJoins.isEmpty())
        assertNull(storage.fieldConsentAt, "only the field build asks")
        assertFalse(release.field.needsConsent)

        val preview = phone()
        assertTrue(preview.field.needsConsent)
        preview.round()
        runCurrent()
        assertTrue(preview.api.fieldJoins.isEmpty(), "no consent, no log")
        // Nothing of the game goes into a log that isn't on.
        preview.field.onFix(fix(DEVICE))
        assertFalse(preview.field.somethingWrong("nothing"))
        assertTrue(preview.log.lines().isEmpty())

        preview.field.giveConsent(1_234L)
        assertEquals(1_234L, storage.fieldConsentAt)
        // The lobby is no round yet.
        preview.round(GamePhase.LOBBY)
        runCurrent()
        assertTrue(preview.api.fieldJoins.isEmpty())
        preview.round(GamePhase.HIDING)
        runCurrent()
        val (gameId, token, request) = preview.api.fieldJoins.single()
        assertEquals(testSession.gameId to testSession.token, gameId to token)
        assertEquals(1_234L, request.consentAtMillis)
        assertEquals("Pixel 8" to Platform.ANDROID, request.model to request.capabilities.platform)
        assertEquals(FieldStatus.ON, preview.field.state.value.status)
        // Every snapshot after that changes nothing.
        preview.round()
        runCurrent()
        assertEquals(1, preview.api.fieldJoins.size)
    }

    @Test
    fun theGameIsWrittenThinnedWithCoordinatesAndUploaded() = runTest {
        val phone = phone()
        phone.field.giveConsent(1L)
        phone.round()
        runCurrent()
        assertTrue(phone.log.isField)
        assertEquals(listOf("clock", "session", "perm"), phone.kinds().take(3))
        val session = phone.events.first { it.kind == FieldKinds.SESSION }
        assertEquals(FieldSession.MODE, session.string("mode"))
        assertEquals("player-1", session.string(LabFields.DEV))
        assertTrue(phone.events.all { it.string(LabFields.RUN) == FakeLabApi().runId.value })

        // GPS: where, at most once a second.
        phone.field.onFix(fix(DEVICE + currentTime - 300))
        phone.field.onFix(fix(DEVICE + currentTime - 100))
        // The radio: three readings of one phone in a second are one event.
        for (rssi in listOf(-70, -60, -80)) {
            phone.field.onSighting(
                RadioSighting("0123abcd", rssi, DEVICE + currentTime, RadioApi.ANDROID_LE, tech = "ble.name"),
            )
        }
        // A sync a quarter of a second long that brings the search.
        phone.field.onSyncSent()
        advanceTimeBy(250)
        phone.field.onSynced(Transport.SOCKET, testSnapshot(phase = GamePhase.SEEKING))
        phone.field.onSyncSent()
        phone.field.onSyncFailed(ApiException(503, null))
        phone.field.ui("game", "tap", "catch_claim")
        assertTrue(phone.field.somethingWrong("  the radar is silent  "))
        advanceTimeBy(1_500)
        runCurrent()

        val gps = phone.events.filter { it.kind == FieldKinds.GPS }
        assertEquals(1, gps.size)
        assertTrue(LabSchema.hasCoordinates(gps.single()))
        assertEquals(50.4501, gps.single().double(GpsFields.LAT))
        val rx = phone.events.filter { it.kind == FieldKinds.RX }
        assertEquals(1, rx.size, "${phone.kinds()}")
        assertEquals(
            Triple(3, -70, -60),
            rx.single().let {
                Triple(it.int(RxFields.COUNT), it.int(RxFields.RSSI), it.int(RxFields.MAX))
            },
        )
        assertEquals("ble.name", rx.single().string(RxFields.TECH))
        val (synced, failed) = phone.events.filter { it.kind == FieldKinds.SYNC }
        assertEquals(SyncFields.SOCKET, synced.string(SyncFields.TRANSPORT))
        assertEquals(250, synced.int(SyncFields.MILLIS))
        assertEquals("SEEKING", synced.string(SyncFields.PHASE))
        assertEquals(503 to SyncFields.SOCKET, failed.int(SyncFields.CODE) to failed.string(SyncFields.TRANSPORT))
        val mark = phone.events.single { it.kind == FieldKinds.MARK }
        assertEquals(
            MarkFields.PLAYER to "the radar is silent",
            mark.string(MarkFields.BY) to mark.string(MarkFields.TEXT),
        )
        assertTrue(phone.kinds().count { it == FieldKinds.TICK } >= 1)

        // Every 10 s the log goes up.
        assertTrue(phone.api.uploads.isEmpty() || phone.api.uploads.size == 1)
        advanceTimeBy(FakeLabApi.FIELD_UPLOAD_MILLIS + 100)
        runCurrent()
        assertTrue(phone.api.uploads.isNotEmpty())
        val sent = phone.api.uploads.flatMap { it.lines }.map { Json.parseToJsonElement(it).jsonObject.kind }
        assertTrue(FieldKinds.GPS in sent && FieldKinds.MARK in sent, "$sent")

        // The survey after the game goes up at once.
        phone.round(GamePhase.FINISHED)
        assertTrue(phone.field.survey(4, listOf("radar"), "fun", "pocket"))
        runCurrent()
        val survey = phone.api.uploads.flatMap { it.lines }.map { Json.parseToJsonElement(it).jsonObject }
            .single { it.kind == FieldKinds.SURVEY }
        assertEquals(4, survey.int(SurveyFields.RATING))
        assertEquals(listOf("radar"), survey[SurveyFields.BROKEN]!!.jsonArray.map { it.jsonPrimitive.content })

        // The player leaves: the log stops, the rest goes up.
        phone.field.onSessionEnded()
        runCurrent()
        assertEquals(FieldStatus.LEFT, phone.field.state.value.status)
        assertFalse(phone.log.isField)
        val count = phone.log.lines().size
        phone.field.onFix(fix(DEVICE + currentTime))
        phone.log.note("after")
        assertEquals(count, phone.log.lines().size)
        val last = phone.log.lines().last().let { Json.parseToJsonElement(it).jsonObject }.long(LabFields.SEQ)
        assertEquals(last, phone.api.ackedSeq)
    }

    @Test
    fun theConsentTakenBackSendsNothingMore() = runTest {
        val phone = phone()
        phone.field.giveConsent(1L)
        phone.round()
        runCurrent()
        assertTrue(phone.field.isActive)
        phone.field.onFix(fix(DEVICE + currentTime))
        assertTrue(phone.field.somethingWrong("lost"))
        val sent = phone.api.uploads.size

        phone.field.withdrawConsent()
        runCurrent()
        assertNull(storage.fieldConsentAt)
        assertTrue(phone.field.needsConsent)
        assertEquals(FieldStatus.LEFT, phone.field.state.value.status)
        // What was not up yet stays on no phone and goes nowhere.
        assertTrue(phone.log.lines().isEmpty())
        advanceTimeBy(60_000)
        phone.round()
        runCurrent()
        assertEquals(sent, phone.api.uploads.size)
        assertEquals(1, phone.api.fieldJoins.size)
    }

    @Test
    fun thePhonesOwnFailuresGoToTheReporterAndTheLogKeepsItsId() = runTest {
        val reported = ArrayList<Throwable>()
        val phone = phone(errorReporter = { t -> reported += t; "e${reported.size}" })
        phone.field.giveConsent(1L)
        phone.round()
        runCurrent()
        phone.field.onError("radio", IllegalStateException("advertiser failed"))
        // A command's failure is the network's or the server's answer: only the log has it.
        phone.field.onError("command", IllegalStateException("timeout"))

        assertEquals(listOf("advertiser failed"), reported.map { it.message })
        val (radio, command) = phone.events.filter { it.kind == FieldKinds.ERR }
        assertEquals("radio" to "e1", radio.string(ErrFields.WHERE) to radio.string(ErrFields.SENTRY_ID))
        assertEquals("command" to null, command.string(ErrFields.WHERE) to command.string(ErrFields.SENTRY_ID))
    }

    @Test
    fun aServerWithoutTheFieldLogIsNotAskedAgainInThisGame() = runTest {
        val api = FakeLabApi().apply { fieldRefusal = ApiException(404, ApiError(ErrorCode.NOT_FOUND, "Not found")) }
        val phone = phone(api = api)
        phone.field.giveConsent(1L)
        phone.round()
        runCurrent()
        assertEquals(FieldStatus.REFUSED, phone.field.state.value.status)
        advanceTimeBy(60_000)
        phone.round()
        runCurrent()
        assertEquals(1, api.fieldJoins.size)
        assertTrue(phone.log.lines().isEmpty())

        // The next game asks again.
        api.fieldRefusal = null
        phone.round(gameId = GameId("game2"))
        runCurrent()
        assertEquals(2, api.fieldJoins.size)
        assertEquals(FieldStatus.ON to GameId("game2"), phone.field.state.value.let { it.status to it.gameId })
    }

    @Test
    fun aJoinWithoutNetworkIsTriedAgain() = runTest {
        val api = FakeLabApi().apply { fieldRefusal = IllegalStateException("offline") }
        val phone = phone(api = api)
        phone.field.giveConsent(1L)
        phone.round()
        runCurrent()
        assertEquals(FieldStatus.OFF, phone.field.state.value.status)
        assertEquals("IllegalStateException: offline", phone.field.state.value.error)
        // Not at once with every snapshot.
        phone.round()
        runCurrent()
        assertEquals(1, api.fieldJoins.size)
        api.fieldRefusal = null
        advanceTimeBy(5_000)
        phone.round()
        runCurrent()
        assertEquals(2, api.fieldJoins.size)
        assertTrue(phone.field.isActive)
    }

    @Test
    fun theLabOutsideTheFieldWritesNoCoordinates() = runTest {
        val log = LabLog(isEnabled = true, { DEVICE + currentTime }, { currentTime }).also { it.isRecording = true }
        log.fix(50.45, 30.52, 5.0, ageMillis = 100, speed = 1.25)
        log.rx("0123abcd", -60, RadioApi.ANDROID_LE, SightingVia.SERVICE_DATA)
        log.rx("0123abcd", -61, RadioApi.ANDROID_LE, SightingVia.SERVICE_DATA)
        val events = log.lines().map { Json.parseToJsonElement(it).jsonObject }
        val gps = events.single { it.kind == FieldKinds.GPS }
        assertFalse(LabSchema.hasCoordinates(gps), "$gps")
        assertEquals(5.0 to 1.3, gps.double(GpsFields.ACC) to gps.double(GpsFields.SPEED))
        // Every reading, as before.
        assertEquals(2, events.count { it.kind == FieldKinds.RX })
    }

    private fun fix(atDeviceMillis: Long) = LocationSample(GeoPoint(50.4501, 30.5234), 6.0, atDeviceMillis)

    private companion object {
        const val DEVICE = 1_790_000_000_000L
    }
}

private val JsonObject.kind: String get() = string(LabFields.K).orEmpty()

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.int

private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.content?.toLong()

private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.content?.toDouble()
