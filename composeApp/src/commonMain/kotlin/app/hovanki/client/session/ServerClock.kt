@file:OptIn(ExperimentalTime::class)

package app.hovanki.client.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Server time as seen from this device. All protocol timestamps are server time, and phone clocks are often
 * off by seconds or minutes: countdowns, the zone and especially the hider's TOTP catch code must use [now],
 * otherwise a wrong phone clock would produce codes the server rejects.
 *
 * The offset is taken from [app.hovanki.shared.protocol.GameSnapshot.serverTimeMillis] of every snapshot. It ignores
 * the network latency (a few hundred ms), which is far below what countdowns and 30 s code periods care about.
 */
class ServerClock(private val deviceTimeMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private val offsetMillis = MutableStateFlow(0L)

    fun onServerTime(serverTimeMillis: Long) {
        offsetMillis.value = serverTimeMillis - deviceTimeMillis()
    }

    fun now(): Long = toServerTime(deviceTimeMillis())

    /** Converts a device-clock timestamp (e.g. of a GPS fix) to server time. */
    fun toServerTime(deviceMillis: Long): Long = deviceMillis + offsetMillis.value
}
