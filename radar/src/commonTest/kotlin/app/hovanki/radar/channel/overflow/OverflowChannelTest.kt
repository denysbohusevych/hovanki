package app.hovanki.radar.channel.overflow

import app.hovanki.radar.AdPart
import app.hovanki.radar.AirFrame
import app.hovanki.radar.Decoded
import app.hovanki.radar.RadarRole
import app.hovanki.radar.RadarService
import app.hovanki.radar.RadioApi
import app.hovanki.radar.SightingVia
import app.hovanki.radar.TOKEN
import app.hovanki.shared.rules.AppleData
import app.hovanki.shared.rules.OverflowArea
import app.hovanki.shared.rules.OverflowCode
import app.hovanki.shared.rules.OverflowLayout
import kotlin.test.Test
import kotlin.test.assertEquals

class OverflowChannelTest {
    private val bits = OverflowCode.encode(TOKEN)

    @Test
    fun theHiderAdvertisesTheServiceAndTheTablesUuidOfEveryBit() {
        val parts = OverflowChannel.advertise(TOKEN, RadarRole.HIDER)
        assertEquals(OverflowParts(bits), parts)
        assertEquals(AdPart.ServiceUuid(RadarService.UUID), parts.first())
        val advertised = parts.drop(1).map { OverflowArea.bitOf((it as AdPart.ServiceUuid).uuid) }.toSet()
        assertEquals(bits, advertised)
        assertEquals(emptyList(), OverflowChannel.advertise(TOKEN, RadarRole.SEEKER))
    }

    @Test
    fun theRawMaskOfAndroidAndAMac() {
        assertEquals(listOf(Decoded(TOKEN, SightingVia.OVERFLOW_RAW)), OverflowChannel.decode(masked(bits)))
    }

    @Test
    fun theUuidsAnIphoneLists() {
        val frame = AirFrame(1L, -70, RadioApi.COREBLUETOOTH, "peer", overflowUuids = bits.map(OverflowArea::uuid))
        assertEquals(listOf(Decoded(TOKEN, SightingVia.OVERFLOW_UUIDS)), OverflowChannel.decode(frame))
    }

    @Test
    fun aDamagedPairGivesItsFirstCandidateAndAll() {
        val pair = OverflowLayout.LAB.tokenPairs.last()
        val decoded = OverflowChannel.decode(masked(bits + pair.first + pair.second)).single()
        assertEquals(2, decoded.candidates.size)
        assertEquals(decoded.candidates.first(), decoded.token)
        assertEquals(true, TOKEN in decoded.candidates)
    }

    @Test
    fun anotherMaskOrAnIBeaconReadsAsNothing() {
        assertEquals(emptyList(), OverflowChannel.decode(masked(setOf(OverflowArea.APPLE_WATCH_BIT))))
        val beacon = byteArrayOf(AppleData.IBEACON.toByte(), 0x15) + ByteArray(21)
        val frame = AirFrame(1L, -70, RadioApi.ANDROID_LE, "peer", manufacturerData = mapOf(0x004C to beacon))
        assertEquals(emptyList(), OverflowChannel.decode(frame))
    }

    private fun masked(bits: Set<Int>): AirFrame {
        val data = byteArrayOf(AppleData.OVERFLOW.toByte()) + OverflowArea.maskOf(bits)
        return AirFrame(
            1L,
            -70,
            RadioApi.ANDROID_LE,
            "peer",
            manufacturerData = mapOf(RadarService.APPLE_COMPANY_ID to data),
        )
    }
}
