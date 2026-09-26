package app.hovanki.client.session

import app.hovanki.client.network.ApiException
import app.hovanki.client.network.FakeGameApi
import app.hovanki.client.network.GameApi
import app.hovanki.client.network.PollingGameConnection
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.testSession
import app.hovanki.client.network.testSnapshot
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.storage.FakeSecureStore
import app.hovanki.client.storage.SavedSession
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.SessionResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A killed app comes back into its game: the session is saved on the device and resumed with `sync`. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionResumeTest {
    private class Offline : Exception("offline")

    private val storage = ClientStorage(FakeSecureStore())
    private val serverUrl = ServerUrl("http://10.0.2.2:8080")
    private val locations = FakeLocationProvider()
    private val tracker = FakeBackgroundTracker()
    private val saved = SavedSession("http://192.168.1.10:8080", testSession)

    private fun TestScope.manager(api: GameApi) = GameSessionManager(
        api,
        PollingGameConnection(api),
        ServerClock { 0L },
        locations,
        tracker,
        serverUrl,
        storage,
        backgroundScope,
    )

    @Test
    fun joiningSavesTheSession() = runTest {
        val api = FakeGameApi(onJoin = { SessionResponse(testSession, testSnapshot()) }) { testSnapshot() }

        assertTrue(manager(api).join("abc234", "Anna"))

        assertEquals(SavedSession("http://10.0.2.2:8080", testSession), storage.loadSession())
    }

    @Test
    fun resumesARunningGame() = runTest {
        storage.saveSession(saved)
        val api = FakeGameApi { testSnapshot(phase = GamePhase.SEEKING) }
        val manager = manager(api)

        manager.resumeSavedGame()
        val resuming = manager.state.value
        assertEquals(testSession, resuming.session, "in the game right away: a loading screen, not the start screen")
        assertTrue(resuming.isResuming)
        assertNull(resuming.snapshot)
        assertEquals("http://192.168.1.10:8080", serverUrl.value, "the server that issued the session")

        val resumed = manager.state.first { it.snapshot != null }
        runCurrent()
        assertFalse(resumed.isResuming)
        assertEquals(GamePhase.SEEKING, resumed.snapshot?.phase)
        assertEquals(testSession, api.syncSessions.first(), "synced with the saved token")
        assertTrue(tracker.running, "background tracking is back")
        assertEquals(1, locations.collectors, "location updates are back")
        assertTrue(manager.state.value.isSharingLocation)
        assertEquals(saved, storage.loadSession(), "still saved: the next restart resumes again")
    }

    @Test
    fun finishedGameGoesBackToTheStartScreen() = runTest {
        storage.saveSession(saved)
        val manager = manager(FakeGameApi { testSnapshot(phase = GamePhase.FINISHED) })

        manager.resumeSavedGame()
        val state = manager.state.first { it.session == null }

        assertEquals(SessionError.SavedGameFinished, state.lastError)
        assertNull(storage.loadSession())
        assertFalse(tracker.running)
        assertEquals(0, locations.collectors)
    }

    @Test
    fun deletedGameOrRefusedTokenGoesBackToTheStartScreen() = runTest {
        for ((status, code) in listOf(401 to ErrorCode.UNAUTHORIZED, 404 to ErrorCode.NOT_FOUND)) {
            storage.saveSession(saved)
            val manager = manager(FakeGameApi { throw ApiException(status, ApiError(code, "gone")) })

            manager.resumeSavedGame()
            val state = manager.state.first { it.session == null }

            assertEquals(SessionError.SavedGameGone, state.lastError, "HTTP $status")
            assertNull(storage.loadSession(), "HTTP $status")
        }
    }

    @Test
    fun keepsRetryingWithoutNetwork() = runTest {
        storage.saveSession(saved)
        var attempts = 0
        val manager = manager(
            FakeGameApi {
                attempts++
                if (attempts == 1) throw Offline()
                testSnapshot(phase = GamePhase.HIDING)
            },
        )

        manager.resumeSavedGame()
        val offline = manager.state.first { it.connectionStatus == ConnectionStatus.RECONNECTING }
        assertTrue(offline.isResuming)
        assertEquals(testSession, offline.session)
        assertEquals(saved, storage.loadSession(), "a network problem is no reason to forget the game")

        val resumed = manager.state.first { it.snapshot != null }
        assertEquals(ConnectionStatus.ONLINE, resumed.connectionStatus)
        assertEquals(GamePhase.HIDING, resumed.snapshot?.phase)
    }

    @Test
    fun leavingWhileResumingForgetsTheGame() = runTest {
        storage.saveSession(saved)
        val manager = manager(FakeGameApi { throw Offline() })
        manager.resumeSavedGame()
        manager.state.first { it.connectionStatus == ConnectionStatus.RECONNECTING }

        manager.leave()

        assertEquals(SessionState(), manager.state.value)
        assertNull(storage.loadSession())
    }

    @Test
    fun nothingSavedNothingToResume() = runTest {
        val api = FakeGameApi { testSnapshot() }
        val manager = manager(api)

        manager.resumeSavedGame()
        runCurrent()

        assertEquals(SessionState(), manager.state.value)
        assertTrue(api.syncRequests.isEmpty())
    }

    @Test
    fun resumesOnlyOncePerProcess() = runTest {
        storage.saveSession(saved)
        val manager = manager(FakeGameApi { testSnapshot(phase = GamePhase.SEEKING) })
        manager.resumeSavedGame()
        manager.state.first { it.snapshot != null }
        manager.leave()

        storage.saveSession(saved)
        manager.resumeSavedGame()

        assertNull(manager.state.value.session)
    }

    @Test
    fun forgetSavedGameDropsIt() = runTest {
        storage.saveSession(saved)
        val manager = manager(FakeGameApi { testSnapshot() })

        manager.forgetSavedGame()
        manager.resumeSavedGame()

        assertNull(storage.loadSession())
        assertNull(manager.state.value.session)
    }

    @Test
    fun finishedGameIsForgotten() = runTest {
        val api = FakeGameApi(onJoin = { SessionResponse(testSession, testSnapshot(serverTimeMillis = 1_000)) }) {
            testSnapshot(serverTimeMillis = 2_000, phase = GamePhase.FINISHED)
        }
        val manager = manager(api)

        manager.join("ABC234", "Anna")
        val finished = manager.state.first { it.snapshot?.phase == GamePhase.FINISHED }

        assertEquals(testSession, finished.session, "the results screen")
        assertNull(storage.loadSession(), "nothing to resume after the results")
    }

    @Test
    fun lostSessionIsForgotten() = runTest {
        val api = FakeGameApi(onJoin = { SessionResponse(testSession, testSnapshot()) }) {
            throw ApiException(404, ApiError(ErrorCode.NOT_FOUND, "gone"))
        }
        val manager = manager(api)

        manager.join("ABC234", "Anna")
        val lost = manager.state.first { it.session == null }

        assertEquals(SessionError.SessionLost, lost.lastError)
        assertNull(storage.loadSession())
    }
}
