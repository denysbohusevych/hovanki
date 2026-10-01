package app.hovanki.radar.channel.ibeacon

import app.hovanki.radar.AdPart
import app.hovanki.radar.AirFrame
import app.hovanki.radar.Availability
import app.hovanki.radar.BleUuid
import app.hovanki.radar.Decoded
import app.hovanki.radar.IBeaconBytes
import app.hovanki.radar.RadarCaps
import app.hovanki.radar.RadarChannel
import app.hovanki.radar.RadarRole
import app.hovanki.radar.RadarService
import app.hovanki.radar.ScanInterest
import app.hovanki.radar.SightingVia
import app.hovanki.radar.TechniqueStatus
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.rules.AppleData
import app.hovanki.shared.rules.IBeaconFrame
import app.hovanki.shared.rules.RadarToken

/**
 * `ble.ibeacon` (docs/adr/0017-radar-techniques-and-big-run.md, section 2.3, `ble.ibeacon.ranging` there): a seeker
 * advertises an iBeacon frame of the game's UUID with the token as major and minor; an iPhone hears it through
 * CoreLocation ranging, also from a pocket while the app lives («Пульс»), Android and a Mac raw in Apple's
 * manufacturer data. An iPhone sends it on the screen only (where a seeker is anyway).
 */
data object IBeaconChannel : RadarChannel {
    override val id: String = "ble.ibeacon"
    override val status: TechniqueStatus = TechniqueStatus.GAME

    override fun available(caps: RadarCaps): Availability = caps.noBluetoothLe ?: Availability.Available

    override fun advertise(token: String, role: RadarRole): List<AdPart> {
        if (role != RadarRole.SEEKER || !RadarToken.isWellFormed(token)) return emptyList()
        val (major, minor) = RadarToken.toMajorMinor(token)
        return listOf(AdPart.IBeacon(RadarService.UUID, major, minor, RadarService.MEASURED_POWER))
    }

    override fun interests(): List<ScanInterest> = listOf(
        ScanInterest.Manufacturer(RadarService.APPLE_COMPANY_ID, IBeaconBytes.prefix(RadarService.UUID)),
        ScanInterest.BeaconRanging(RadarService.UUID),
    )

    override fun decode(frame: AirFrame): List<Decoded> {
        val beacon = frame.iBeacon
            ?: frame.manufacturerData[RadarService.APPLE_COMPANY_ID]?.let(AppleData::iBeacon)
            ?: return emptyList()
        if (!beacon.isGames()) return emptyList()
        return listOf(Decoded(RadarToken.fromMajorMinor(beacon.major, beacon.minor), SightingVia.IBEACON))
    }
}

/**
 * `ble.ibeacon.region` (docs/adr/0017-radar-techniques-and-big-run.md, section 2.3): iOS region monitoring of the
 * seekers' iBeacon. Entering the region wakes the app even in the background; the host traces the enter and the exit
 * (`region_enter`, `region_exit`). Carries no token: [decode] reads nothing. In the game since ADR 0012 (the iOS radio
 * always monitored the game's region, so a hider's locked iPhone wakes when a seeker comes near), and in the lab so a
 * step can switch it off and see what changes in the log.
 */
data object IBeaconRegionChannel : RadarChannel {
    override val id: String = "ble.ibeacon.region"
    override val status: TechniqueStatus = TechniqueStatus.GAME

    override fun available(caps: RadarCaps): Availability = caps.noBluetoothLe
        ?: if (caps.platform == Platform.IOS && caps.canRangeBeacons) {
            Availability.Available
        } else {
            Availability.Unavailable("region monitoring is CoreLocation's")
        }

    override fun advertise(token: String, role: RadarRole): List<AdPart> = emptyList()

    override fun interests(): List<ScanInterest> = listOf(ScanInterest.BeaconRegion(RadarService.UUID))

    override fun decode(frame: AirFrame): List<Decoded> = emptyList()
}

private val GAME_UUID_HEX = BleUuid.hex(RadarService.UUID)

private fun IBeaconFrame.isGames(): Boolean = uuidHex.equals(GAME_UUID_HEX, ignoreCase = true)
