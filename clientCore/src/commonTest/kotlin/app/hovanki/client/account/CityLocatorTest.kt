package app.hovanki.client.account

import app.hovanki.client.location.LocationProvider
import app.hovanki.client.network.ServerUrl
import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.storage.FakeSecureStore
import app.hovanki.client.storage.SavedAccount
import app.hovanki.shared.protocol.CityRequest
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.UserProfile
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The city of the city leaderboard from where the phone is (docs/adr/0022-city-leaderboard.md). */
class CityLocatorTest {
    private val server = "https://hovanki.example.org"
    private val storage = ClientStorage(FakeSecureStore())
    private val api = FakeAccountApi()

    private class Gps(var permission: Boolean = true, var fixes: List<LocationSample> = emptyList()) :
        LocationProvider {
        override fun hasPermission() = permission

        override fun locationUpdates(intervalMillis: Long): Flow<LocationSample> = flow {
            fixes.forEach { emit(it) }
            awaitCancellation()
        }
    }

    private fun TestScope.loggedIn(user: UserProfile = testUser): AccountManager {
        api.user = user
        storage.saveAccount(SavedAccount(server, TEST_ACCOUNT_TOKEN, user))
        return AccountManager(api, storage, ServerUrl(server), backgroundScope).also {
            it.restore()
            runCurrent()
            api.calls.clear()
            api.requests.clear()
        }
    }

    private fun fix(lat: Double, lon: Double, accuracy: Double = 20.0) =
        LocationSample(GeoPoint(lat, lon), accuracyMeters = accuracy, timestampMillis = 0)

    @Test
    fun theCityAroundTheFixGoesToTheAccountWithoutThePosition() = runTest {
        val account = loggedIn()
        val gps = Gps(fixes = listOf(fix(49.84, 24.03, accuracy = 50_000.0), fix(50.4547, 30.5238)))

        assertEquals(CityLookup.Found("kyiv"), CityLocator(account, gps).locate())
        assertEquals("kyiv", account.state.value.user?.city)
        assertEquals(listOf<Any>(CityRequest("kyiv")), api.requests, "the city only, the rough fix skipped")

        api.calls.clear()
        assertEquals(CityLookup.Found("kyiv"), CityLocator(account, gps).locate())
        assertTrue(api.calls.isEmpty(), "the same city: nothing to send")
    }

    @Test
    fun outsideEveryCityForgetsTheCity() = runTest {
        val account = loggedIn(testUser.copy(city = "lviv"))
        val gps = Gps(fixes = listOf(fix(50.0, 31.5)))

        assertEquals(CityLookup.Found(null), CityLocator(account, gps).locate())
        assertEquals(null, account.state.value.user?.city)
    }

    @Test
    fun noPermissionOrNoFixChangesNothing() = runTest {
        val account = loggedIn(testUser.copy(city = "lviv"))
        assertEquals(CityLookup.NoPermission, CityLocator(account, Gps(permission = false)).locate())
        assertEquals(CityLookup.NoFix, CityLocator(account, Gps(), timeoutMillis = 1_000).locate())
        assertEquals("lviv", account.state.value.user?.city)
        assertTrue(api.calls.isEmpty())
    }
}
