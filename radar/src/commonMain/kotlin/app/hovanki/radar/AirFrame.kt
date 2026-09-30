package app.hovanki.radar

import app.hovanki.shared.rules.IBeaconFrame

/**
 * One frame of the air as a host reads it (docs/adr/0017-radar-techniques-and-big-run.md, section 2.2): whatever
 * fields the platform gives; every channel picks its own ([RadarChannel.decode]). UUIDs are [BleUuid.normalize]d:
 * upper case, 128-bit with dashes, 16-bit as 4 hex digits. [atMillis] is the device's clock; [peer] the OS's id of
 * the sender (an address, a CoreBluetooth identifier), never sent anywhere: the lab hashes it.
 *
 * @property overflowUuids iOS: the UUIDs CoreBluetooth lists from another iPhone's overflow area
 * (`CBAdvertisementDataOverflowServiceUUIDsKey`), only those this phone scans for.
 * @property manufacturerData by company id; Apple's (`0x004C`) holds the overflow mask and iBeacon frames on Android
 * and a Mac; an iPhone never shows them to an app.
 * @property iBeacon a beacon CoreLocation ranged: nothing else is set then.
 * @property regionEvent iOS region monitoring: entered or left the game's beacon region; nothing else is set then.
 * @property raw the raw advertising record where the platform has it (Android: `ScanRecord.bytes`), for the log.
 */
data class AirFrame(
    val atMillis: Long,
    val rssi: Int,
    val api: RadioApi,
    val peer: String?,
    val name: String? = null,
    val serviceUuids: List<String> = emptyList(),
    val overflowUuids: List<String> = emptyList(),
    val serviceData: Map<String, ByteArray> = emptyMap(),
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    val iBeacon: IBeaconFrame? = null,
    val txPower: Int? = null,
    val connectable: Boolean? = null,
    val regionEvent: RegionEvent? = null,
    val raw: ByteArray? = null,
) {
    /** [raw] in lower-case hex, for the log's `frame` event; null where the platform gives no raw record (iOS). */
    fun hex(): String? = raw?.toHex()

    /** The service data of [uuid], whatever the case or the form the host wrote the key in. */
    fun serviceData(uuid: String): ByteArray? {
        val wanted = BleUuid.normalize(uuid)
        return serviceData.entries.firstOrNull { BleUuid.normalize(it.key) == wanted }?.value
    }

    /** Whether [uuid] is among [serviceUuids]. */
    fun lists(uuid: String): Boolean {
        val wanted = BleUuid.normalize(uuid)
        return serviceUuids.any { BleUuid.normalize(it) == wanted }
    }
}

/** iOS region monitoring (`didEnterRegion`, `didExitRegion`). */
enum class RegionEvent { ENTER, EXIT }

/**
 * What a channel puts into the advertisement ([RadarChannel.advertise]). The host assembles one advertisement from
 * every channel's parts ([AdPlan]) and drops what its platform can't send.
 */
sealed interface AdPart {
    /** A service UUID in the advertisement's list (iOS: in the overflow area when it doesn't fit or in the background). */
    data class ServiceUuid(val uuid: String) : AdPart

    /** Service data (Android only); [inScanResponse]: in the scan response rather than the advertisement. */
    class ServiceData(val uuid: String, val data: ByteArray, val inScanResponse: Boolean = false) : AdPart {
        override fun equals(other: Any?): Boolean = other is ServiceData && uuid == other.uuid &&
            data.contentEquals(other.data) && inScanResponse == other.inScanResponse

        override fun hashCode(): Int = (uuid.hashCode() * 31 + data.contentHashCode()) * 31 + inScanResponse.hashCode()

        override fun toString(): String = "ServiceData($uuid, ${data.toHex()}, inScanResponse=$inScanResponse)"
    }

    /** Manufacturer data (Android only): [data] after the company id. */
    class ManufacturerData(val companyId: Int, val data: ByteArray) : AdPart {
        override fun equals(other: Any?): Boolean =
            other is ManufacturerData && companyId == other.companyId && data.contentEquals(other.data)

