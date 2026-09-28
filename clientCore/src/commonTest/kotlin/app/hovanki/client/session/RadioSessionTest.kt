package app.hovanki.client.session

import app.hovanki.client.network.FakeGameApi
import app.hovanki.client.network.PollingGameConnection
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.testSession
import app.hovanki.client.network.testSnapshot
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.storage.FakeSecureStore
import app.hovanki.client.storage.SavedSession
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.FeatureMode
import app.hovanki.shared.protocol.GameFeatures
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.MyState
import app.hovanki.shared.protocol.NearbySighting
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.rules.CheckpointPayload
import app.hovanki.shared.rules.RadarToken
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The radar on the phone (docs/adr/0010-nearby-radar.md): the app advertises its token while a round with the radar
 * runs, and sends whom it heard, with what the phone says about itself, with the next sync.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioSessionTest {
    private val storage = ClientStorage(FakeSecureStore())
    private val radio = FakeRadio()
    private val secret = "00112233445566778899aabbccddeeff00112233"
    private val serverNow = 1_700_000_000_000L

    /** The phone's clock: 10 s behind the server's, and it advances with it ([snapshots]). */
    private var deviceNow = serverNow - 10_000L
    private var syncs = 0

    /** A snapshot per sync, a second apart in server time. */
    private fun snapshots(phase: () -> GamePhase, withRadar: Boolean = true): FakeGameApi = FakeGameApi(
        onBoard = { round(GamePhase.SEEKING, withRadar) },
    ) {
        val serverTime = serverNow + syncs++ * 1_000L
        deviceNow = serverTime - 10_000L
        round(phase(), withRadar, serverTime)
    }

    private fun TestScope.manager(api: FakeGameApi) = GameSessionManager(
        api,
        PollingGameConnection(api),
        ServerClock { deviceNow },
        FakeLocationProvider(),
        FakeBackgroundTracker(),
        ServerUrl("http://10.0.2.2:8080"),
        storage,
        backgroundScope,
        radio = radio,
        deviceInfo = FakeDeviceInfo(Platform.IOS),
    )

    private fun round(phase: GamePhase, withRadar: Boolean = true, serverTimeMillis: Long = serverNow): GameSnapshot {
        val snapshot = testSnapshot(serverTimeMillis = serverTimeMillis, syncIntervalSeconds = 1, phase = phase)
        val me =
            MyState(testSession.playerId, Role.HIDER, PlayerStatus.ACTIVE, radarSecret = secret.takeIf { withRadar })
        return snapshot.copy(
            settings = snapshot.settings.copy(
                features = GameFeatures(radar = if (withRadar) FeatureMode.OPTIONAL else FeatureMode.OFF),
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
        // Before the round said so, the sync only told what the phone is.
        val first = api.syncRequests.first()
        assertEquals(emptyList(), first.nearby)
        assertEquals(Platform.IOS, assertNotNull(first.device).platform)
        assertEquals(BluetoothState.ON, first.device?.bluetooth)

        // Heard at the phone's time, reported in server time; four readings per phone are plenty.
        repeat(6) { radio.hears("0123abcd", -70 - it, atMillis = serverNow - 10_000L + it) }
        radio.hears("89abcdef", -85, atMillis = serverNow - 10_000L)
        runCurrent()
        val before = api.syncRequests.size
        manager.state.first { api.syncRequests.size >= before + 2 }
        val withSightings = api.syncRequests.single { it.nearby.isNotEmpty() }
        assertEquals(
            listOf(-72, -73, -74, -75).map { NearbySighting("0123abcd", it, serverNow + (-70 - it)) } +
                NearbySighting("89abcdef", -85, serverNow),
            withSightings.nearby,
            "sent once, in server time",
        )
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
