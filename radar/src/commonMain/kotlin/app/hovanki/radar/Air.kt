package app.hovanki.radar

/*
 * The radar's channels and hosts (docs/adr/0017-radar-techniques-and-big-run.md, section 2.2): the common ground of
 * every channel (`app.hovanki.radar.channel.<id>`) and every host (Android, iOS, the JVM's simulated air). A channel
 * says what it puts into the advertisement ([AdPart]), what a scan must let through for it ([ScanInterest]) and how a
 * frame becomes a token ([RadarChannel.decode]); a host owns the platform's advertiser and scanner, joins the parts
 * into one advertisement ([AdJoin]) and hands every frame it hears to the channels ([AirDecoder]). Pure Kotlin: the
 * codecs are tested on the JVM.
 */

/** Which kind of phone advertises or listens: the OS's rules differ (ADR 0017 §2.2). */
enum class AirPlatform {
    ANDROID,
    IOS,

    /** A MacBook (`e2e/mac-beacon`): advertises like an iPhone on the screen, hears everything. */
    MAC,
}

/** What the phone is in the round: a hider advertises its token for the seekers, a seeker an iBeacon. */
enum class AirRole { HIDER, SEEKER }

/** What a channel's reading does: [GAME] goes to the game; [SHADOW] only into the journal (ADR 0018, «в тени»). */
enum class ChannelUse { GAME, SHADOW }

/** The game's own constants on the air. */
object GameAir {
    /** The game's 128-bit UUID: the service of a hider's frame and the proximity UUID of a seeker's iBeacon. */
    const val SERVICE_UUID = "7A0B8D2E-4C1F-4E6A-9B3D-2F5E8C1A7D10"

    /** Apple's company id in manufacturer data: iBeacon frames and the overflow area. */
    const val APPLE_COMPANY_ID = 0x004C

    /**
     * The company id of the `.mfr` layout: 0xFFFF is the Bluetooth SIG's «not assigned, for tests», which is what a
     * layout on trial is; the game's UUID right after it tells our frames from anybody else's.
     */
    const val TEST_COMPANY_ID = 0xFFFF

    /** The signal at one metre an iBeacon says: -59 dBm, a typical phone. */
    const val MEASURED_POWER = -59

    /** An iPhone hider's name was this prefix and the token in the first apps; still read. */
    const val NAME_PREFIX = "hv"
}

/**
 * One advertisement's data, the way the platform's API takes it (Android's `AdvertiseData`) and a scan shows it.
 * UUIDs are canonical ([BleUuid.canonical]); data are lower-case hex.
 */
data class AdData(
    val serviceUuids: List<String> = emptyList(),
    val serviceData: Map<String, String> = emptyMap(),
    val manufacturerData: Map<Int, String> = emptyMap(),
    val localName: String? = null,
    val includeTxPower: Boolean = false,
) {
    val isEmpty: Boolean
        get() = serviceUuids.isEmpty() && serviceData.isEmpty() && manufacturerData.isEmpty() && localName == null &&
            !includeTxPower

    /** Both together; [other]'s name and UUIDs after these. Clashes are the joiner's to find ([AdJoin]). */
    operator fun plus(other: AdData): AdData = AdData(
        serviceUuids = (serviceUuids + other.serviceUuids).distinct(),
        serviceData = serviceData + other.serviceData,
        manufacturerData = manufacturerData + other.manufacturerData,
        localName = localName ?: other.localName,
        includeTxPower = includeTxPower || other.includeTxPower,
    )
}

/** A seeker's iBeacon: the game's UUID, the token as major and minor. */
data class IBeaconAd(
    val uuid: String,
    val major: Int,
    val minor: Int,
    val measuredPower: Int = GameAir.MEASURED_POWER,
) {
    /** The frame in Apple's manufacturer data after the company id: type 2, length 21, UUID, major, minor, power. */
    fun frameHex(): String = "0215" + AirHex.of(BleUuid.bytes(uuid)) + AirHex.u16(major) + AirHex.u16(minor) +
        AirHex.of(byteArrayOf(measuredPower.toByte()))
}

/**
 * What one channel puts into the advertisement for a token: [main] (the advertising packet), [scanResponse] (the
 * answer to an active scan), an [iBeacon] (on iOS a whole advertisement of its own, on Android Apple's manufacturer
 * data) and, on iOS, [backgroundUuids]: the service UUIDs of the advertisement a locked iPhone keeps on the air, which
 * iOS turns into bits of the overflow area (docs/adr/0016-iphone-overflow-radar.md). [layout]: which of its channel's
 * layouts this is (`scan_response`, `bare`, `mfr`), for the journal.
 */
data class AdPart(
    val tech: String,
    val main: AdData = AdData(),
    val scanResponse: AdData = AdData(),
    val iBeacon: IBeaconAd? = null,
    val backgroundUuids: List<String> = emptyList(),
    val layout: String? = null,
)

/** What a scan must let through for a channel; each host turns these into its platform's filters. */
sealed interface ScanInterest {
    /** A frame listing this service UUID (Android `setServiceUuid`, iOS `scanForPeripheralsWithServices`). */
    data class ServiceUuid(val uuid: String) : ScanInterest

    /** A frame with service data of this UUID (Android `setServiceData`; iOS can't filter by it). */
    data class ServiceData(val uuid: String) : ScanInterest

