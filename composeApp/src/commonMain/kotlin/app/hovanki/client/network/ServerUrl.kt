package app.hovanki.client.network

import app.hovanki.client.defaultServerUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Base URL of the game server. Editable on the start screen, because a real phone can't reach
 * the emulator/simulator default and has to use the development machine's LAN address.
 */
class ServerUrl(initial: String = defaultServerUrl()) {
    private val url = MutableStateFlow(normalize(initial))

    val flow: StateFlow<String> = url.asStateFlow()

    var value: String
        get() = url.value
        set(value) {
            url.value = normalize(value)
        }

    private fun normalize(value: String): String = value.trim().trimEnd('/')
}
