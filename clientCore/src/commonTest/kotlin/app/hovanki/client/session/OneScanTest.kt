package app.hovanki.client.session

import app.hovanki.client.network.ApiException
import app.hovanki.client.network.FakeGameApi
import app.hovanki.client.network.PollingGameConnection
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.network.testSession
import app.hovanki.client.network.testSnapshot
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.storage.FakeSecureStore
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.CatchView
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.SessionResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The seeker scans a hider's QR code before any claim: one request finds them. */
@OptIn(ExperimentalCoroutinesApi::class)
class OneScanTest {
    private val anna = PlayerId("anna")
    private val claimId = CatchId("c1")

    private fun claim(status: CatchStatus) = CatchView(claimId, testSession.playerId, anna, status, createdAtMillis = 0)

    private fun seeking(time: Long, vararg catches: CatchView) =
        testSnapshot(serverTimeMillis = time, phase = GamePhase.SEEKING).copy(catches = catches.toList())

    private fun TestScope.manager(api: FakeGameApi) = GameSessionManager(
        api,
        PollingGameConnection(api),
        ServerClock { 0L },
        FakeLocationProvider(),
        FakeBackgroundTracker(),
        ServerUrl("http://localhost:8080"),
        ClientStorage(FakeSecureStore()),
        backgroundScope,
    )

    private fun api(
        onClaim: suspend (PlayerId, String?) -> app.hovanki.shared.protocol.GameSnapshot,
        onConfirm: suspend (CatchId, String) -> app.hovanki.shared.protocol.GameSnapshot = { _, _ -> error("unused") },
    ) = FakeGameApi(
        onJoin = { SessionResponse(testSession, seeking(1_000)) },
        onClaim = onClaim,
        onConfirm = onConfirm,
    ) { seeking(1_000) }

    @Test
    fun theClaimCarriesTheScannedCode() = runTest {
        val api = api(onClaim = { _, _ -> seeking(2_000, claim(CatchStatus.CONFIRMED)) })
        val manager = manager(api)
        manager.join("ABC234", "Sam")

        assertTrue(manager.catchByScan(anna, " 1234 "))

        assertEquals(listOf<Pair<PlayerId, String?>>(anna to "1234"), api.claims)
        assertTrue(api.confirms.isEmpty(), "the server confirmed it already")
        assertEquals(CatchStatus.CONFIRMED, manager.state.value.snapshot?.catches?.single()?.status)
    }

    @Test
    fun aServerWithoutOneScanGetsTheCodeRightAfterTheClaim() = runTest {
        val api = api(
            onClaim = { _, _ -> seeking(2_000, claim(CatchStatus.AWAITING_CODE)) },
            onConfirm = { _, _ -> seeking(3_000, claim(CatchStatus.CONFIRMED)) },
        )
        val manager = manager(api)
        manager.join("ABC234", "Sam")

        assertTrue(manager.catchByScan(anna, "1234"))

        assertEquals(listOf(claimId to "1234"), api.confirms)
        assertEquals(CatchStatus.CONFIRMED, manager.state.value.snapshot?.catches?.single()?.status)
    }

    @Test
    fun aWrongCodeIsAnError() = runTest {
        val wrong = ApiError(ErrorCode.INVALID_CODE, "Wrong code, attempts left: 4")
        val api = api(onClaim = { _, _ -> throw ApiException(422, wrong) })
        val manager = manager(api)
        manager.join("ABC234", "Sam")

        assertFalse(manager.catchByScan(anna, "0000"))

        val error = manager.state.value.lastError
        assertTrue(error is SessionError.Rejected && error.code == ErrorCode.INVALID_CODE, "$error")
        assertTrue(api.confirms.isEmpty())
    }
}
