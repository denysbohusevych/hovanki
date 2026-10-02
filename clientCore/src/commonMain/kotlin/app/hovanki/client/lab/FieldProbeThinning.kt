package app.hovanki.client.lab

import app.hovanki.device.lab.LabBattery
import kotlin.math.max
import kotlin.math.min

/**
 * What the field log keeps of the phone's own sensors (docs/adr/0018-field-test-build.md §3.2): changes, and at most
 * about one event a minute of each kind, so a three-hour game costs a few hundred lines, not a million readings. Pure:
 * the time of every reading comes in (the monotonic clock). Not thread-safe; the main thread.
 */
class FieldProbeThinning {
    private var lastNear: Boolean? = null
    private var lastLuxAt: Long? = null
    private var lastLux: Double? = null
    private var lastBattery: LabBattery? = null
    private var lastBatteryAt: Long? = null
    private var lastThermal: String? = null
    private var lastCarry: String? = null
    private var lastActivity: String? = null

    /** The proximity sensor now: written when it first says anything and when near/far changes. */
    fun allowProximity(near: Boolean?): Boolean {
        if (near == null || near == lastNear) return false
        lastNear = near
        return true
    }

    /**
     * The light at [nowMillis]: written the first time, when it changed by a factor of [LUX_FACTOR] (or out of or into
     * the dark) since the last written one after [LUX_MIN_GAP_MILLIS], and in any case once in [MINUTE_MILLIS].
     */
    fun allowLight(lux: Double, nowMillis: Long): Boolean {
        val at = lastLuxAt
        val last = lastLux
        if (at != null && last != null) {
            val since = nowMillis - at
            val inOrder = since >= 0
            if (inOrder && since < LUX_MIN_GAP_MILLIS) return false
            val lo = min(lux, last)
            val hi = max(lux, last)
            val changed = if (lo < DARK_LUX) hi >= DARK_LUX else hi / lo >= LUX_FACTOR
            if (inOrder && !changed && since < MINUTE_MILLIS) return false
        }
        lastLuxAt = nowMillis
        lastLux = lux
        return true
    }

    /** The battery at [nowMillis]: written when the state or the power saver changes, and else once a minute. */
    fun allowBattery(battery: LabBattery, nowMillis: Long): Boolean {
        val last = lastBattery
        val at = lastBatteryAt
        val sameKind = last != null && last.state == battery.state && last.lowPower == battery.lowPower
        if (sameKind && at != null && nowMillis - at in 0 until MINUTE_MILLIS) return false
        lastBattery = battery
        lastBatteryAt = nowMillis
        return true
    }

    /** A thermal state: written when it changes. */
    fun allowThermal(state: String): Boolean {
        if (state == lastThermal) return false
        lastThermal = state
        return true
    }

    /** A carry state (`in_hand`…): written when it changes. */
    fun allowCarry(state: String): Boolean {
        if (state == lastCarry) return false
        lastCarry = state
        return true
    }

    /** An activity (`walking`…): written when it changes. */
    fun allowActivity(activity: String): Boolean {
        if (activity == lastActivity) return false
        lastActivity = activity
        return true
    }

    companion object {
        const val MINUTE_MILLIS = 60_000L
        const val LUX_MIN_GAP_MILLIS = 5_000L
        const val LUX_FACTOR = 2.0

        /** Below it the phone is in the dark (a pocket, a bag). */
        const val DARK_LUX = 1.0
    }
}
