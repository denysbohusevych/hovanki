package app.hovanki.radar.channel.ibeacon

import app.hovanki.radar.AdPart
import app.hovanki.radar.AirHex
import app.hovanki.radar.AirPlatform
import app.hovanki.radar.AirRole
import app.hovanki.radar.BleUuid
import app.hovanki.radar.ChannelReading
import app.hovanki.radar.ChannelUse
import app.hovanki.radar.GameAir
import app.hovanki.radar.HeardFrame
import app.hovanki.radar.IBeaconAd
import app.hovanki.radar.RadarChannel
import app.hovanki.radar.ScanInterest
import app.hovanki.radar.SightingVia
import app.hovanki.shared.rules.AppleData
import app.hovanki.shared.rules.RadarToken

/**
 * A seeker's iBeacon (`ble.ibeacon.ranging`, docs/adr/0017-radar-techniques-and-big-run.md, section 2.3): the game's
 * UUID with the token as major and minor. An iPhone hears it through CoreLocation's ranging, which keeps going from a
 * pocket while the round's location updates keep the app alive («Пульс»); Android reads Apple's manufacturer data.
 * Android and iPhones on the screen advertise it; macOS can't, and iOS doesn't send it from the background.
 */
class IBeaconChannel : RadarChannel {
    override val id: String = TECH
    override val use: ChannelUse = ChannelUse.GAME

    override fun adPart(token: String, role: AirRole, platform: AirPlatform): AdPart? {
        if (role != AirRole.SEEKER || platform == AirPlatform.MAC || !RadarToken.isWellFormed(token)) return null
        val (major, minor) = RadarToken.toMajorMinor(token)
        return AdPart(id, iBeacon = IBeaconAd(GameAir.SERVICE_UUID, major, minor))
    }

    override fun interests(platform: AirPlatform): List<ScanInterest> = when (platform) {
        AirPlatform.IOS -> listOf(ScanInterest.IBeaconRanging(GameAir.SERVICE_UUID))
        else -> listOf(ScanInterest.Manufacturer(GameAir.APPLE_COMPANY_ID, BEACON_PREFIX))
    }

    override fun decode(frame: HeardFrame): ChannelReading? {
        val beacon = frame.iBeacon?.let { Triple(BleUuid.canonical(it.uuid), it.major, it.minor) }
            ?: frame.manufacturerData[GameAir.APPLE_COMPANY_ID]
                ?.let { AppleData.iBeacon(AirHex.bytes(it)) }
                ?.let { Triple(BleUuid.canonical(it.uuidHex), it.major, it.minor) }
            ?: return null
        if (beacon.first != GameAir.SERVICE_UUID) return null
        return ChannelReading(
            id,
            listOf(RadarToken.fromMajorMinor(beacon.second, beacon.third)),
            SightingVia.IBEACON,
            use,
        )
    }

    companion object {
        const val TECH = "ble.ibeacon.ranging"

        /** Type 2, length 21, the game's UUID: what a scan filter for the game's iBeacons asks of Apple's data. */
        val BEACON_PREFIX: String = "0215" + BleUuid.canonical(GameAir.SERVICE_UUID).replace("-", "").lowercase()
    }
}

/**
 * Entering and leaving the region of the seekers' iBeacons (`ble.ibeacon.region`, ADR 0017 §2.3): iOS wakes the app
 * for it even in the background. Only the journal hears it (the host writes `region`); the question is how late the
 * entry comes when the phone is locked, and whether it opens a window for ranging.
 */
class IBeaconRegionChannel : RadarChannel {
    override val id: String = TECH
    override val use: ChannelUse = ChannelUse.SHADOW

    override fun interests(platform: AirPlatform): List<ScanInterest> =
        if (platform == AirPlatform.IOS) listOf(ScanInterest.IBeaconRegion(GameAir.SERVICE_UUID)) else emptyList()

    companion object {
        const val TECH = "ble.ibeacon.region"
    }
}
