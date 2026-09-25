package app.hovanki.client.network

import app.cash.turbine.test
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ErrorCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PollingGameConnectionTest {
    private class Offline : Exception("offline")

    @Test
    fun emitsSnapshotsAtTheSyncInterval() = runTest {
        var polls = 0L
        val api = FakeGameApi { testSnapshot(serverTimeMillis = ++polls, syncIntervalSeconds = 3) }

        PollingGameConnection(api).connect(testSession, LocationOutbox()).test {
            assertEquals(1L, assertIs<ConnectionEvent.Snapshot>(awaitItem()).snapshot.serverTimeMillis)
            assertEquals(0L, time())
            assertEquals(2L, assertIs<ConnectionEvent.Snapshot>(awaitItem()).snapshot.serverTimeMillis)
            assertEquals(3_000L, time())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sendsQueuedSamplesWithTheNextSync() = runTest {
        val api = FakeGameApi { testSnapshot() }
        val outbox = LocationOutbox()
        outbox.add(testSample(1))
        outbox.add(testSample(2))

        PollingGameConnection(api).connect(testSession, outbox).test {
            awaitItem()
            assertEquals(listOf(testSample(1), testSample(2)), api.syncRequests.single().samples)
            assertEquals(0, outbox.size)

            outbox.add(testSample(3))
            awaitItem()
            assertEquals(listOf(testSample(3)), api.syncRequests[1].samples)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun requeuesSamplesAndBacksOffOnFailure() = runTest {
        var attempts = 0
        val api = FakeGameApi {
            attempts++
            if (attempts <= 2) throw Offline()
            testSnapshot()
        }
        val outbox = LocationOutbox()
        outbox.add(testSample(1))

        PollingGameConnection(api).connect(testSession, outbox).test {
            val first = assertIs<ConnectionEvent.Problem>(awaitItem())
            assertIs<Offline>(first.error)
            assertEquals(1_000L, first.retryInMillis)
            assertEquals(1, outbox.size)

            assertEquals(2_000L, assertIs<ConnectionEvent.Problem>(awaitItem()).retryInMillis)
            assertEquals(1_000L, time())

            assertIs<ConnectionEvent.Snapshot>(awaitItem())
            assertEquals(3_000L, time())
            // The same sample went out with every attempt, and is gone once it was delivered.
            assertTrue(api.syncRequests.all { it.samples == listOf(testSample(1)) })
            assertEquals(3, api.syncRequests.size)
            assertEquals(0, outbox.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun backoffIsCapped() = runTest {
        val api = FakeGameApi { throw Offline() }

        PollingGameConnection(api).connect(testSession, LocationOutbox()).test {
            val delays = List(6) { assertIs<ConnectionEvent.Problem>(awaitItem()).retryInMillis }
            assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L), delays)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun dropsSamplesTheServerRejected() = runTest {
        var attempts = 0
        val api = FakeGameApi {
            attempts++
            if (attempts == 1) throw ApiException(400, ApiError(ErrorCode.BAD_REQUEST, "Too many samples"))
            testSnapshot()
        }
        val outbox = LocationOutbox()
        outbox.add(testSample(1))

        PollingGameConnection(api).connect(testSession, outbox).test {
            assertIs<ConnectionEvent.Problem>(awaitItem())
            assertEquals(0, outbox.size)
            assertIs<ConnectionEvent.Snapshot>(awaitItem())
            assertEquals(emptyList(), api.syncRequests[1].samples)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun endsWhenTheSessionIsRejected() = runTest {
        val api = FakeGameApi { throw ApiException(401, ApiError(ErrorCode.UNAUTHORIZED, "Unknown token")) }

        PollingGameConnection(api).connect(testSession, LocationOutbox()).test {
            assertEquals(ConnectionEvent.Ended(EndReason.SESSION_REJECTED), awaitItem())
            awaitComplete()
        }
        assertEquals(1, api.syncRequests.size)
    }

    @Test
    fun endsWhenTheGameIsGone() = runTest {
        val api = FakeGameApi { throw ApiException(404, null) }

        PollingGameConnection(api).connect(testSession, LocationOutbox()).test {
            assertEquals(ConnectionEvent.Ended(EndReason.GAME_NOT_FOUND), awaitItem())
            awaitComplete()
        }
    }

    private fun TestScope.time(): Long = currentTime
}
