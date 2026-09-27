package app.hovanki.client.storage

import app.hovanki.client.network.testSession
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientStorageTest {
    private val store = FakeSecureStore()
    private val storage = ClientStorage(store)
    private val account = SavedAccount(
        serverUrl = "https://hovanki.example.org",
        token = "account-token",
        user = UserProfile(UserId("u1"), "anna", "anna@example.org", emailVerified = true, createdAtMillis = 5),
    )

    @Test
    fun savesLoadsAndClearsTheSession() {
        assertNull(storage.loadSession())

        val saved = SavedSession("http://10.0.2.2:8080", testSession)
        storage.saveSession(saved)
        assertEquals(saved, storage.loadSession())
        assertEquals(saved, ClientStorage(store).loadSession(), "a new app process reads the same session")

        storage.clearSession()
        assertNull(storage.loadSession())
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun unreadableSessionIsDropped() {
        store.values["session"] = "{not json"

        assertNull(storage.loadSession())
        assertFalse("session" in store.values)
    }

    @Test
    fun savesLoadsAndClearsTheAccount() {
        assertNull(storage.loadAccount())

        storage.saveAccount(account)
        assertEquals(account, storage.loadAccount())
        assertEquals(account, ClientStorage(store).loadAccount(), "a new app process reads the same account")
        assertEquals(setOf("account"), store.values.keys)

        storage.clearAccount()
        assertNull(storage.loadAccount())
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun unreadableAccountIsDropped() {
        store.values["account"] = """{"token":"only half of it"}"""

        assertNull(storage.loadAccount())
        assertFalse("account" in store.values)
    }

    @Test
    fun remembersTheGuestName() {
        assertNull(storage.playerName)

        storage.rememberPlayer("Anna")

        assertEquals("Anna", storage.playerName)
        assertEquals(setOf("playerName"), store.values.keys, "no server address any more")
    }

    @Test
    fun forgetsTheServerAddressOfOlderVersions() {
        store.values["serverUrl"] = "http://192.168.1.10:8080"
        store.values["playerName"] = "Anna"

        val storage = ClientStorage(store)

        assertFalse("serverUrl" in store.values)
        assertEquals("Anna", storage.playerName)
    }

    @Test
    fun storeErrorsLookLikeNothingStored() {
        store.failing = true
        val storage = ClientStorage(store)

        storage.saveSession(SavedSession("http://localhost:8080", testSession))
        storage.saveAccount(account)
        storage.rememberPlayer("Anna")
        storage.clearSession()
        storage.clearAccount()

        assertNull(storage.loadSession())
        assertNull(storage.loadAccount())
        assertNull(storage.playerName)
    }
}
