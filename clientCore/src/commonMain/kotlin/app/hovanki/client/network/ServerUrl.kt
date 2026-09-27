package app.hovanki.client.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Base URL of the game server: the build's server (`BuildConstants.SERVER_URL` in :composeApp), or in debug builds the
 * development machine or [app.hovanki.client.automation.LaunchOptions.serverUrl]. Set before the saved game and
 * account are restored: those of another server are dropped.
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
