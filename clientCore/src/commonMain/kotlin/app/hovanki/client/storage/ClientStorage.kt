package app.hovanki.client.storage

import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.protocolJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** The game this device is in, saved to resume it after the app was killed. */
@Serializable
data class SavedSession(
    /** The server that issued [session]; the app talks to it again when it resumes. */
    val serverUrl: String,
    val session: PlayerSession,
)

/**
 * What the app remembers between launches: the running game (its session token is a secret, hence [SecureStore])
 * and the start screen's player name and server address.
 *
 * Storage is a convenience, never a reason to crash: a value that can't be read or written (Keystore or Keychain
 * error, data from an older app version) is treated as absent. Location data is never stored.
 */
class ClientStorage(private val store: SecureStore) {
    fun loadSession(): SavedSession? {
        val json = read(SESSION) ?: return null
        return try {
            protocolJson.decodeFromString(SavedSession.serializer(), json)
        } catch (e: IllegalArgumentException) {
            // Unreadable (e.g. written by another app version): nothing to resume.
            remove(SESSION)
            null
        }
    }

    fun saveSession(saved: SavedSession) {
        write(SESSION, protocolJson.encodeToString(SavedSession.serializer(), saved))
    }

    fun clearSession() {
        remove(SESSION)
    }

    /** Name the player entered last time. */
    val playerName: String? get() = read(PLAYER_NAME)

    /** Server address the player used last time. */
    val serverUrl: String? get() = read(SERVER_URL)

    /** Remembers the start screen's fields once they worked (a game was created or joined with them). */
    fun rememberPlayer(playerName: String, serverUrl: String) {
        write(PLAYER_NAME, playerName)
        write(SERVER_URL, serverUrl)
    }

    private fun read(key: String): String? = attempt { store.read(key) }?.takeIf { it.isNotBlank() }

    private fun write(key: String, value: String) {
        attempt { store.write(key, value) }
    }

    private fun remove(key: String) {
        attempt { store.remove(key) }
    }

    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val SESSION = "session"
        const val PLAYER_NAME = "playerName"
        const val SERVER_URL = "serverUrl"
    }
}