    /** Manufacturer data of [companyId] starting with [prefixHex] (Android `setManufacturerData`). */
    data class Manufacturer(val companyId: Int, val prefixHex: String) : ScanInterest

    /** iOS on the screen: these UUIDs, read off other iPhones' overflow areas (only when asked for by name). */
    data class OverflowUuids(val uuids: List<String>) : ScanInterest

    /** iOS: CoreLocation ranges the iBeacons of [uuid] (CoreBluetooth never shows iBeacon frames to an app). */
    data class IBeaconRanging(val uuid: String) : ScanInterest

    /** iOS: CoreLocation watches the region of [uuid]'s iBeacons: entering and leaving it, even in the background. */
    data class IBeaconRegion(val uuid: String) : ScanInterest
}

/**
 * A frame as a listener heard it: whatever its platform shows of an advertisement. Android and the simulated air
 * give everything, Apple's manufacturer data included; iOS's CoreBluetooth hides iBeacon frames and the overflow
 * area's raw bytes, and lists instead the [overflowUuids] it was asked to scan for; CoreLocation's ranging gives
 * only the [iBeacon]. [rawHex]: the record's bytes where the platform has them (Android).
 */
data class HeardFrame(
    val rssi: Int,
    val atMillis: Long,
    val api: RadioApi,
    val peer: String? = null,
    val localName: String? = null,
    val serviceUuids: List<String> = emptyList(),
    val overflowUuids: List<String> = emptyList(),
    val serviceData: Map<String, String> = emptyMap(),
    val manufacturerData: Map<Int, String> = emptyMap(),
    val iBeacon: HeardIBeacon? = null,
    val txPower: Int? = null,
    val connectable: Boolean? = null,
    val rawHex: String? = null,
)

/** An iBeacon as CoreLocation ranges it. */
data class HeardIBeacon(val uuid: String, val major: Int, val minor: Int)

/** What a channel read in a frame: [tokens] (an overflow mask may give 2 or 4 when damaged), how and what for. */
data class ChannelReading(val tech: String, val tokens: List<String>, val via: SightingVia, val use: ChannelUse)

/**
 * One way to carry a token on the air (docs/adr/0017-radar-techniques-and-big-run.md, section 2.2): its own package
 * `app.hovanki.radar.channel.<id>`, which imports no other channel; only [RadarCatalog] knows them all.
 */
interface RadarChannel {
    /** The technique's id (ADR 0017 §2.3): `ble.name`, `ble.service_data.bare`, `ble.overflow`… */
    val id: String

    val use: ChannelUse

    /** What this phone puts on the air for [token] as [role] on [platform]; null: nothing. */
    fun adPart(token: String, role: AirRole, platform: AirPlatform): AdPart? = null

    /** What a listener on [platform] must scan for to hear this channel. */
    fun interests(platform: AirPlatform): List<ScanInterest> = emptyList()

    /** The token in [frame]; null: not this channel's frame. */
    fun decode(frame: HeardFrame): ChannelReading? = null
}

/** Bluetooth UUIDs as text: canonical upper case with dashes; the 16- and 32-bit short forms on the base UUID. */
object BleUuid {
    private const val BASE_TAIL = "-0000-1000-8000-00805F9B34FB"

    /** Upper case with dashes; a short form (`FEAA`, `0000FEAA`) becomes the full UUID on Bluetooth's base. */
    fun canonical(uuid: String): String {
        val upper = uuid.trim().uppercase()
        return when (upper.length) {
            4 -> "0000$upper$BASE_TAIL"

            8 -> "$upper$BASE_TAIL"

            32 -> listOf(upper.take(8), upper.substring(8, 12), upper.substring(12, 16), upper.substring(16, 20))
                .joinToString("-") + "-" + upper.substring(20)

            else -> upper
        }
    }

    /** How many bytes the UUID takes on the air: 2 or 4 on the base UUID, else 16 (Android's `BluetoothUuid`). */
    fun sizeOnAir(uuid: String): Int {
        val full = canonical(uuid)
        if (!full.endsWith(BASE_TAIL)) return 16
        return if (full.startsWith("0000")) 2 else 4
    }

    /** The 16 bytes, most significant first (as in an iBeacon frame). */
    fun bytes(uuid: String): ByteArray = AirHex.bytes(canonical(uuid).replace("-", ""))
}

/** Lower-case hex, both ways. */
internal object AirHex {
    fun of(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** [hex] as bytes; an odd or broken string gives what can be read of it. */
    fun bytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
        hex.substring(2 * i, 2 * i + 2).toIntOrNull(16)?.toByte() ?: 0
    }

    /** Two bytes, most significant first. */
    fun u16(value: Int): String = (value and 0xffff).toString(16).padStart(4, '0')

    /**
     * A scan record's fields (the packet and the scan response, as Android's `ScanRecord.getBytes` gives them) without
     * the zeros its buffer is padded with: up to the first field of length 0, as `ScanRecord.parseFromBytes` reads it.
     * Not by cutting the trailing zeros, which would cut a token or a measured power that ends in `00` too.
     */
    fun recordFields(record: ByteArray): ByteArray {
        var end = 0
        while (end < record.size) {
            val length = record[end].toInt() and 0xff
            if (length == 0) break
            end += 1 + length
        }
        return record.copyOf(minOf(end, record.size))
    }
}
