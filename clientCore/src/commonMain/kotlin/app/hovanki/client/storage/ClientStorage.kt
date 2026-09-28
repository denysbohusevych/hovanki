package app.hovanki.client.storage

import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.GameSetup
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/** The game this device is in, saved to resume it after the app was killed. */
@Serializable
data class SavedSession(
    /** The server that issued [session]; a session of another server is dropped instead of resumed. */
    val serverUrl: String,
    val session: PlayerSession,
)

/** The logged-in account, saved so the app starts logged in. */
@Serializable
data class SavedAccount(
    /** The server the account belongs to; an account of another server is dropped (debug builds can switch). */
    val serverUrl: String,
    /** The account token: a secret, like the game token. */
    val token: String,
    /** The profile as last seen, so the app starts logged in without waiting for the server. */
    val user: UserProfile,
)

/**
 * What the app remembers between launches: the running game and the logged-in account (their tokens are secrets,
 * hence [SecureStore]), the name a guest entered last time and the game setup the host chose last time.
 *
 * Storage is a convenience, never a reason to crash: a value that can't be read or written (Keystore or Keychain
 * error, data from an older app version) is treated as absent. Location data is never stored.
 */
class ClientStorage(private val store: SecureStore) {
    init {
        // Older app versions remembered the server address typed on the start screen; there is one server now.
        remove(LEGACY_SERVER_URL)
    }

    fun loadSession(): SavedSession? = load(SESSION, SavedSession.serializer())

    fun saveSession(saved: SavedSession) {
        save(SESSION, SavedSession.serializer(), saved)
    }

    fun clearSession() {
        remove(SESSION)
    }

    fun loadAccount(): SavedAccount? = load(ACCOUNT, SavedAccount.serializer())

    fun saveAccount(saved: SavedAccount) {
        save(ACCOUNT, SavedAccount.serializer(), saved)
    }

    fun clearAccount() {
        remove(ACCOUNT)
    }

    /** Name the player entered last time (as a guest; a logged-in player plays under their nickname). */
    val playerName: String? get() = read(PLAYER_NAME)

    /** Remembers the start screen's name once it worked (a game was created or joined with it). */
    fun rememberPlayer(playerName: String) {
        write(PLAYER_NAME, playerName)
    }

    /** The setup the host chose last time on this phone: sizes and times, never a place. */
    fun loadGameSetup(): GameSetup? = load(GAME_SETUP, GameSetup.serializer())

    fun saveGameSetup(setup: GameSetup) {
        save(GAME_SETUP, GameSetup.serializer(), setup)
    }

    private fun <T> load(key: String, serializer: KSerializer<T>): T? {
        val json = read(key) ?: return null
        return try {
            protocolJson.decodeFromString(serializer, json)
        } catch (e: IllegalArgumentException) {
            // Unreadable (e.g. written by another app version): as if nothing was saved.
            remove(key)
            null
        }
    }

    private fun <T> save(key: String, serializer: KSerializer<T>, value: T) {
        write(key, protocolJson.encodeToString(serializer, value))
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
        const val ACCOUNT = "account"
        const val PLAYER_NAME = "playerName"
        const val GAME_SETUP = "gameSetup"
        const val LEGACY_SERVER_URL = "serverUrl"
    }
}
