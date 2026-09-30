package app.hovanki.radar.lab

import app.hovanki.radar.RadioApi
import app.hovanki.radar.SightingVia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/*
 * The radio lab's own radio (docs/radio-lab.md §5), besides the game's. Implemented in this module's androidMain
 * (`AndroidLabAir`) and iosMain (`IosLabAir`), the no-op one below everywhere else. Debug builds only use it: the
 * lab's screen exists only there. Flows are collected on the main thread and may emit from it only.
 */

/**
 * The lab's own radio beside the game's (docs/radio-lab.md §5): «listen to everything» and the overflow probe. The
 * game's [app.hovanki.radar.ProximityRadio] stays as it is; the lab's «as a hider / as a seeker» goes through it.
 */
interface LabAir {
    val canListen: Boolean

    /**
     * While collected: Android and the Mac hear every Apple frame (the overflow mask `0x01`, iBeacon `0x02 0x15`); an
     * iPhone scans for the table's 128 UUIDs and the game's service and reads the overflow bits CoreBluetooth lists.
     */
    fun listen(): Flow<AirFrame> = emptyFlow()

    val canProbe: Boolean

    /**
     * While collected: advertises the game's service and the table's UUIDs for [bits] (iOS). A change of [bits] in the
     * background is skipped, as the game's advertiser does (iOS can't start an advertisement there), and applied when
     * the app is active again.
     */
    fun probe(bits: StateFlow<Set<Int>>): Flow<ProbeEvent> = emptyFlow()
}

sealed interface AirFrame {
    val rssi: Int
    val peer: String?
    val atMillis: Long
    val api: RadioApi

    /** An overflow mask: its [bits] (Young's order), and [hex], the raw 16 bytes, where the platform has them. */
    data class Mask(
        val bits: Set<Int>,
        val hex: String?,
        override val rssi: Int,
        override val peer: String?,
        override val atMillis: Long,
        override val api: RadioApi,
    ) : AirFrame

    /** A token read another way: an iBeacon frame of the game, a hider's name or service data. */
    data class Token(
        val token: String,
        val via: SightingVia,
        override val rssi: Int,
        override val peer: String?,
        override val atMillis: Long,
        override val api: RadioApi,
    ) : AirFrame
}

/** [action]: `start`, `stop`, `failed` ([error]), `skipped_background`. */
data class ProbeEvent(val action: String, val error: String? = null)

class NoopLabAir : LabAir {
    override val canListen: Boolean = false
    override val canProbe: Boolean = false
}
