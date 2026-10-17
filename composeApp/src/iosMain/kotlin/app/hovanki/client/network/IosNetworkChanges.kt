@file:OptIn(ExperimentalForeignApi::class)

package app.hovanki.client.network

import app.hovanki.client.network.live.NetworkChanges
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.nw_interface_get_index
import platform.Network.nw_interface_get_type
import platform.Network.nw_path_enumerate_interfaces
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.darwin.dispatch_queue_create
import kotlin.concurrent.Volatile

/**
 * The phone's default network by `nw_path_monitor` (docs/live-v2/client.md §6, §7): the generation grows when the
 * path changes its status or its interfaces (Wi-Fi ↔ mobile, the network gone and back). The live channel's UDP socket
 * is a BSD one, which doesn't follow the network by itself (Apple's TN3151): on a new generation it takes a new one.
 * Watched from the first question on, for as long as the app lives.
 */
class IosNetworkChanges : NetworkChanges {
    @Volatile
    private var generation = 0L

    /** The last path's status and interfaces; touched on the monitor's queue only. */
    private var path: String? = null

    private val monitor by lazy {
        nw_path_monitor_create().also { monitor ->
            nw_path_monitor_set_queue(monitor, dispatch_queue_create("app.hovanki.network", null))
            nw_path_monitor_set_update_handler(monitor) { path ->
                val interfaces = StringBuilder()
                nw_path_enumerate_interfaces(path) { item ->
                    interfaces.append(nw_interface_get_type(item)).append(':').append(nw_interface_get_index(item))
                        .append(' ')
                    true
                }
                changed("${nw_path_get_status(path)} $interfaces")
            }
            nw_path_monitor_start(monitor)
        }
    }

    override fun generation(): Long {
        monitor
        return generation
    }

    /** The first path is the one in use at once: not a change. One writer, the monitor's serial queue. */
    private fun changed(next: String) {
        val last = path
        path = next
        if (last != null && last != next) generation += 1
    }
}
