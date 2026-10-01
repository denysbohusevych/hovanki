package app.hovanki.radar

/**
 * The bytes of an advertisement (docs/adr/0017-radar-techniques-and-big-run.md, section 2.2). [android] counts the way
 * `BluetoothLeAdvertiser.totalBytes` does before `startAdvertising`: 2 bytes per field (length and type) and the field's
 * data, 16-bit, 32-bit and 128-bit service UUIDs each in one field, 2 more for a company id, the flags (3) only for a
 * connectable advertisement. A legacy packet holds [LEGACY_BYTES]: more, and Android refuses it with
 * `ADVERTISE_FAILED_DATA_TOO_LARGE` (code 1) before anything goes on the air. [ios] counts the room iOS gives an app
 * in the foreground ([IOS_FOREGROUND_BYTES] for the name and the service UUIDs): what doesn't fit is not refused, the
 * UUIDs go into the overflow area.
 */
object AdBudget {
    const val LEGACY_BYTES = 31

    /** iOS in the foreground: the name and the service UUIDs share this much (`CBPeripheralManager.startAdvertising`). */
    const val IOS_FOREGROUND_BYTES = 28

    /** `AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE`. */
    const val ANDROID_TOO_LARGE = 1

    private const val FLAGS_BYTES = 3
    private const val FIELD_OVERHEAD = 2
    private const val COMPANY_ID_BYTES = 2

    /** One field of a packet: what it is and its bytes on the air, the 2 of length and type included. */
    data class Field(val name: String, val bytes: Int)

    /** A packet's fields against its [limit]. */
    data class Packet(val fields: List<Field>, val limit: Int = LEGACY_BYTES) {
        val bytes: Int get() = fields.sumOf { it.bytes }
        val fits: Boolean get() = bytes <= limit

        /** `uuid128 18 + svc_data 22 = 40/31`; `-` for an empty packet. */
        override fun toString(): String = if (fields.isEmpty()) {
            "-"
        } else {
            fields.joinToString(" + ") { "${it.name} ${it.bytes}" } + " = $bytes/$limit"
        }
    }

    /** The packet as Android counts it; [flags]: a connectable (and discoverable) advertisement carries them. */
    fun android(data: AdData, flags: Boolean = false): Packet = Packet(
        buildList {
            if (flags) add(Field("flags", FLAGS_BYTES))
            val bySize = data.serviceUuids.groupBy(BleUuid::sizeOnAir)
            for (size in listOf(2, 4, 16)) {
                val uuids = bySize[size] ?: continue
                add(Field("uuid${size * 8}", FIELD_OVERHEAD + uuids.size * size))
            }
            for ((uuid, hex) in data.serviceData) {
                add(Field("svc_data", FIELD_OVERHEAD + BleUuid.sizeOnAir(uuid) + hex.length / 2))
            }
            for ((_, hex) in data.manufacturerData) {
                add(Field("mfr", FIELD_OVERHEAD + COMPANY_ID_BYTES + hex.length / 2))
            }
            if (data.includeTxPower) add(Field("tx_power", FIELD_OVERHEAD + 1))
            data.localName?.let { add(Field("name", FIELD_OVERHEAD + it.encodeToByteArray().size)) }
        },
    )

    /** The room iOS gives the name and the service UUIDs in the foreground; nothing else can be advertised there. */
    fun ios(data: AdData): Packet = Packet(
        buildList {
            val bySize = data.serviceUuids.groupBy(BleUuid::sizeOnAir)
            for (size in listOf(2, 4, 16)) {
                val uuids = bySize[size] ?: continue
                add(Field("uuid${size * 8}", FIELD_OVERHEAD + uuids.size * size))
            }
            data.localName?.let { add(Field("name", FIELD_OVERHEAD + it.encodeToByteArray().size)) }
        },
        limit = IOS_FOREGROUND_BYTES,
    )
}

/**
 * The advertisement a host puts on the air, joined from its channels' parts ([AdJoin]): the channels in it ([tech]),
 * [main], [scanResponse], an [iBeacon] (iOS: the whole advertisement; Android: already in [main] as Apple's
 * manufacturer data), on iOS the [backgroundUuids] for a locked iPhone, and the channels left out ([dropped]).
 */
data class Advert(
    val tech: List<String>,
    val main: AdData,
    val scanResponse: AdData,
    val iBeacon: IBeaconAd?,
    val backgroundUuids: List<String>,
    val dropped: List<String>,
    val layout: String?,
    val platform: AirPlatform,
) {
    val isEmpty: Boolean get() = main.isEmpty && scanResponse.isEmpty && iBeacon == null

    /** What the journal's `adv` says of it (ADR 0017 §4): the channels, the bytes, what didn't fit. */
    fun report(): AdvertReport = AdvertReport(
        tech = tech,
        layout = layout,
        main = if (platform == AirPlatform.ANDROID) AdBudget.android(main) else AdBudget.ios(main),
        scanResponse = scanResponse.takeIf { !it.isEmpty }?.let { AdBudget.android(it) },
        dropped = dropped,
        background = backgroundUuids.size,
    )
}

