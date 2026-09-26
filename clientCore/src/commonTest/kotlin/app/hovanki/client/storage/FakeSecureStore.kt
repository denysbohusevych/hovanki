package app.hovanki.client.storage

/** In-memory [SecureStore]; [failing] makes every call throw, like a broken Keystore or Keychain. */
class FakeSecureStore(var failing: Boolean = false) : SecureStore {
    val values = mutableMapOf<String, String>()

    override fun read(key: String): String? {
        check(!failing) { "store unavailable" }
        return values[key]
    }

    override fun write(key: String, value: String) {
        check(!failing) { "store unavailable" }
        values[key] = value
    }

    override fun remove(key: String) {
        check(!failing) { "store unavailable" }
        values.remove(key)
    }
}
