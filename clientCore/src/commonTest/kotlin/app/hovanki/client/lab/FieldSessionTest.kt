package app.hovanki.client.lab

import app.hovanki.client.errors.ErrorReporter
import app.hovanki.client.errors.NoopErrorReporter
import app.hovanki.client.network.ApiException
import app.hovanki.client.network.Transport
import app.hovanki.client.network.testSession
import app.hovanki.client.network.testSnapshot
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.storage.FakeSecureStore
import app.hovanki.device.lab.LabBattery
import app.hovanki.device.lab.LabProbes
import app.hovanki.device.lab.LabScreen
import app.hovanki.device.lab.LabSensorReading
import app.hovanki.radar.RadioApi
import app.hovanki.radar.RadioSighting
import app.hovanki.radar.SightingVia
import app.hovanki.shared.lab.ErrFields
import app.hovanki.shared.lab.FieldKinds
import app.hovanki.shared.lab.GpsFields
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.lab.LabSchema
import app.hovanki.shared.lab.MarkFields
import app.hovanki.shared.lab.RunStep
import app.hovanki.shared.lab.RxFields
import app.hovanki.shared.lab.SurveyFields
import app.hovanki.shared.lab.SyncFields
import app.hovanki.shared.lab.TouchDetector
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.FeatureMode
import app.hovanki.shared.protocol.GameFeatures
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LabCapabilities
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.rules.RadarToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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
        clockSync: LabClockSync? = null,
        random: Random = Random.Default,
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
            clockSync = clockSync,
            permissions = { mapOf("location" to "always") },
            errorReporter = errorReporter,
            retryMillis = 5_000,
            random = random,
        )
        return Phone(field, log, api)
    }

    private fun Phone.round(phase: GamePhase = GamePhase.SEEKING, gameId: GameId = testSession.gameId) =
        field.onSnapshot(
            testSession.copy(gameId = gameId),
            testSnapshot(phase = phase, serverTimeMillis = SERVER).copy(gameId = gameId),
        )

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

        preview.field.giveConsent(CONSENT)
        assertEquals(CONSENT, storage.fieldConsentAt)
        // The results of a game nobody logged stay unlogged; the lobby already joins (the touches before the round).
        preview.round(GamePhase.FINISHED)
        runCurrent()
        assertTrue(preview.api.fieldJoins.isEmpty())
        preview.round(GamePhase.LOBBY)
        runCurrent()
        val (gameId, token, request) = preview.api.fieldJoins.single()
        assertEquals(testSession.gameId to testSession.token, gameId to token)
        assertEquals(CONSENT, request.consentAtMillis)
        assertEquals("Pixel 8" to Platform.ANDROID, request.model to request.capabilities.platform)
        assertEquals(FieldStatus.ON, preview.field.state.value.status)
        // Every snapshot after that changes nothing.
        preview.round(GamePhase.HIDING)
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
        // The server is told: its own events of the game name the player no more.
        assertEquals(listOf(testSession.gameId to testSession.token), phone.api.fieldLeaves)

        // The consent given again in the same game: the phone joins the game's run again.
        phone.field.giveConsent(CONSENT)
        phone.round()
        runCurrent()
        assertEquals(2, phone.api.fieldJoins.size)
        assertEquals(FieldStatus.ON, phone.field.state.value.status)
    }

    @Test
    fun aPhoneThatLeftAndCameBackToTheSameGameJoinsItsRunAgain() = runTest {
        val phone = phone()
        phone.field.giveConsent(CONSENT)
        phone.round(GamePhase.LOBBY)
        runCurrent()
        // Left the lobby by mistake, and back by the game's code.
        phone.field.onSessionEnded()
        runCurrent()
        assertEquals(FieldStatus.LEFT, phone.field.state.value.status)
        assertEquals(1, phone.api.fieldLeaves.size)
        phone.round(GamePhase.LOBBY)
        runCurrent()
        assertEquals(2, phone.api.fieldJoins.size)
        assertEquals(FieldStatus.ON, phone.field.state.value.status)
    }

    @Test
    fun thePhonesOwnFailuresGoToTheReporterAndTheLogKeepsItsId() = runTest {
        val reported = ArrayList<Throwable>()
        val phone = phone(errorReporter = { t ->
            reported += t
            "e${reported.size}"
        })
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

        // The consent taken back: the game saved before goes on behind the consent screen, and its failures go nowhere.
        phone.field.withdrawConsent()
        phone.field.onError("location", IllegalStateException("no fix"))
        assertEquals(1, reported.size)
    }

    @Test
    fun withoutConsentNoFailureIsReported() = runTest {
        val reported = ArrayList<Throwable>()
        val phone = phone(errorReporter = { t ->
            reported += t
            "e"
        })
        phone.field.onError("radio", IllegalStateException("advertiser failed"))
        assertTrue(reported.isEmpty())
    }

    @Test
    fun aConsentStampedByAClockOffIsStampedAgainByTheServers() = runTest {
        // The consent screen comes before the app knows the server's clock: a phone two days ahead.
        val phone = phone()
        phone.field.giveConsent(SERVER + 2 * 86_400_000L)
        phone.round(GamePhase.LOBBY)
        assertEquals(SERVER, storage.fieldConsentAt)
        assertEquals(SERVER, phone.field.consentAt.value)
        phone.round()
        runCurrent()
        assertEquals(SERVER, phone.api.fieldJoins.single().third.consentAtMillis)

        // Far behind, before the field build existed: the server would refuse it.
        phone.field.giveConsent(1_000L)
        phone.round(GamePhase.LOBBY)
        assertEquals(SERVER, storage.fieldConsentAt)
        // A clock that was right stays as it said.
        phone.field.giveConsent(CONSENT)
        phone.round(GamePhase.LOBBY)
        assertEquals(CONSENT, storage.fieldConsentAt)
    }

    @Test
    fun theLastGamesUploadStopsWithTheConsentTakenBackAndNeverSendsTheNextGame() = runTest {
        val api = FakeLabApi().apply { fieldRunPerJoin = true }
        val phone = phone(api = api)
        phone.field.giveConsent(CONSENT)
        phone.round(gameId = GameId("gameA"))
        runCurrent()
        val runA = assertNotNull(phone.field.state.value.runId)
        phone.field.onFix(fix(DEVICE + currentTime))

        // A weak network: the last upload keeps trying after the player left, and the consent is taken back meanwhile.
        api.failures = 1_000
        phone.field.onSessionEnded()
        runCurrent()
        advanceTimeBy(2_000)
        phone.field.withdrawConsent()
        runCurrent()

        // The network is back; the tester agrees again and plays the next game.
        api.failures = 0
        phone.field.giveConsent(CONSENT)
        phone.round(gameId = GameId("gameB"))
        runCurrent()
        val runB = assertNotNull(phone.field.state.value.runId)
        assertNotEquals(runA, runB)
        val before = api.uploads.size
        phone.field.onFix(fix(DEVICE + currentTime))
        advanceTimeBy(60_000)
        runCurrent()

        val after = api.uploads.drop(before)
        assertTrue(after.isNotEmpty())
        assertEquals(setOf(runB), after.map { it.runId }.toSet())
    }

    @Test
    fun eachPhoneAsksTheServersClockAtItsOwnMoment() = runTest {
        var asked = 0
        val sync = LabClockSync(
            serverTime = {
                asked++
                SERVER
            },
            deviceTimeMillis = { DEVICE + currentTime },
            monotonicMillis = { currentTime },
        )
        // This phone's moment: 7 s after the join, then 7 s after every 5 minutes.
        val phone = phone(clockSync = sync, random = FixedRandom(7_000))
        phone.field.giveConsent(CONSENT)
        phone.round()
        runCurrent()
        assertTrue(phone.field.isActive)
        // The join's answer until then: no burst of questions with every other phone of the game.
        assertEquals(0, asked)
        advanceTimeBy(6_999)
        runCurrent()
        assertEquals(0, asked)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(LabClockSync.SAMPLES, asked)
        // Measured at 7 s: the next at 7 s + 5 min + 7 s.
        advanceTimeBy(LabClockSync.EVERY_MILLIS + 6_998)
        runCurrent()
        assertEquals(LabClockSync.SAMPLES, asked)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2 * LabClockSync.SAMPLES, asked)
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
    fun theLobbyOfAGameWithTheRadarShowsTheTouchCard() = runTest {
        val sensors = MutableSharedFlow<LabSensorReading>(extraBufferCapacity = 8)
        val probes = object : LabProbes {
            override fun appState(): String = "screen_on"

            override fun lifecycle(): Flow<String> = MutableSharedFlow()

            override fun sensors(): Flow<LabSensorReading> = sensors

            override fun battery(): Flow<LabBattery> = MutableSharedFlow()
        }

        /** A knock of [peakG] over gravity on the sensors' clock: the reading, then a quiet one [after] ms later. */
        suspend fun knock(peakG: Double, after: Long = 200) {
            val at = currentTime
            sensors.emit(LabSensorReading.Motion(at, 1.0 + peakG, null))
            sensors.emit(LabSensorReading.Motion(at + after, 1.0, null))
        }
        val log = LabLog(isEnabled = false, { DEVICE + currentTime }, { currentTime })
        val api = FakeLabApi()
        val field = FieldSession(
            log = log,
            api = api,
            storage = storage,
            scope = backgroundScope,
            isFieldBuild = true,
            probes = probes,
        )
        fun snapshot(phase: GamePhase, radar: Boolean = true) = testSnapshot(phase = phase, serverTimeMillis = SERVER)
            .let {
                it.copy(
                    settings = it.settings.copy(
                        features = GameFeatures(radar = if (radar) FeatureMode.OPTIONAL else FeatureMode.OFF),
                    ),
                )
            }
        val events = { log.lines().map { Json.parseToJsonElement(it).jsonObject } }

        // Before the join: no card, no radio outside the round.
        field.giveConsent(CONSENT)
        assertNull(field.touchRadioToken(snapshot(GamePhase.LOBBY)))
        field.onSnapshot(testSession, snapshot(GamePhase.LOBBY))
        runCurrent()
        assertTrue(field.isActive)
        assertTrue(field.touchCard.value)
        val token = assertNotNull(field.touchRadioToken(snapshot(GamePhase.LOBBY)))
        assertTrue(RadarToken.isWellFormed(token), token)
        // No ticks in the lobby, and nothing new to send after the join's first upload.
        advanceTimeBy(35_000)
        runCurrent()
        assertTrue(events().none { it.kind == FieldKinds.TICK })
        // The first sync says the phase; it goes up with the next upload.
        field.onSyncSent()
        field.onSynced(Transport.POLLING, snapshot(GamePhase.LOBBY))
        advanceTimeBy(15_000)
        runCurrent()
        val quiet = api.uploads.size
        // The lobby's polls go on all the while: the ones that change nothing are not news.
        repeat(30) {
            field.onSyncSent()
            field.onSynced(Transport.POLLING, snapshot(GamePhase.LOBBY))
            advanceTimeBy(2_000)
            runCurrent()
        }
        assertEquals(quiet, api.uploads.size, "a quiet lobby sends nothing")

        // The neighbours touch: a knock, both press «We touched»; it goes up at once.
        knock(1.75, after = 400)
        runCurrent()
        assertEquals(0, field.touchCount.value)
        assertTrue(field.touched(PlayerId("player-2")))
        assertEquals(1, field.touchCount.value, "the card counts the touches")
        runCurrent()
        val touches = api.uploads.drop(quiet).flatMap { it.lines }.map { Json.parseToJsonElement(it).jsonObject }
            .filter { it.kind == IMPACT || it.kind == FieldKinds.MARK }
        assertEquals(listOf(IMPACT, FieldKinds.MARK), touches.map { it.kind }, "the lab's knock and its truth")
        assertEquals(1.75, touches.first().double("peak"))
        assertEquals(400L, touches.first().long("ago"))
        assertEquals(TouchDetector.TOUCH_ACTION, touches.last().string("action"))
        assertEquals(
            TouchDetector.LABEL_PREFIX + RunStep.pairKey(log.label.value, "player-2"),
            touches.last().string("label"),
        )

        // The round: no card, no touch radio, the ticks and the shadow's pocket start.
        field.onSnapshot(testSession, snapshot(GamePhase.HIDING))
        assertFalse(field.touchCard.value)
        assertNull(field.touchRadioToken(snapshot(GamePhase.HIDING)))
        assertFalse(field.touched(PlayerId("player-2")))
        assertEquals(1, field.touchCount.value, "a press with no card is not counted")
        knock(2.0)
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(1, events().count { it.kind == IMPACT }, "no knocks in the round")
        assertTrue(events().any { it.kind == FieldKinds.TICK })

        // On the results, again; the round's ticks stop, so a quiet results screen sends nothing.
        field.onSnapshot(testSession, snapshot(GamePhase.FINISHED))
        assertTrue(field.touchCard.value)
        assertEquals(token, field.touchRadioToken(snapshot(GamePhase.FINISHED)))
        advanceTimeBy(15_000)
        runCurrent()
        val ticks = events().count { it.kind == FieldKinds.TICK }
        val uploads = api.uploads.size
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(ticks, events().count { it.kind == FieldKinds.TICK }, "no tick on the results")
        assertEquals(uploads, api.uploads.size, "quiet results send nothing")

        // «Not now»: no card, no touch radio, no jolts on this screen.
        field.dismissTouch()
        assertFalse(field.touchCard.value)
        assertNull(field.touchRadioToken(snapshot(GamePhase.FINISHED)))
        knock(2.5)
        runCurrent()
        field.onSnapshot(testSession, snapshot(GamePhase.FINISHED))
        assertFalse(field.touchCard.value, "dismissed until the phase changes")
        assertTrue(events().none { it.kind == IMPACT && it.double("peak") == 2.5 })

        // A game without the radar has none.
        field.onSnapshot(testSession, snapshot(GamePhase.FINISHED, radar = false))
        assertFalse(field.touchCard.value)
        field.onSessionEnded()
        assertFalse(field.touchCard.value)
    }

    @Test
    fun theReleaseBuildHasNoTouchCard() = runTest {
        val release = phone(isFieldBuild = false)
        release.field.giveConsent(CONSENT)
        val lobby = testSnapshot(phase = GamePhase.LOBBY, serverTimeMillis = SERVER)
            .let { it.copy(settings = it.settings.copy(features = GameFeatures(radar = FeatureMode.OPTIONAL))) }
        release.field.onSnapshot(testSession, lobby)
        runCurrent()
        assertFalse(release.field.touchCard.value)
        assertNull(release.field.touchRadioToken(lobby))
        assertFalse(release.field.touched(PlayerId("player-2")))
        assertTrue(release.log.lines().isEmpty())
    }

    @Test
    fun theRoundOfAGameWithTheRadarTurnsThePocketOnAndTheLogSaysSo() = runTest {
        val screen = object : LabScreen {
            override val canTurnOffByProximity = true
            var on = false

            override fun setOffByProximity(on: Boolean) {
                this.on = on
            }
        }
        val lifecycle = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val probes = object : LabProbes {
            override fun appState(): String = "active"

            override fun lifecycle(): Flow<String> = lifecycle

            override fun sensors(): Flow<LabSensorReading> = MutableSharedFlow()

            override fun battery(): Flow<LabBattery> = MutableSharedFlow()
        }
        fun phone(isFieldBuild: Boolean): Pair<FieldSession, LabLog> {
            val log = LabLog(isEnabled = false, { DEVICE + currentTime }, { currentTime })
            val field = FieldSession(
                log = log,
                api = FakeLabApi(),
                storage = storage,
                scope = backgroundScope,
                isFieldBuild = isFieldBuild,
                probes = probes,
                pocket = FieldPocket(screen = screen),
            )
            return field to log
        }
        fun snapshot(phase: GamePhase) = testSnapshot(phase = phase, serverTimeMillis = SERVER)
            .let { it.copy(settings = it.settings.copy(features = GameFeatures(radar = FeatureMode.OPTIONAL))) }

        // Release and debug: whatever the game says, the pocket stays as it was.
        val (release, releaseLog) = phone(isFieldBuild = false)
        release.giveConsent(CONSENT)
        release.onSnapshot(testSession, snapshot(GamePhase.SEEKING))
        release.onPulse(RadarBand.HOT)
        release.onScreenChanged(false)
        release.onScreenChanged(true)
        runCurrent()
        assertFalse(screen.on)
        assertTrue(releaseLog.lines().isEmpty())

        val (field, log) = phone(isFieldBuild = true)
        val modes = { log.lines().map { Json.parseToJsonElement(it).jsonObject }.filter { it.kind == "mode" } }
        field.giveConsent(CONSENT)
        field.onSnapshot(testSession, snapshot(GamePhase.LOBBY))
        runCurrent()
        assertTrue(field.isActive)
        assertFalse(screen.on, "not in the lobby")
        field.onSnapshot(testSession, snapshot(GamePhase.SEEKING))
        assertTrue(screen.on)
        assertTrue(field.pocket.hint.value)
        val on = modes().last()
        assertEquals(FieldPocket.PROXIMITY_SCREEN to "on", on.string("mode") to on.string("event"))
        // The app's life while the round wants the sensor; the app away turns it off.
        lifecycle.emit("will_resign")
        runCurrent()
        field.onScreenChanged(false)
        assertFalse(screen.on)
        assertEquals(
            listOf("app" to "will_resign", "app" to "off_screen", "off" to "off_screen"),
            modes().takeLast(3).map { it.string("event") to it.string("reason") },
        )
        field.onScreenChanged(true)
        assertTrue(screen.on)
        // The phone leaves the game: off, written before the log stops.
        field.onSessionEnded()
        assertFalse(screen.on)
        assertEquals("off" to "left", modes().last().let { it.string("event") to it.string("reason") })
    }

    @Test
    fun theCarryShadowReadsTheSensorWhileTheRoundsProximityScreenIsOn() = runTest {
        val screen = object : LabScreen {
            override val canTurnOffByProximity = true
        }
        val sensors = MutableSharedFlow<LabSensorReading>(extraBufferCapacity = 64)
        val probes = object : LabProbes {
            // The proximity screen keeps an iPhone active in the pocket.
            override fun appState(): String = "active"

            override fun lifecycle(): Flow<String> = MutableSharedFlow()

            override fun sensors(): Flow<LabSensorReading> = sensors

            override fun battery(): Flow<LabBattery> = MutableSharedFlow()
        }
        val log = LabLog(isEnabled = false, { DEVICE + currentTime }, { currentTime })
        val field = FieldSession(
            log = log,
            api = FakeLabApi(),
            storage = storage,
            scope = backgroundScope,
            isFieldBuild = true,
            probes = probes,
            pocket = FieldPocket(screen = screen),
        )
        val seeking = testSnapshot(phase = GamePhase.SEEKING, serverTimeMillis = SERVER)
            .let { it.copy(settings = it.settings.copy(features = GameFeatures(radar = FeatureMode.OPTIONAL))) }
        field.giveConsent(CONSENT)
        field.onSnapshot(testSession, seeking)
        runCurrent()
        assertTrue(field.pocket.isProximityOn)
        sensors.emit(LabSensorReading.Proximity(near = true, monitoring = true))
        repeat(30) {
            sensors.emit(LabSensorReading.Motion(currentTime, if (it % 2 == 0) 0.8 else 1.2, null))
            advanceTimeBy(100)
            runCurrent()
        }
        val carry = log.lines().map { Json.parseToJsonElement(it).jsonObject }
            .filter { it.kind == "shadow" && it.string("tech") == CarryShadow.CARRY_V2 }
        assertEquals("in_pocket", carry.last().string("state"), "$carry")
        // The first second came before the sensor said «near»: the hand; from then on, never «screen_on».
        assertTrue(carry.drop(1).none { it.string("reason") == "screen_on" }, "$carry")
        field.onSessionEnded()
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

    /** A random source that always picks [value]. */
    private class FixedRandom(private val value: Long) : Random() {
        override fun nextBits(bitCount: Int): Int = 0

        override fun nextLong(until: Long): Long = value.coerceAtMost(until - 1)
    }

    private companion object {
        const val DEVICE = 1_790_000_000_000L
        const val IMPACT = "impact"

        /** The server's clock in the snapshots: after the field build existed. */
        const val SERVER = 1_790_000_000_000L

        /** When the tester agreed, by a clock that was right. */
        const val CONSENT = SERVER - 60_000L
    }
}

private val JsonObject.kind: String get() = string(LabFields.K).orEmpty()

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.int

private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.content?.toLong()

private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.content?.toDouble()
