package app.hovanki.radar.host

import app.hovanki.radar.AirFrame
import app.hovanki.radar.AirHost
import app.hovanki.radar.Broadcast
import app.hovanki.radar.RadarCaps
import app.hovanki.radar.RadarChannel
import app.hovanki.radar.RadarRole
import app.hovanki.radar.RadarTrace
import app.hovanki.radar.RadioApi
import app.hovanki.radar.ScanInterest
import app.hovanki.radar.inWords
import app.hovanki.shared.protocol.Platform
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The host of a simulated [phone] in [air] (docs/adr/0017-radar-techniques-and-big-run.md, section 2.2): the bots'
 * Bluetooth. Behaves as the phone's own host would: one advertisement from the channels' parts, what the platform
 * can't send dropped and traced, an iPhone's advertisement untouched in the background; the air does the rest.
 */
class JvmAirHost(private val air: SimulatedAir, val phone: SimPhone) : AirHost {
    override val caps: StateFlow<RadarCaps> get() = phone.caps

    /** The token this phone advertises right now (the last radio started); null: none. */
    val advertisedToken: String? get() = air.radiosOf(phone).firstNotNullOfOrNull { it.advertised }

    /** The role of the radio running on this phone; null: none runs. */
    val role: RadarRole? get() = air.radiosOf(phone).firstOrNull()?.role

    /** What this phone has on the air right now, as the OS sends it; null: nothing. */
    fun onAir(): Broadcast? = air.radiosOf(phone).firstNotNullOfOrNull { it.broadcast() }

    override fun run(
        channels: List<RadarChannel>,
        token: StateFlow<String?>,
        role: RadarRole,
        trace: RadarTrace,
    ): Flow<AirFrame> = callbackFlow {
        val radio = SimRadio(phone, channels, role, trace) { trySend(it) }
        val apis = apis(radio.interests)
        apis.forEach { trace.scan("start", it, radio.interests.inWords()) }
        air.open(radio)
        val job = token.onEach(radio::want).launchIn(this)
        awaitClose {
            job.cancel()
            air.close(radio)
            radio.stop()
            apis.forEach { trace.scan("stop", it) }
        }
    }

    private fun apis(interests: List<ScanInterest>): List<RadioApi> = when (phone.platform) {
        Platform.ANDROID -> listOf(RadioApi.ANDROID_LE)

        Platform.OTHER -> listOf(RadioApi.MAC_COREBLUETOOTH)

        Platform.IOS -> buildList {
            add(RadioApi.COREBLUETOOTH)
            if (interests.any { it is ScanInterest.BeaconRanging }) add(RadioApi.CORELOCATION_RANGING)
            if (interests.any { it is ScanInterest.BeaconRegion }) add(RadioApi.CORELOCATION_REGION)
        }
    }
}
