package app.hovanki.client.bigGames

import app.hovanki.client.account.AccountManager
import app.hovanki.client.account.FakeAccountApi
import app.hovanki.client.account.TEST_ACCOUNT_TOKEN
import app.hovanki.client.account.testUser
import app.hovanki.client.network.ApiException
import app.hovanki.client.network.ApiResult
import app.hovanki.client.network.BigGameApi
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.storage.FakeSecureStore
import app.hovanki.client.storage.SavedAccount
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.BigGameCard
import app.hovanki.shared.protocol.BigGameId
import app.hovanki.shared.protocol.BigGameStatus
import app.hovanki.shared.protocol.BigGamesResponse
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.protocol.ZonePolygon
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BigGameManagerTest {
    private val server = "https://hovanki.example.org"
    private val storage = ClientStorage(FakeSecureStore())
    private val api = FakeBigGameApi()
    private lateinit var account: AccountManager

    private fun TestScope.manager(user: UserProfile? = testUser): BigGameManager {
        if (user != null) storage.saveAccount(SavedAccount(server, TEST_ACCOUNT_TOKEN, user))
        account = AccountManager(FakeAccountApi(), storage, ServerUrl(server), backgroundScope)
        account.restore()
        return BigGameManager(api, account, backgroundScope, intervalMillis = 1_000).also { runCurrent() }
    }

    @Test
    fun theListIsPolledWhileTheTabIsOpen() = runTest {
        val manager = manager()
        assertEquals(0, api.lists)

        val watching = backgroundScope.launch { manager.state.collect {} }
        runCurrent()
        assertEquals(1, api.lists)
        assertEquals(listOf("saturday"), manager.state.value.games.map { it.id.value })
        advanceTimeBy(1_001)
        assertEquals(2, api.lists)

        watching.cancel()
        advanceTimeBy(5_000)
        assertEquals(2, api.lists, "nobody watching, no polling")
    }

    @Test
    fun signingUpUpdatesTheCard() = runTest {
        val manager = manager()
        manager.refresh()

        assertIs<ApiResult.Success<Unit>>(manager.signUp(BigGameId("saturday")))
        assertTrue(manager.state.value.games.single().signedUpByMe)
        assertEquals(1, manager.state.value.games.single().signedUp)

        manager.cancelSignup(BigGameId("saturday"))
        assertEquals(0, manager.state.value.games.single().signedUp)
    }

    @Test
    fun aFullGameSaysSo() = runTest {
        val manager = manager()
        manager.refresh()
        api.full = true

        val result = manager.signUp(BigGameId("saturday"))

        assertIs<ApiResult.Rejected>(result)
        assertEquals(ErrorReason.LIMIT_REACHED, result.reason)
    }

    @Test
    fun loggedOutNothing() = runTest {
        val manager = manager(user = null)

        assertIs<ApiResult.Rejected>(manager.refresh())
        assertEquals(0, api.lists)
    }
}

class FakeBigGameApi : BigGameApi {
    var lists = 0
    var full = false
    private var card = BigGameCard(
        id = BigGameId("saturday"),
        title = "Saturday",
        status = BigGameStatus.SCHEDULED,
        startsAtMillis = 1_900_000_000_000,
        timeZone = "Europe/Kyiv",
        zone = ZonePolygon(listOf(GeoPoint(50.0, 30.0), GeoPoint(50.0, 30.01), GeoPoint(50.01, 30.0))),
        playerLimit = 40,
    )

    override suspend fun list(token: String): BigGamesResponse {
        lists++
        return BigGamesResponse(listOf(card))
    }

    override suspend fun signUp(token: String, id: BigGameId): BigGameCard {
        if (full) {
            throw ApiException(
                409,
                ApiError(ErrorCode.WRONG_STATE, "The game is full", ErrorReason.LIMIT_REACHED),
            )
        }
        card = card.copy(signedUpByMe = true, signedUp = 1)
        return card
    }

    override suspend fun cancelSignup(token: String, id: BigGameId): BigGameCard {
        card = card.copy(signedUpByMe = false, signedUp = 0)
        return card
    }
}
