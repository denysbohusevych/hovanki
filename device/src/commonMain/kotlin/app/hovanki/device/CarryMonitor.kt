package app.hovanki.device

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

/**
 * The iOS carry monitor's rule, pure ([IosCarryMonitor]): in the hand while the app is active and its screen lit;
 * while the field build's round turned the screen off by the proximity sensor ([proximityOn], ADR 0018 wave 4) and
 * the sensor is covered ([near]), the app stays active in the pocket: in the pocket when the accelerometer says the
 * phone is [carried], unknown otherwise; locked ([protectedDataAvailable] false) and carried: in the pocket.
 */
object IosCarryRules {
    fun state(
        active: Boolean,
        proximityOn: Boolean,
        near: Boolean,
        protectedDataAvailable: Boolean,
        carried: Boolean,
    ): Carry = when {
        active && proximityOn && near -> if (carried) Carry.IN_POCKET else Carry.UNKNOWN
        active -> Carry.IN_HAND
        !protectedDataAvailable && carried -> Carry.IN_POCKET
        else -> Carry.UNKNOWN
    }
}
