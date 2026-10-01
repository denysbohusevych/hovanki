package app.hovanki.radar

import app.hovanki.shared.rules.AppleData
import app.hovanki.shared.rules.OverflowArea

/**
 * What one phone puts on the air at a moment, after its OS's rules ([AirRules.broadcast]): the advertising packet and
 * the scan response as the air carries them. Apple's frames (an iBeacon, the overflow area) are manufacturer data of
 * company 0x004C, as on the air.
 */
data class Broadcast(val main: AdData, val scanResponse: AdData = AdData())

/**
 * The OS's rules of the simulated air (docs/adr/0017-radar-techniques-and-big-run.md, section 2.2), for the e2e bots
 * and the radar's tests: what goes out of a phone, and what a phone's APIs hand to the app of what comes in. What the
 * real phones do is the field test's question; these are the rules we know or assume:
 *
 * - Android sends a packet of at most 31 bytes and its scan response: more, and `startAdvertising` refuses it, so
 *   nothing goes out. Its scan is active (it gets the scan response), passes what its filters let through and shows
 *   everything, Apple's manufacturer data included.
 * - An iPhone on the screen sends its name and service UUIDs, or an iBeacon. In the background (in a pocket) it sends
 *   neither the name nor the iBeacon: its service UUIDs become bits of the overflow area (the table's UUIDs their own
 *   bits, the game's service bit 117; others are Apple's secret and left out), and nothing if none is known.
 * - An iPhone hears through CoreBluetooth only frames that list a service it scans for, or, on the screen only, whose
 *   overflow bits stand for a UUID it scans for (and then it lists those UUIDs); it never sees iBeacon frames or
 *   Apple's raw data. CoreLocation ranges the iBeacons of the UUIDs it asked for, on the screen and in the pocket
 *   alike (the round's location updates keep the app alive; whether iOS 26 keeps ranging locked is H1 of the lab).
 * - A Mac sends like an iPhone on the screen and hears everything, Apple's raw data included.
 *
 * Not modelled: the foreground's own overflow area (UUIDs beyond the 28 bytes), iOS coalescing a backgrounded scan's
 * duplicates, the scan response missing on a passive scan.
 */
object AirRules {
    /** What [advert] puts on the air from a [platform] phone, [onScreen] or not; null: nothing. */
    fun broadcast(advert: Advert, platform: AirPlatform, onScreen: Boolean): Broadcast? = when (platform) {
        AirPlatform.ANDROID -> {
            val fits = AdBudget.android(advert.main).fits && AdBudget.android(advert.scanResponse).fits
            if (!fits || advert.isEmpty) null else Broadcast(advert.main, advert.scanResponse)
        }

        AirPlatform.MAC -> appleOnScreen(advert)

        AirPlatform.IOS -> if (onScreen) appleOnScreen(advert) else iosInTheBackground(advert)
    }

    /**
     * What a [platform] phone ([onScreen] or not) scanning for [interests] gets of [broadcast], heard at [rssi] dBm at
     * [atMillis] of its clock from the sender known to its OS as [peer]: a frame per API that hands it over.
     */
    fun hear(
        broadcast: Broadcast,
        platform: AirPlatform,
        onScreen: Boolean,
        interests: List<ScanInterest>,
        rssi: Int,
        atMillis: Long,
        peer: String,
    ): List<HeardFrame> {
        val all = broadcast.main + broadcast.scanResponse
        return when (platform) {
            AirPlatform.ANDROID -> if (interests.any { androidPasses(it, all) }) {
                listOf(frameOf(all, rssi, atMillis, RadioApi.ANDROID_LE, peer))
            } else {
                emptyList()
            }

            AirPlatform.MAC -> listOf(frameOf(all, rssi, atMillis, RadioApi.MAC_COREBLUETOOTH, peer))

            AirPlatform.IOS -> listOfNotNull(
                coreBluetooth(all, onScreen, interests, rssi, atMillis, peer),
                coreLocation(all, interests, rssi, atMillis),
            )
        }
    }

    private fun appleOnScreen(advert: Advert): Broadcast? {
        advert.iBeacon?.let { beacon ->
            return Broadcast(AdData(manufacturerData = mapOf(GameAir.APPLE_COMPANY_ID to beacon.frameHex())))
        }
        val main = AdData(serviceUuids = advert.main.serviceUuids, localName = advert.main.localName)
        return if (main.isEmpty) null else Broadcast(main)
    }

