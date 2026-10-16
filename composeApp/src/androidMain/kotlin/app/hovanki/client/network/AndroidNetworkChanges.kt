package app.hovanki.client.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import app.hovanki.client.network.live.NetworkChanges
import java.util.concurrent.atomic.AtomicLong

/**
 * The phone's default network (docs/live-v2/client.md §7): the generation grows when another network becomes the
 * default (Wi-Fi ↔ mobile) or the one in use goes. Watched from the first question on, for as long as the app lives.
 */
class AndroidNetworkChanges(private val context: Context) : NetworkChanges {
    private val generation = AtomicLong()
    private var current: Network? = null

    private val watching by lazy {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@lazy false
        // The callback names the network in use at once: not a change.
        synchronized(this) { current = manager.activeNetwork }
        runCatching {
            manager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = changed(network)

                    override fun onLost(network: Network) = changed(null)
                },
            )
        }.isSuccess
    }

    override fun generation(): Long {
        watching
        return generation.get()
    }

    @Synchronized
    private fun changed(network: Network?) {
        if (network == current) return
        current = network
        generation.incrementAndGet()
    }
}
