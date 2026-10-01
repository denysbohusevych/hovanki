package app.hovanki.radar

import app.hovanki.shared.rules.OverflowArea
import app.hovanki.shared.rules.OverflowCode
import app.hovanki.shared.rules.OverflowLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every channel reads what it advertises, and only that (ADR 0017 §2.2): the three layouts of an Android hider, an
 * iPhone hider's name, a seeker's iBeacon (as Android sees the frame and as CoreLocation ranges it), a locked
 * iPhone's overflow mask (as Android's raw bytes and as the UUIDs iOS lists).
 */
class ChannelCodecsTest {
    private val token = "c0ffee42"
    private val service = GameAir.SERVICE_UUID

    @Test
    fun eachLayoutIsReadByItsOwnChannelOnly() {
        for (layout in RadarCatalog.hiderLayouts) {
            val frame = asAndroidHearsIt(layoutAdvert(layout))
            val readings = RadarCatalog.channels.mapNotNull { it.decode(frame) }
            assertEquals(listOf(layout.id), readings.map { it.tech }, layout.id)
            assertEquals(listOf(token), readings.single().tokens)
            assertEquals(ChannelUse.GAME, readings.single().use)
        }
        val mfr = asAndroidHearsIt(layoutAdvert(RadarCatalog.MFR))
        assertEquals(SightingVia.MANUFACTURER_DATA, RadarCatalog.MFR.decode(mfr)?.via)
        val bare = asAndroidHearsIt(layoutAdvert(RadarCatalog.BARE))
        assertEquals(SightingVia.SERVICE_DATA, RadarCatalog.BARE.decode(bare)?.via)
    }

    @Test
    fun theLayoutsSayWhatTheyAre() {
        assertEquals("ble.service_data.scan_response", RadarCatalog.SCAN_RESPONSE.id)
        assertEquals("ble.service_data.bare", RadarCatalog.BARE.id)
        assertEquals("ble.service_data.mfr", RadarCatalog.MFR.id)
        val advert = layoutAdvert(RadarCatalog.MFR)
        assertEquals("mfr", advert.layout)
        // `48 01`, the game's UUID, the token: what a scan filter for the layout asks of the test company's data.
        assertEquals("4801" + service.replace("-", "").lowercase() + token, advert.main.manufacturerData[0xFFFF])
    }

    @Test
    fun aScanResponseHeardWithoutItsAnswerHasNoToken() {
        val passive = HeardFrame(-60, 0, RadioApi.ANDROID_LE, serviceUuids = listOf(service))
        assertTrue(RadarCatalog.channels.none { it.decode(passive) != null })
    }

    @Test
    fun othersFramesAreNotTokens() {
        val otherUuid = "11111111-2222-3333-4444-555555555555"
        val frames = listOf(
            // Somebody else's service data, and ours with a broken token.
            HeardFrame(-60, 0, RadioApi.ANDROID_LE, serviceData = mapOf(otherUuid to token)),
            HeardFrame(-60, 0, RadioApi.ANDROID_LE, serviceData = mapOf(service to "c0ffee")),
            // A name without the game's service, and a name that is no token.
            HeardFrame(-60, 0, RadioApi.ANDROID_LE, localName = token),
            HeardFrame(-60, 0, RadioApi.ANDROID_LE, serviceUuids = listOf(service), localName = "Pixel 9"),
            // The test company's data of another app.
            HeardFrame(-60, 0, RadioApi.ANDROID_LE, manufacturerData = mapOf(0xFFFF to "0102030405")),
            // An iBeacon of another UUID.
            HeardFrame(
                -60,
                0,
                RadioApi.ANDROID_LE,
                manufacturerData = mapOf(0x004C to "0215" + "11".repeat(16) + "0102030405"),
            ),
            HeardFrame(-60, 0, RadioApi.CORELOCATION_RANGING, iBeacon = HeardIBeacon(otherUuid, 1, 2)),
        )
        for (frame in frames) assertTrue(RadarCatalog.channels.none { it.decode(frame) != null }, "$frame")
    }

    @Test
    fun anIPhoneHidersNameIsReadBareOrAfterThePrefix() {
        for (name in listOf(token, "hv$token")) {
            val frame =
                HeardFrame(-60, 0, RadioApi.COREBLUETOOTH, serviceUuids = listOf(service.lowercase()), localName = name)
            val reading = RadarCatalog.NAME.decode(frame)
            assertEquals(listOf(token), reading?.tokens)
            assertEquals(SightingVia.NAME, reading?.via)
        }
    }

    @Test
    fun aSeekersIBeaconIsReadByAndroidAndByCoreLocation() {
        val advert = RadarCatalog.advert(token, AirRole.SEEKER, AirPlatform.ANDROID, RadioOptions(), shadow = false)
        val reading = RadarCatalog.IBEACON.decode(asAndroidHearsIt(advert))
        assertEquals(listOf(token), reading?.tokens)
        assertEquals(SightingVia.IBEACON, reading?.via)
        val ranged = HeardFrame(-70, 0, RadioApi.CORELOCATION_RANGING, iBeacon = HeardIBeacon(service, 0xc0ff, 0xee42))
        assertEquals(listOf(token), RadarCatalog.IBEACON.decode(ranged)?.tokens)
    }

