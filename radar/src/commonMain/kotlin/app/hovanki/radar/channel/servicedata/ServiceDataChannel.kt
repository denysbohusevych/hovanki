package app.hovanki.radar.channel.servicedata

import app.hovanki.radar.AdPart
import app.hovanki.radar.AirFrame
import app.hovanki.radar.Availability
import app.hovanki.radar.BleUuid
import app.hovanki.radar.Decoded
import app.hovanki.radar.RadarCaps
import app.hovanki.radar.RadarChannel
import app.hovanki.radar.RadarRole
import app.hovanki.radar.RadarService
import app.hovanki.radar.ScanInterest
import app.hovanki.radar.SightingVia
import app.hovanki.radar.TechniqueStatus
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.rules.RadarToken

/**
 * `ble.service_data` (docs/adr/0017-radar-techniques-and-big-run.md, section 2.3): an Android hider's token as 4
 * bytes of data, in three competing layouts the radio lab compares. Only Android can send them (iOS lets an app
 * advertise no data); everybody hears them. The first layout, the game's service UUID and its data in one packet, was
 * 40 bytes against Android's 31 and never went out.
 */
sealed class ServiceDataChannel(final override val id: String, final override val status: TechniqueStatus) :
    RadarChannel {
    /**
     * The game's: the service UUID in the advertisement (18 bytes), the token as its service data in the scan response
     * (22), which Android and CoreBluetooth merge into one record, so an iPhone's filter by the service matches it.
     */
    data object ScanResponse : ServiceDataChannel("ble.service_data.scan_response", TechniqueStatus.GAME) {
        override fun parts(token: String): List<AdPart> = listOf(
            AdPart.ServiceUuid(RadarService.UUID),
            AdPart.ServiceData(RadarService.UUID, token.hexToBytes(), inScanResponse = true),
        )

        override fun interests(): List<ScanInterest> = listOf(ScanInterest.Service(RadarService.UUID))

        override fun decode(frame: AirFrame): List<Decoded> =
            if (frame.lists(RadarService.UUID)) serviceData(frame) else emptyList()
    }

    /**
     * The service data alone, no list of UUIDs (22 bytes). Android hears it by a filter on the service data; an
     * iPhone's scan with a filter by the service does not match a frame without the UUID in its list, and no host
     * scans without one (an unfiltered scan is dead in the background on both platforms): iPhones don't hear `bare`,
     * Android and the Mac do. The run measures whether that trade is worth the 18 bytes.
     */
    data object Bare : ServiceDataChannel("ble.service_data.bare", TechniqueStatus.LAB) {
        override fun parts(token: String): List<AdPart> =
            listOf(AdPart.ServiceData(RadarService.UUID, token.hexToBytes()))

        override fun interests(): List<ScanInterest> = listOf(ScanInterest.Service(RadarService.UUID))

        override fun decode(frame: AirFrame): List<Decoded> =
            if (frame.lists(RadarService.UUID)) emptyList() else serviceData(frame)
    }

    /**
     * The service UUID and the token in manufacturer data of [HOVANKI_COMPANY_ID] (2 + 2 + 16 + 4 = 24 bytes). Heard
     * by Android and a Mac, whose filters take manufacturer data; never by an iPhone, which can't filter by it.
     */
    data object Mfr : ServiceDataChannel("ble.service_data.mfr", TechniqueStatus.LAB) {
        private val prefix = BleUuid.bytes(RadarService.UUID)

        override fun parts(token: String): List<AdPart> =
            listOf(AdPart.ManufacturerData(HOVANKI_COMPANY_ID, prefix + token.hexToBytes()))

        override fun interests(): List<ScanInterest> = listOf(ScanInterest.Manufacturer(HOVANKI_COMPANY_ID, prefix))

        override fun decode(frame: AirFrame): List<Decoded> {
            val data = frame.manufacturerData[HOVANKI_COMPANY_ID] ?: return emptyList()
            if (data.size != prefix.size + TOKEN_BYTES || !data.copyOf(prefix.size).contentEquals(prefix)) {
                return emptyList()
            }
            return tokenOf(data.copyOfRange(prefix.size, data.size))
        }
    }

    protected abstract fun parts(token: String): List<AdPart>

    override fun advertise(token: String, role: RadarRole): List<AdPart> =
        if (role == RadarRole.HIDER && RadarToken.isWellFormed(token)) parts(token) else emptyList()

    override fun available(caps: RadarCaps): Availability = caps.noBluetoothLe
        ?: if (caps.platform == Platform.ANDROID && this is ScanResponse && !caps.canScanResponse) {
            Availability.Unavailable("no scan response")
        } else {
            Availability.Available
        }

    protected fun serviceData(frame: AirFrame): List<Decoded> =
        frame.serviceData(RadarService.UUID)?.let(::tokenOf).orEmpty()

    protected fun tokenOf(bytes: ByteArray): List<Decoded> {
        if (bytes.size != TOKEN_BYTES) return emptyList()
        val token = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return listOf(Decoded(token, SightingVia.SERVICE_DATA))
    }

    companion object {
        /** The test company id (Bluetooth SIG: `0xFFFF`, for tests only), until the game has its own. */
        const val HOVANKI_COMPANY_ID = 0xFFFF

        private const val TOKEN_BYTES = RadarToken.LENGTH / 2

        private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) {
            substring(2 * it, 2 * it + 2).toInt(16).toByte()
        }
    }
}