    private fun iosInTheBackground(advert: Advert): Broadcast? {
        // The advertisement a locked iPhone keeps: the one it had on the screen, or the one it put on as it resigned.
        val uuids = (advert.main.serviceUuids + advert.backgroundUuids).distinct()
        val bits = uuids.mapNotNull(::bitOf).toSet()
        if (bits.isEmpty()) return null
        val mask = OverflowArea.maskOf(bits)
        val hex = AirHex.of(byteArrayOf(AppleData.OVERFLOW.toByte()) + mask)
        return Broadcast(AdData(manufacturerData = mapOf(GameAir.APPLE_COMPANY_ID to hex)))
    }

    /** The overflow bit iOS gives [uuid]: the table's, the game's service's (measured); null: unknown. */
    private fun bitOf(uuid: String): Int? {
        val canonical = BleUuid.canonical(uuid)
        if (canonical == GameAir.SERVICE_UUID) return OverflowArea.OUR_SERVICE_BIT
        return OverflowArea.bitOf(canonical)
    }

    private fun androidPasses(interest: ScanInterest, data: AdData): Boolean = when (interest) {
        is ScanInterest.ServiceUuid -> data.serviceUuids.any {
            BleUuid.canonical(it) == BleUuid.canonical(interest.uuid)
        }

        is ScanInterest.ServiceData -> data.serviceData.keys.any {
            BleUuid.canonical(it) ==
                BleUuid.canonical(interest.uuid)
        }

        is ScanInterest.Manufacturer ->
            data.manufacturerData[interest.companyId]?.lowercase()?.startsWith(interest.prefixHex.lowercase()) == true

        else -> false
    }

    private fun coreBluetooth(
        data: AdData,
        onScreen: Boolean,
        interests: List<ScanInterest>,
        rssi: Int,
        atMillis: Long,
        peer: String,
    ): HeardFrame? {
        val apple = data.manufacturerData[GameAir.APPLE_COMPANY_ID]?.let(AirHex::bytes)
        // CoreBluetooth never shows an iBeacon frame to an app.
        if (apple != null && AppleData.iBeacon(apple) != null) return null
        val scanned = interests.filterIsInstance<ScanInterest.ServiceUuid>().map { BleUuid.canonical(it.uuid) }
        val overflowScanned = interests.filterIsInstance<ScanInterest.OverflowUuids>().flatMap { it.uuids }
        val listed = data.serviceUuids.map(BleUuid::canonical).filter { it in scanned }
        val maskBits = apple?.let(AppleData::overflowMask)?.let(OverflowArea::bitsOf).orEmpty()
        val overflow = if (onScreen && maskBits.isNotEmpty()) {
            (scanned + overflowScanned).map(BleUuid::canonical).distinct().filter { bitOf(it) in maskBits }
        } else {
            emptyList()
        }
        if (listed.isEmpty() && overflow.isEmpty()) return null
        return HeardFrame(
            rssi = rssi,
            atMillis = atMillis,
            api = RadioApi.COREBLUETOOTH,
            peer = peer,
            localName = data.localName,
            serviceUuids = data.serviceUuids,
            overflowUuids = overflow,
            serviceData = data.serviceData,
            manufacturerData = data.manufacturerData - GameAir.APPLE_COMPANY_ID,
        )
    }

    private fun coreLocation(data: AdData, interests: List<ScanInterest>, rssi: Int, atMillis: Long): HeardFrame? {
        val ranged = interests.filterIsInstance<ScanInterest.IBeaconRanging>().map { BleUuid.canonical(it.uuid) }
        if (ranged.isEmpty()) return null
        val beacon = data.manufacturerData[GameAir.APPLE_COMPANY_ID]?.let { AppleData.iBeacon(AirHex.bytes(it)) }
            ?: return null
        val uuid = BleUuid.canonical(beacon.uuidHex)
        if (uuid !in ranged) return null
        return HeardFrame(
            rssi,
            atMillis,
            RadioApi.CORELOCATION_RANGING,
            iBeacon = HeardIBeacon(uuid, beacon.major, beacon.minor),
        )
    }

    private fun frameOf(data: AdData, rssi: Int, atMillis: Long, api: RadioApi, peer: String) = HeardFrame(
        rssi = rssi,
        atMillis = atMillis,
        api = api,
        peer = peer,
        localName = data.localName,
        serviceUuids = data.serviceUuids,
        serviceData = data.serviceData,
        manufacturerData = data.manufacturerData,
        connectable = false,
    )
}