    @Test
    fun aLockedIPhonesMaskIsReadInTheShadow() {
        val advert = RadarCatalog.advert(token, AirRole.HIDER, AirPlatform.IOS, RadioOptions(), shadow = true)
        assertEquals(listOf(RadarCatalog.NAME.id, RadarCatalog.OVERFLOW.id), advert.tech)
        assertEquals(service, advert.backgroundUuids.first())
        val bits = advert.backgroundUuids.mapNotNull(OverflowArea::bitOf).toSet()
        assertEquals(OverflowCode.encode(token), bits)

        // Android: the raw mask in Apple's manufacturer data, with the service's own bit (117) beside the token's.
        val mask = OverflowArea.maskOf(bits + OverflowArea.OUR_SERVICE_BIT)
        val raw = HeardFrame(-80, 0, RadioApi.ANDROID_LE, manufacturerData = mapOf(0x004C to "01" + hex(mask)))
        val onAndroid = RadarCatalog.channels.mapNotNull { it.decode(raw) }.single()
        assertEquals(RadarCatalog.OVERFLOW.id, onAndroid.tech)
        assertEquals(ChannelUse.SHADOW, onAndroid.use)
        assertEquals(listOf(token), onAndroid.tokens)
        assertEquals(SightingVia.OVERFLOW_RAW, onAndroid.via)

        // An iPhone on the screen: the table's UUIDs CoreBluetooth lists.
        val listed = HeardFrame(-80, 0, RadioApi.COREBLUETOOTH, overflowUuids = bits.map(OverflowArea::uuid))
        assertEquals(listOf(token), RadarCatalog.OVERFLOW.decode(listed)?.tokens)
        assertEquals(SightingVia.OVERFLOW_UUIDS, RadarCatalog.OVERFLOW.decode(listed)?.via)
    }

    @Test
    fun aDamagedMaskGivesItsCandidatesAndAForeignOneNone() {
        val bits = OverflowCode.encode(token)
        // Another app set the other half of one pair: two candidates, ours among them.
        val pair = OverflowLayout.LAB.tokenPairs.first().toList()
        val damaged = HeardFrame(-80, 0, RadioApi.ANDROID_LE, overflowUuids = (bits + pair).map(OverflowArea::uuid))
        val tokens = RadarCatalog.OVERFLOW.decode(damaged)?.tokens.orEmpty()
        assertEquals(2, tokens.size)
        assertTrue(token in tokens)
        // Bit 96 alone (seen on the street): not ours.
        val foreign =
            HeardFrame(
                -80,
                0,
                RadioApi.ANDROID_LE,
                manufacturerData = mapOf(0x004C to "01" + hex(OverflowArea.maskOf(setOf(96)))),
            )
        assertNull(RadarCatalog.OVERFLOW.decode(foreign))
        assertEquals(ForeignFrame(ForeignKind.MASK, setOf(96)), RadarCatalog.foreign(foreign))
    }

    @Test
    fun aRecordsRawBytesEndAtItsLastFieldNotItsLastNonZeroByte() {
        // A 128-bit UUID (zeros inside a field are data), its service data with a token ending in 00, the padding.
        val uuid = "1107" + "00".repeat(16)
        val data = "1521" + "00".repeat(16) + "c0ffee00"
        val record = AirHex.bytes(uuid + data + "00".repeat(20))
        assertEquals(uuid + data, AirHex.of(AirHex.recordFields(record)))
        // A last field longer than the buffer: everything there is.
        assertEquals("05ff4c00", AirHex.of(AirHex.recordFields(AirHex.bytes("05ff4c00"))))
        assertEquals("", AirHex.of(AirHex.recordFields(ByteArray(31))))
    }

    /** An Android hider with [layout]: with a journal, the player's number picks it. */
    private fun layoutAdvert(layout: RadarChannel): Advert {
        val options = RadioOptions(playerNumber = RadarCatalog.hiderLayouts.indexOf(layout))
        return RadarCatalog.advert(token, AirRole.HIDER, AirPlatform.ANDROID, options, shadow = true)
    }

    /** What an Android's active scan shows of [advert]: the packet and the scan response in one record. */
    private fun asAndroidHearsIt(advert: Advert): HeardFrame {
        val all = advert.main + advert.scanResponse
        return HeardFrame(
            rssi = -60,
            atMillis = 0,
            api = RadioApi.ANDROID_LE,
            localName = all.localName,
            serviceUuids = all.serviceUuids,
            serviceData = all.serviceData,
            manufacturerData = all.manufacturerData,
        )
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
