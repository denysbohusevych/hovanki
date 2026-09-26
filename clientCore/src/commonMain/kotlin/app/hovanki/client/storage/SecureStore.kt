package app.hovanki.client.storage

/**
 * Small key-value storage on the device that survives app restarts and is fit for secrets (the session token).
 * Platform implementations live in :composeApp: Android encrypts the values with an AES key kept in the Android
 * Keystore, iOS keeps them in the Keychain (docs/adr/0002-session-storage.md). Bots and tests use in-memory ones.
 *
 * Implementations may throw on platform errors; [ClientStorage] handles that. They must never log values.
 */
interface SecureStore {
    fun read(key: String): String?

    fun write(key: String, value: String)

    fun remove(key: String)
}
