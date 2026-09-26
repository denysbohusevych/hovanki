package app.hovanki.client.storage

import app.hovanki.client.network.testSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientStorageTest {
    private val store = FakeSecureStore()
    private val storage = ClientStorage(store)

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
    fun remembersTheStartScreenFields() {
        assertNull(storage.playerName)
        assertNull(storage.serverUrl)

        storage.rememberPlayer("Anna", "http://192.168.1.10:8080")

        assertEquals("Anna", storage.playerName)
        assertEquals("http://192.168.1.10:8080", storage.serverUrl)
    }

    @Test
    fun storeErrorsLookLikeNothingStored() {
        store.failing = true

        storage.saveSession(SavedSession("http://localhost:8080", testSession))
        storage.rememberPlayer("Anna", "http://localhost:8080")
        storage.clearSession()

        assertNull(storage.loadSession())
        assertNull(storage.playerName)
    }
}
