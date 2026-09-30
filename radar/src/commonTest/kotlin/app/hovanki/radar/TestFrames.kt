package app.hovanki.radar

/**
 * A frame as a phone that hears everything raw would read [parts] (Android with a filter that matches): the fields
 * straight from the parts, both packets merged, an iBeacon as Apple's manufacturer data. For the channels' codecs;
 * what the platforms really let through is [OsRules]' and is tested there.
 */
fun frameOf(
    parts: List<AdPart>,
    rssi: Int = -60,
    api: RadioApi = RadioApi.ANDROID_LE,
    atMillis: Long = 1_790_000_000_000L,
): AirFrame = AirFrame(
    atMillis = atMillis,
    rssi = rssi,
    api = api,
    peer = "peer",
    name = parts.filterIsInstance<AdPart.LocalName>().firstOrNull()?.name,
    serviceUuids = parts.filterIsInstance<AdPart.ServiceUuid>().map { BleUuid.normalize(it.uuid) },
    serviceData = parts.filterIsInstance<AdPart.ServiceData>().associate { it.uuid to it.data },
    manufacturerData = parts.mapNotNull {
        when (it) {
            is AdPart.ManufacturerData -> it.companyId to it.data
            is AdPart.IBeacon -> RadarService.APPLE_COMPANY_ID to IBeaconBytes.of(it)
            else -> null
        }
    }.toMap(),
)

const val TOKEN = "0a1b2c3d"
