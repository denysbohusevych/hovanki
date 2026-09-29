package app.hovanki.client.tracking

import app.hovanki.shared.protocol.Carry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Where the phone is by its sensors (docs/adr/0012-nearby-radar.md, «Карман»), while collected: in the pocket only
 * with the screen off (and, where the phone has them, the proximity sensor covered or the light gone), in the hand
 * with the screen on; unknown otherwise, for instance a phone lying on a table. Only the state leaves the phone
 * (`DeviceReport.carry`), never a sensor reading.
 */
interface CarryMonitor {
    fun carry(): Flow<Carry>
}

class NoopCarryMonitor : CarryMonitor {
    override fun carry(): Flow<Carry> = emptyFlow()
}
