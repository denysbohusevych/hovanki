package app.hovanki.radar

import app.hovanki.radar.channel.ibeacon.IBeaconChannel
import app.hovanki.shared.rules.AppleData
import app.hovanki.shared.rules.OverflowArea
import kotlin.test.Test
import kotlin.test.assertEquals

class AirTallyTest {
    private val second = 1_790_000_000_000L

    @Test
    fun ourFramesWholeTheOthersOnceASecond() {
        val trace = RecordingTrace()
        val tally = AirTally(trace, RadarCatalog.game)
        val ours = frameOf(IBeaconChannel.advertise(TOKEN, RadarRole.SEEKER), atMillis = second + 10)
        val mask = AirFrame(
            second + 20,
            -80,
            RadioApi.ANDROID_LE,
            "a",
            manufacturerData = mapOf(
                0x004C to byteArrayOf(AppleData.OVERFLOW.toByte()) + OverflowArea.maskOf(listOf(3, 69)),
            ),
        )
        val beacon = frameOf(
            listOf(AdPart.IBeacon("E2C56DB5-DFFB-48D2-B060-D0F5A71096E0", 1, 2, -59)),
            atMillis =
            second + 30,
        )
        val listed =
            AirFrame(second + 40, -80, RadioApi.COREBLUETOOTH, "b", overflowUuids = listOf(OverflowArea.uuid(5)))
        val next = AirFrame(second + 1_000, -90, RadioApi.ANDROID_LE, "c", name = "tv")

        assertEquals(listOf("ble.ibeacon"), tally.heard(ours).map { it.first })
        for (frame in listOf(mask, beacon, listed, next)) assertEquals(emptyList(), tally.heard(frame))

        assertEquals(listOf(ours), trace.frames.map { it.first })
        assertEquals(listOf(AirSecond(second, 3, 1, 2, 2, setOf(3, 5, 69))), trace.seconds)
        tally.flush()
        assertEquals(AirSecond(second + 1_000, 1, 0, 0, 0, emptySet()), trace.seconds.last())
    }

    @Test
    fun aRegionEventIsAScanAction() {
        val trace = RecordingTrace()
        val tally = AirTally(trace, RadarCatalog.channels)
        tally.heard(AirFrame(second, 0, RadioApi.CORELOCATION_REGION, null, regionEvent = RegionEvent.ENTER))
        tally.flush()
        assertEquals(listOf("region_enter corelocation_region"), trace.scans)
        assertEquals(emptyList(), trace.seconds)
    }
}
