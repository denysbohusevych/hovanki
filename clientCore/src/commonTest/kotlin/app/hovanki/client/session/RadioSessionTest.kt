package app.hovanki.client.session

import app.hovanki.client.diagnostics.Diagnostics
import app.hovanki.client.network.ApiException
import app.hovanki.client.network.ConnectionEvent
import app.hovanki.client.network.FakeGameApi
import app.hovanki.client.network.GameConnection
import app.hovanki.client.network.LocationOutbox
import app.hovanki.client.network.PollingGameConnection
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.SyncExtras
import app.hovanki.client.network.testPlayer
import app.hovanki.client.network.testSession
import app.hovanki.client.network.testSnapshot
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.storage.FakeSecureStore
import app.hovanki.client.storage.SavedSession
import app.hovanki.radar.ChannelMix
import app.hovanki.radar.RadarCatalog
import app.hovanki.radar.RadioSighting
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Carry
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.FeatureMode
import app.hovanki.shared.protocol.GameFeatures
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.MyState
import app.hovanki.shared.protocol.NearbySighting
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.protocol.RadarContact
import app.hovanki.shared.protocol.RadarState
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.rules.CheckpointPayload
import app.hovanki.shared.rules.RadarToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The radar on the phone (docs/adr/0012-nearby-radar.md): the app advertises its token while a round with the radar
 * runs, and sends whom it heard, with what the phone says about itself, with the next sync.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioSessionTest {
    private val storage = ClientStorage(FakeSecureStore())
    private val radio = FakeRadio()
    private val pulse = FakePocketPulse()
    private val carry = FakeCarryMonitor()
    private val secret = "00112233445566778899aabbccddeeff00112233"
    private val seekerSecret = "ffeeddccbbaa99887766554433221100ffeeddcc"
    private val serverNow = 1_700_000_000_000L

    /** What the seeker's phone advertises right now. */
    private val seekerToken get() = RadarToken.at(seekerSecret, serverNow)

    private val hiderSecret = "0102030405060708090a0b0c0d0e0f1011121314"

    /** What a hider's phone advertises right now. */
    private val hiderToken get() = RadarToken.at(hiderSecret, serverNow)

    /** The phone's clock: 10 s behind the server's, and it advances with it ([snapshots]). */
    private var deviceNow = serverNow - 10_000L
    private var syncs = 0

    /** A snapshot per sync, a second apart in server time. */
    private fun snapshots(
        phase: () -> GamePhase,
        withRadar: Boolean = true,
        role: Role = Role.HIDER,
        sense: Boolean = false,
        radar: () -> RadarState? = { null },
        syncIntervalSeconds: Int = 1,
    ): FakeGameApi = FakeGameApi(
        onBoard = { round(GamePhase.SEEKING, withRadar) },
    ) {
        val serverTime = serverNow + syncs++ * 1_000L
        deviceNow = serverTime - 10_000L
        round(phase(), withRadar, serverTime, role, sense, radar(), syncIntervalSeconds)
    }

    private fun TestScope.manager(
        api: FakeGameApi,
        radio: FakeRadio = this@RadioSessionTest.radio,
        diagnostics: Diagnostics = Diagnostics.Off,
        connection: GameConnection = PollingGameConnection(api),
        trace: GameTrace = GameTrace.None,
    ) = GameSessionManager(
        api,
        connection,
        ServerClock { deviceNow },
        FakeLocationProvider(),
        FakeBackgroundTracker(),
        ServerUrl("http://10.0.2.2:8080"),
        storage,
        backgroundScope,
        radio = radio,
        deviceInfo = FakeDeviceInfo(Platform.IOS, model = "iPhone15,2"),
        pocketPulse = pulse,
        carryMonitor = carry,
        diagnostics = diagnostics,
        trace = trace,
    )

    private fun round(
        phase: GamePhase,
        withRadar: Boolean = true,
        serverTimeMillis: Long = serverNow,
        role: Role = Role.HIDER,
        sense: Boolean = false,
        radar: RadarState? = null,
        syncIntervalSeconds: Int = 1,
    ): GameSnapshot {
        val snapshot = testSnapshot(
            serverTimeMillis = serverTimeMillis,
            syncIntervalSeconds = syncIntervalSeconds,
            phase = phase,
        )
        val inSearch = withRadar && phase == GamePhase.SEEKING
        val me = MyState(
            testSession.playerId,
            role,
            PlayerStatus.ACTIVE,
            radarSecret = secret.takeIf { withRadar },
            radar = radar.takeIf { inSearch },
            seekerTokens = if (inSearch && sense &&
                role == Role.HIDER
            ) {
                RadarToken.candidates(seekerSecret, serverTimeMillis)
            } else {
                emptyList()
            },
            hiderTokens = if (inSearch && role == Role.SEEKER) {
                RadarToken.candidates(hiderSecret, serverTimeMillis)
            } else {
                emptyList()
            },
        )
        return snapshot.copy(
            settings = snapshot.settings.copy(
                features = GameFeatures(
                    radar = if (withRadar) FeatureMode.OPTIONAL else FeatureMode.OFF,
                    hiderSense = sense && withRadar,
                ),
            ),
            me = me,
        )
    }

    @Test
    fun thePhoneAdvertisesAndReportsWhatItHeard() = runTest {
        storage.saveSession(SavedSession("http://10.0.2.2:8080", testSession))
        val api = snapshots({ GamePhase.SEEKING })
        val manager = manager(api)

        manager.resumeSavedGame()
        manager.state.first { it.snapshot != null }
        runCurrent()
        assertEquals(1, radio.collectors, "advertising and scanning")
        assertEquals(RadarToken.at(secret, serverNow), assertNotNull(radio.tokens).value)
        assertEquals(false, radio.asSeeker, "a hider advertises the service, not an iBeacon")
        // Before the round said so, the sync only told what the phone is.
        val first = api.syncRequests.first()
        assertEquals(emptyList(), first.nearby)
        assertEquals(Platform.IOS, assertNotNull(first.device).platform)
        assertEquals(BluetoothState.ON, first.device?.bluetooth)
        assertEquals(Carry.IN_HAND, first.device?.carry, "on the screen")
        assertNull(first.device?.model, "the model only once the phone knows the game has the radar")

        // Heard at the phone's time, reported in server time. A burst within the same half second is one reading (its
        // latest); the last eight readings per phone go.
        repeat(3) { radio.hears("0123abcd", -60 - it, atMillis = serverNow - 10_000L + it) }
        repeat(10) { radio.hears("0123abcd", -70 - it, atMillis = serverNow - 10_000L + 1_000L + it * 500L) }
        radio.hears("89abcdef", -85, atMillis = serverNow - 10_000L)
        runCurrent()
        val before = api.syncRequests.size
        manager.state.first { api.syncRequests.size >= before + 2 }
        val withSightings = api.syncRequests.single { it.nearby.isNotEmpty() }
        assertEquals(
            (2..9).map { NearbySighting("0123abcd", -70 - it, serverNow + 1_000L + it * 500L) } +
                NearbySighting("89abcdef", -85, serverNow),
            withSightings.nearby,
            "sent once, in server time",
        )
        assertEquals("iPhone15,2", withSightings.device?.model, "the model, since the game has the radar")
    }

    @Test
    fun theRadioKnowsThePlayersNumberInTheGame() = runTest {
        storage.saveSession(SavedSession("http://10.0.2.2:8080", testSession))
        val others = listOf("bob", "carol").map { PlayerView(PlayerId(it), it, Role.HIDER, PlayerStatus.ACTIVE) }
        val api = FakeGameApi {
            val serverTime = serverNow + syncs++ * 1_000L
            deviceNow = serverTime - 10_000L
            round(GamePhase.SEEKING, serverTimeMillis = serverTime).copy(players = others + testPlayer)
        }
        val numbers = mutableListOf<Int>()
        val trace = object : GameTrace {
            override fun radarChannels(playerNumber: Int): ChannelMix {
                numbers += playerNumber
                return RadarCatalog.field(playerNumber)
            }
        }
        val manager = manager(api, trace = trace)

        manager.resumeSavedGame()
        manager.state.first { it.snapshot != null }
        runCurrent()
        // The third to join: an Android hider's layout goes round the circle by it (docs/adr/0018-field-test-build.md).
        assertEquals(listOf(2), numbers)
        assertEquals(RadarCatalog.field(2), radio.mix)
    }

    @Test
    fun withoutAJournalTheRadioRunsTheGamesChannels() = runTest {
        storage.saveSession(SavedSession("http://10.0.2.2:8080", testSession))
        val api = snapshots({ GamePhase.SEEKING })
        val manager = manager(api)

        manager.resumeSavedGame()
        manager.state.first { it.snapshot != null }
        runCurrent()
        assertEquals(1, radio.collectors)
        assertEquals(null, radio.mix)
    }

    @Test
    fun aDebugBuildShowsWhatThePhoneHeardAndTheSyncs() = runTest {
        storage.saveSession(SavedSession("http://10.0.2.2:8080", testSession))
        val api = snapshots({ GamePhase.SEEKING })
        val diagnostics = Diagnostics(isEnabled = true) { deviceNow }
        val manager = manager(api, diagnostics = diagnostics)

        manager.resumeSavedGame()
        manager.state.first { it.snapshot != null }
        runCurrent()
        assertEquals(RadarToken.at(secret, serverNow), diagnostics.state.value.ownToken)
        radio.hears("0123abcd", -71, atMillis = deviceNow)
        runCurrent()
        val before = api.syncRequests.size
        manager.state.first { api.syncRequests.size >= before + 2 }

        val measured = diagnostics.state.value
        // The raw dBm at the phone's own clock, as the scan reported it.
        val contact = measured.contacts.single()
        assertEquals("0123abcd", contact.token)
        assertEquals(-71, contact.lastRssi)
        assertEquals(RadarBand.WARM, contact.band)
        assertTrue(measured.syncs >= 2)
        assertEquals(Platform.IOS, measured.device?.platform, "what the phone told the server")
        assertTrue(measured.log.any { it.text.startsWith("phase SEEKING") })
    }

    @Test
    fun theLobbyOfAGameWithTheRadarLooksAtBluetooth() = runTest {
        // An iPhone: nothing known of its Bluetooth until the app looks, and then it is on.
        val iphone = FakeRadio(BluetoothState.UNSUPPORTED, onRefresh = BluetoothState.ON)
        storage.saveSession(SavedSession("http://10.0.2.2:8080", testSession))
        val api = snapshots({ GamePhase.LOBBY })
        val manager = manager(api, iphone)

        manager.resumeSavedGame()
        manager.state.first { it.snapshot != null }
        runCurrent()
        assertEquals(BluetoothState.ON, manager.bluetooth.value)
        val before = api.syncRequests.size
        manager.state.first { api.syncRequests.size > before }
        assertEquals(BluetoothState.ON, api.syncRequests.last().device?.bluetooth, "the host may start the game")
    }

    @Test
    fun theLobbyHasNoRadioUnlessTheFieldLogAsksForTheTouch() = runTest {
        storage.saveSession(SavedSession("http://10.0.2.2:8080", testSession))
        var phase = GamePhase.LOBBY
        val heard = ArrayList<String>()
        var touch: String? = null
        val trace = object : GameTrace {
            override fun touchRadioToken(snapshot: GameSnapshot): String? = touch

            override fun onSighting(sighting: RadioSighting) {
                heard += sighting.token
            }
        }
        val api = snapshots({ phase })
        val manager = manager(api, trace = trace)

        manager.resumeSavedGame()
        manager.state.first { it.snapshot != null }
        runCurrent()
        assertEquals(0, radio.collectors, "every build: no radio in the lobby")

        // The field log's touch card: the phone advertises the log's own token as a hider.
        touch = "f00dcafe"
        manager.state.first { api.syncRequests.size >= 3 }
        runCurrent()
        assertEquals(1, radio.collectors)
        assertEquals("f00dcafe", radio.tokens?.value)
        assertEquals(false, radio.asSeeker)
        radio.hears("0123abcd", -45, atMillis = deviceNow)
        runCurrent()
        val before = api.syncRequests.size
        manager.state.first { api.syncRequests.size >= before + 2 }
        assertEquals(listOf("0123abcd"), heard, "what it hears is the journal's")
        assertTrue(api.syncRequests.all { it.nearby.isEmpty() }, "and never the server's")

        // The round: the game's own radio, with the game's token.
        phase = GamePhase.SEEKING
        manager.state.first { it.snapshot?.phase == GamePhase.SEEKING }
        runCurrent()
        assertEquals(1, radio.collectors)
        assertEquals(
            RadarToken.at(secret, assertNotNull(manager.state.value.snapshot).serverTimeMillis),
            radio.tokens?.value,
        )
    }

    @Test
    fun theTouchRadioStopsAsSoonAsTheFieldLogNoLongerAsksOrTheGameIsGone() = runTest {
        storage.saveSession(SavedSession("http://10.0.2.2:8080", testSession))
        var gone = false
        val wanted = MutableStateFlow(true)
        val trace = object : GameTrace {
            override fun touchRadioToken(snapshot: GameSnapshot): String? = "f00dcafe"

            override val touchRadioWanted: Flow<Boolean> get() = wanted
        }
        var phase = GamePhase.LOBBY
        val api = snapshots({
            if (gone) throw ApiException(404, null)
            phase
        })
        val manager = manager(api, trace = trace)
        manager.resumeSavedGame()
        manager.state.first { it.snapshot != null }
        phase = GamePhase.FINISHED
        manager.state.first { it.snapshot?.phase == GamePhase.FINISHED }
        runCurrent()
        assertEquals(1, radio.collectors, "the results' touch card")

        // Dismissed between two snapshots: the radio stops at once; asked again, it is back.
        wanted.value = false
        runCurrent()
        assertEquals(0, radio.collectors)
        wanted.value = true
        runCurrent()
        assertEquals(1, radio.collectors)

        // The server deleted the finished game: no snapshot comes again, the radio stops with the game.
        gone = true
        // The connection polls on its own dispatcher, in real time.
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                while (radio.collectors != 0) delay(10)
            }
        }
        assertEquals(0, radio.collectors)
        assertEquals(GamePhase.FINISHED, manager.state.value.snapshot?.phase, "the results stay")
    }

    @Test
    fun aGameWithoutTheRadarNeverAsksForBluetooth() = runTest {
        val iphone = FakeRadio(BluetoothState.UNSUPPORTED, onRefresh = BluetoothState.ON)
        storage.saveSession(SavedSession("http://10.0.2.2:8080", testSession))
        val api = snapshots({ GamePhase.LOBBY }, withRadar = false)
        val manager = manager(api, iphone)

        manager.resumeSavedGame()
        manager.state.first { it.snapshot != null }
        runCurrent()
        assertEquals(0, iphone.refreshes)
        assertEquals(BluetoothState.UNSUPPORTED, manager.bluetooth.value)
    }

    @Test
    fun theHiderFeelsASeekerComingFromTheirToken() = runTest {
        storage.saveSession(SavedSession("http://10.0.2.2:8080", testSession))
        var phase = GamePhase.SEEKING
        val api = snapshots({ phase }, sense = true)
        val manager = manager(api)
        manager.resumeSavedGame()
        manager.state.first { it.snapshot != null }
        runCurrent()
        assertEquals(RadarBand.NONE, manager.pulse.value)

        // Somebody who is not a seeker of this game, however loud: nothing.
        radio.hears("0123abcd", -40, atMillis = serverNow - 10_000L)
        runCurrent()
        assertEquals(RadarBand.NONE, manager.pulse.value)
        assertEquals(emptyList(), pulse.bands)

        // The seeker's token: the phone smooths it like the server would and beats right away.
        radio.hears(seekerToken, -65, atMillis = serverNow - 10_000L)
        runCurrent()
        assertEquals(RadarBand.HOT, manager.pulse.value)
        assertEquals(listOf(RadarBand.HOT), pulse.bands)
        repeat(2) { radio.hears(seekerToken, -55, atMillis = serverNow - 10_000L + 500L * (it + 1)) }
        runCurrent()
        assertEquals(RadarBand.BURNING, manager.pulse.value)
        assertEquals(listOf(RadarBand.HOT, RadarBand.BURNING), pulse.bands)

        // The round ends: quiet.
        phase = GamePhase.FINISHED
        manager.state.first { it.snapshot?.phase == GamePhase.FINISHED }
        runCurrent()
        assertEquals(RadarBand.NONE, manager.pulse.value)
        assertEquals(RadarBand.NONE, pulse.bands.last())
    }

    @Test
    fun aSeekerFeelsAHiderBeforeTheServerSaysSo() = runTest {
        storage.saveSession(SavedSession("http://10.0.2.2:8080", testSession))
        val api = snapshots({ GamePhase.SEEKING }, role = Role.SEEKER, radar = { RadarState() })
        val manager = manager(api)
        manager.resumeSavedGame()
        manager.state.first { it.snapshot != null }
        runCurrent()
        assertEquals(RadarBand.NONE, manager.pulse.value)

        // A teammate's token (unknown to this phone): nothing, however loud.
        radio.hears("0123abcd", -40, atMillis = deviceNow)
        runCurrent()
        assertEquals(RadarBand.NONE, manager.pulse.value)

        // A hider's token: warm at once, nameless; the server's band per hider is still none.
        radio.hears(hiderToken, -80, atMillis = deviceNow)
        runCurrent()
        assertEquals(RadarBand.WARM, manager.pulse.value)
        assertEquals(RadarBand.NONE, manager.state.value.snapshot?.radarBand())
        assertEquals(listOf(RadarBand.WARM), pulse.bands)
    }

    @Test
    fun somebodyNearMakesThePhoneSyncEverySecond() = runTest {
        storage.saveSession(SavedSession("http://10.0.2.2:8080", testSession))
        val api =
            snapshots({ GamePhase.SEEKING }, role = Role.SEEKER, radar = { RadarState() }, syncIntervalSeconds = 3)
        val connection = SpyConnection(PollingGameConnection(api))
        val manager = manager(api, connection = connection)
        manager.resumeSavedGame()
        manager.state.first { it.snapshot != null }
        runCurrent()
        val snapshot = checkNotNull(manager.state.value.snapshot)
        val pause = checkNotNull(connection.intervalMillis)
        assertEquals(3_000L, pause(snapshot), "the game's pace while nobody is near")

        // The server's band says a hider is near: every second.
        val warm = snapshot.copy(me = snapshot.me.copy(radar = RadarState(listOf(RadarContact(RadarBand.WARM)))))
        assertEquals(GameSessionManager.NEAR_SYNC_MILLIS, pause(warm))

        // So does a close reading of any phone of the game (a teammate's too), for a while.
        radio.hears("0123abcd", -60, atMillis = deviceNow)
        runCurrent()
        assertEquals(GameSessionManager.NEAR_SYNC_MILLIS, pause(snapshot))
        // Outside the search, the game's pace whatever happens.
        assertEquals(3_000L, pause(snapshot.copy(phase = GamePhase.LOBBY)))
    }

    /** The connection the manager opened, and the pause it asks for between syncs. */
    private class SpyConnection(private val inner: GameConnection) : GameConnection {
        var intervalMillis: ((GameSnapshot) -> Long)? = null
            private set

        override fun connect(
            session: PlayerSession,
            outbox: LocationOutbox,
            chatAfter: () -> Long?,
            extras: () -> SyncExtras,
            intervalMillis: (GameSnapshot) -> Long,
        ): Flow<ConnectionEvent> {
            this.intervalMillis = intervalMillis
            return inner.connect(session, outbox, chatAfter, extras, intervalMillis)
        }
    }

    @Test
    fun theSeekersSonarAndTheServersBandCountToo() = runTest {
        storage.saveSession(SavedSession("http://10.0.2.2:8080", testSession))
        var band = RadarBand.NONE
        val api = snapshots(
            { GamePhase.SEEKING },
            role = Role.SEEKER,
            radar = { RadarState(listOf(RadarContact(band, PlayerId("h1")))) },
        )
        val manager = manager(api)
        manager.resumeSavedGame()
        manager.state.first { it.snapshot != null }
        runCurrent()
        assertEquals(true, radio.asSeeker, "a seeker advertises the iBeacon frame")
        assertEquals(RadarBand.NONE, manager.pulse.value)

        band = RadarBand.WARM
        manager.state.first { it.snapshot?.me?.radar?.contacts?.firstOrNull()?.band == RadarBand.WARM }
        runCurrent()
        assertEquals(RadarBand.WARM, manager.pulse.value)
        assertEquals(listOf(RadarBand.WARM), pulse.bands)
        band = RadarBand.NONE
        manager.state.first { it.snapshot?.me?.radar?.contacts?.firstOrNull()?.band == RadarBand.NONE }
        runCurrent()
        assertEquals(RadarBand.NONE, manager.pulse.value)
    }

    @Test
    fun thePhoneSaysWhereItIs() = runTest {
        storage.saveSession(SavedSession("http://10.0.2.2:8080", testSession))
        val api = snapshots({ GamePhase.SEEKING }, sense = true)
        val manager = manager(api)
        manager.resumeSavedGame()
        manager.state.first { it.snapshot != null }
        runCurrent()

        manager.onScreenChanged(false)
        carry.state.value = Carry.IN_POCKET
        manager.state.first { api.syncRequests.last().device?.carry == Carry.IN_POCKET }
        // Heard from the pocket, the seeker's signal is evened out before the band is read.
        radio.hears(seekerToken, -70, atMillis = serverNow - 10_000L)
        runCurrent()
        assertEquals(RadarBand.BURNING, manager.pulse.value, "-70 dBm through the body is about -58 in the open")

        manager.onScreenChanged(true)
        manager.state.first { api.syncRequests.last().device?.carry == Carry.IN_HAND }
    }

    @Test
    fun switchedOffByThePlayerOrWithTheRound() = runTest {
        storage.saveSession(SavedSession("http://10.0.2.2:8080", testSession))
        var phase = GamePhase.SEEKING
        val api = snapshots({ phase })
        val manager = manager(api)
        manager.resumeSavedGame()
        manager.state.first { it.snapshot != null }
        runCurrent()
        assertEquals(1, radio.collectors)

        manager.setRadarEnabled(false)
        runCurrent()
        assertEquals(0, radio.collectors, "the player's choice")
        assertEquals(false, storage.radarEnabled, "remembered")
        manager.state.first { api.syncRequests.last().device?.bluetooth == BluetoothState.OFF_BY_PLAYER }
        manager.setRadarEnabled(true)
        runCurrent()
        assertEquals(1, radio.collectors)

        phase = GamePhase.FINISHED
        manager.state.first { it.snapshot?.phase == GamePhase.FINISHED }
        runCurrent()
        assertEquals(0, radio.collectors, "the round is over")
    }

    @Test
    fun noRadarInAGameWithout() = runTest {
        storage.saveSession(SavedSession("http://10.0.2.2:8080", testSession))
        val api = snapshots({ GamePhase.SEEKING }, withRadar = false)
        val manager = manager(api)
        manager.resumeSavedGame()
        manager.state.first { it.snapshot != null }
        runCurrent()

        assertEquals(0, radio.collectors)
        assertNull(radio.tokens)
    }

    @Test
    fun aCheckpointOfAnotherGameIsRefusedOnThePhone() = runTest {
        storage.saveSession(SavedSession("http://10.0.2.2:8080", testSession))
        val api = snapshots({ GamePhase.SEEKING })
        val manager = manager(api)
        manager.resumeSavedGame()
        manager.state.first { it.snapshot != null }

        assertEquals(false, manager.scanCheckpoint("hovanki:cp:othergame:ABCD2345"))
        assertEquals(ErrorCode.NOT_FOUND, assertIs<SessionError.Rejected>(manager.state.value.lastError).code)
        assertEquals(false, manager.scanCheckpoint("https://example.com"))
        assertEquals(true, manager.scanCheckpoint(CheckpointPayload(testSession.gameId, "ABCD2345").encode()))
        assertEquals(listOf("ABCD2345"), api.scannedCodes)
    }
}
