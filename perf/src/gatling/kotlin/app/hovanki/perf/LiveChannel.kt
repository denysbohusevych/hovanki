package app.hovanki.perf

import app.hovanki.shared.protocol.ServerFeature

/**
 * The live channel for a socket run (`-Pperf.transport=socket`, docs/adr/0015-websockets.md): `LIVE_SOCKET` is a server
 * feature, off by default as on production. The run turns it on through the lab's admin before its players come, and
 * off again after them if it was off.
 */
class LiveChannel private constructor(private val admin: LabAdmin, private val turnedOn: Boolean) {
    fun close() {
        if (turnedOn) admin.setFeature(ServerFeature.LIVE_SOCKET, false, "perf: the socket run is over")
    }

    companion object {
        /**
         * Turns the live channel on for a run with sockets ([needed]: by default a socket run); null for a polling one,
         * which leaves the server as it is.
         */
        fun openFor(needed: Boolean = LabConfig.transport == Transport.SOCKET, admin: () -> LabAdmin): LiveChannel? {
            if (!needed) return null
            val lab = admin()
            val wasOn = lab.setFeature(ServerFeature.LIVE_SOCKET, true, "perf: a socket run")
            println("The live channel (LIVE_SOCKET) is on${if (wasOn) "" else " for this run"}")
            return LiveChannel(lab, turnedOn = !wasOn)
        }
    }
}