        override fun hashCode(): Int = companyId * 31 + data.contentHashCode()

        override fun toString(): String = "ManufacturerData(0x${companyId.toString(16)}, ${data.toHex()})"
    }

    /** The local name (iOS; Android can't name one advertisement without renaming the phone: its host drops it). */
    data class LocalName(val name: String) : AdPart

    /**
     * An iBeacon frame: iOS `CLBeaconRegion.peripheralDataWithMeasuredPower` (it then advertises alone), Android
     * Apple's manufacturer data ([IBeaconBytes]).
     */
    data class IBeacon(val uuid: String, val major: Int, val minor: Int, val measuredPower: Int) : AdPart
}

/** What a channel wants heard ([RadarChannel.interests]); the host scans for the union of every channel's. */
sealed interface ScanInterest {
    /**
     * Frames that carry [uuid]: in the list of service UUIDs, or (Android and a Mac) as the key of service data. The
     * Android framework matches `ScanFilter.setServiceUuid` against the list only, so its host puts a second filter,
     * `setServiceData(uuid, empty)`, for the same interest. An iPhone matches the list (and, on screen, the overflow
     * area) only: a frame with the service data alone never passes its filter.
     */
    data class Service(val uuid: String) : ScanInterest

    /** Manufacturer data of [companyId] starting with [prefix] (Android and a Mac; an iPhone can't filter by it). */
    class Manufacturer(val companyId: Int, val prefix: ByteArray = ByteArray(0)) : ScanInterest {
        override fun equals(other: Any?): Boolean =
            other is Manufacturer && companyId == other.companyId && prefix.contentEquals(other.prefix)

        override fun hashCode(): Int = companyId * 31 + prefix.contentHashCode()

        override fun toString(): String = "Manufacturer(0x${companyId.toString(16)}, ${prefix.toHex()})"
    }

    /** iOS: CoreLocation ranging of the beacons with [uuid] (once a second, also from a pocket while the app lives). */
    data class BeaconRanging(val uuid: String) : ScanInterest

    /** iOS: region monitoring of the beacons with [uuid]: enter and exit ([RegionEvent]). */
    data class BeaconRegion(val uuid: String) : ScanInterest

    /** iOS: scan for these UUIDs (the overflow table's): CoreBluetooth lists those set in an overflow area. */
    data class OverflowUuids(val uuids: List<String>) : ScanInterest
}

/** Apple's iBeacon frame in manufacturer data, after the company id (Android advertises and reads it raw). */
object IBeaconBytes {
    /** Type 2, length 21 (0x15), the UUID, major, minor, the measured power. */
    const val LENGTH = 23

    fun of(beacon: AdPart.IBeacon): ByteArray = byteArrayOf(0x02, 0x15) + BleUuid.bytes(beacon.uuid) +
        byteArrayOf(
            (beacon.major shr 8).toByte(),
            beacon.major.toByte(),
            (beacon.minor shr 8).toByte(),
            beacon.minor.toByte(),
            beacon.measuredPower.toByte(),
        )

    /** The prefix of a beacon of [uuid]: what a scan filter by manufacturer data looks for. */
    fun prefix(uuid: String): ByteArray = byteArrayOf(0x02, 0x15) + BleUuid.bytes(uuid)
}

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** [this] in words for the log's `scan` event: `service 7A0B8D2E-…, mfr 0x4c 0215…, ranging 7A0B8D2E-…`. */
fun List<ScanInterest>.inWords(): String = joinToString(", ") {
    when (it) {
        is ScanInterest.Service -> "service ${it.uuid}"
        is ScanInterest.Manufacturer -> "mfr 0x${it.companyId.toString(16)} ${it.prefix.toHex()}".trimEnd()
        is ScanInterest.BeaconRanging -> "ranging ${it.uuid}"
        is ScanInterest.BeaconRegion -> "region ${it.uuid}"
        is ScanInterest.OverflowUuids -> "overflow ${it.uuids.size} uuids"
    }
}
