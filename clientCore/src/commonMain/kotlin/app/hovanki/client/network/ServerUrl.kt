package app.hovanki.client.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Base URL of the game server. Editable on the start screen: a real phone reaches a development machine through
 * its LAN address or an HTTPS tunnel. The app starts with its default (`defaultServerUrl()` in :composeApp),
 * which is empty in non-debug builds until a server is deployed.
 */
class ServerUrl(initial: String) {
    private val url = MutableStateFlow(normalize(initial))

    val flow: StateFlow<String> = url.asStateFlow()

    var value: String
        get() = url.value
        set(value) {
            url.value = normalize(value)
        }

    val isBlank: Boolean
        get() = url.value.isEmpty()

    companion object {
        /**
         * Trims spaces and trailing slashes. An address typed without a scheme (`name.trycloudflare.com`) gets
         * `https://`: non-debug builds only talk HTTPS, plain HTTP needs the scheme spelled out.
         */
        fun normalize(value: String): String {
            val url = value.trim().trimEnd('/')
            return if (url.isEmpty() || "://" in url) url else "https://$url"
        }
    }
}