/** An advertisement's layout in the journal (`adv`): [main] and [scanResponse] by field, the channels left out. */
data class AdvertReport(
    val tech: List<String>,
    val layout: String?,
    val main: AdBudget.Packet,
    val scanResponse: AdBudget.Packet?,
    val dropped: List<String>,
    val background: Int = 0,
)

/**
 * Joins the channels' parts into one advertisement by the OS's rules (ADR 0017 §2.2), in the order given (the first
 * has the room first); a part that doesn't fit or clashes with one taken is left out and named in [Advert.dropped].
 *
 * - Android: a legacy packet and its scan response, each [AdBudget.LEGACY_BYTES] at most; an iBeacon is Apple's
 *   manufacturer data in the packet; no overflow area (only iOS makes one).
 * - iOS and the Mac: the name and the service UUIDs only (CoreBluetooth takes nothing else), or an iBeacon, which is
 *   a whole advertisement of its own; [Advert.backgroundUuids] on iOS only.
 */
object AdJoin {
    fun join(parts: List<AdPart>, platform: AirPlatform): Advert =
        if (platform == AirPlatform.ANDROID) android(parts) else apple(parts, platform)

    private fun android(parts: List<AdPart>): Advert {
        var main = AdData()
        var response = AdData()
        val kept = ArrayList<AdPart>()
        val dropped = ArrayList<String>()
        for (part in parts) {
            val beacon = part.iBeacon?.let {
                AdData(manufacturerData = mapOf(GameAir.APPLE_COMPANY_ID to it.frameHex()))
            }
            val partMain = part.main + (beacon ?: AdData())
            val nothingHere = partMain.isEmpty && part.scanResponse.isEmpty
            val clash = clashes(main, partMain) || clashes(response, part.scanResponse)
            val nextMain = main + partMain
            val nextResponse = response + part.scanResponse
            if (nothingHere || clash || !AdBudget.android(nextMain).fits || !AdBudget.android(nextResponse).fits) {
                dropped += part.tech
                continue
            }
            main = nextMain
            response = nextResponse
            kept += part
        }
        return Advert(
            tech = kept.map { it.tech },
            main = main,
            scanResponse = response,
            iBeacon = null,
            backgroundUuids = emptyList(),
            dropped = dropped,
            layout = kept.firstNotNullOfOrNull { it.layout },
            platform = AirPlatform.ANDROID,
        )
    }

    private fun apple(parts: List<AdPart>, platform: AirPlatform): Advert {
        var main = AdData()
        var beacon: IBeaconAd? = null
        val background = ArrayList<String>()
        val kept = ArrayList<AdPart>()
        val dropped = ArrayList<String>()
        for (part in parts) {
            val unsupported = part.main.serviceData.isNotEmpty() || part.main.manufacturerData.isNotEmpty() ||
                !part.scanResponse.isEmpty || part.main.includeTxPower
            val onlyBackground = part.main.isEmpty && part.iBeacon == null
            when {
                unsupported -> dropped += part.tech

                onlyBackground && (platform != AirPlatform.IOS || part.backgroundUuids.isEmpty()) ->
                    dropped +=
                        part.tech

                onlyBackground -> {
                    background += part.backgroundUuids
                    kept += part
                }

                part.iBeacon != null -> if (beacon == null && main.isEmpty) {
                    beacon = part.iBeacon
                    kept += part
                } else {
                    dropped += part.tech
                }

                beacon != null || (main.localName != null && part.main.localName != null) -> dropped += part.tech

                else -> {
                    main += part.main
                    if (platform == AirPlatform.IOS) background += part.backgroundUuids
                    kept += part
                }
            }
        }
        return Advert(
            tech = kept.map { it.tech },
            main = main,
            scanResponse = AdData(),
            iBeacon = beacon,
            backgroundUuids = background.distinct(),
            dropped = dropped,
            layout = kept.firstNotNullOfOrNull { it.layout },
            platform = platform,
        )
    }

    /** The same service data UUID or company id twice: the second would overwrite the first. */
    private fun clashes(taken: AdData, part: AdData): Boolean = part.serviceData.keys.any { it in taken.serviceData } ||
        part.manufacturerData.keys.any { it in taken.manufacturerData } ||
        (part.localName != null && taken.localName != null)
}
