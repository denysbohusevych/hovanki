package app.hovanki.server.radio

import app.hovanki.server.game.CalibrationAnchor
import app.hovanki.server.game.CalibrationBucket
import app.hovanki.shared.lab.CalibrationSample
import app.hovanki.shared.lab.ModelOffsets
import app.hovanki.shared.protocol.Carry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** `radio_calibration` adds up across games (docs/adr/0012-nearby-radar.md, «Калибровка»). */
@SpringBootTest
class RadioCalibrationRepositoryTest(@Autowired private val repository: RadioCalibrationRepository) {
    @Test
    fun theCountsAddUp() {
        val model = "Test ${UUID.randomUUID()}"
        val first = Instant.parse("2026-06-01T12:00:00Z")
        val bucket =
            CalibrationBucket(model, "iPhone15,2", Carry.IN_HAND, Carry.IN_POCKET, CalibrationAnchor.CATCH, -58, 3)
        repository.add(listOf(bucket, bucket.copy(rssiDbm = -60, readings = 1)), first)
        repository.add(listOf(bucket.copy(readings = 2)), first.plusSeconds(60))

        val rows = repository.all().filter { it.hearerModel == model }
        assertEquals(listOf(bucket.copy(readings = 5), bucket.copy(rssiDbm = -60, readings = 1)), rows)
    }

    @Test
    fun theCatchesByModelGiveTheLabsModelOffsets() {
        val model = "Test ${UUID.randomUUID()}"
        val at = Instant.parse("2026-06-01T12:00:00Z")
        val catch = CalibrationBucket(model, "Pixel 8", Carry.IN_HAND, Carry.IN_HAND, CalibrationAnchor.CATCH, -66, 15)
        repository.add(
            listOf(
                catch,
                // Held otherwise out of a pocket: the same models, counted together.
                catch.copy(heardCarry = Carry.UNKNOWN, readings = 10),
                // A pocket damps the reading: not the models' doing, left out.
                catch.copy(heardCarry = Carry.IN_POCKET, rssiDbm = -78, readings = 50),
                catch.copy(hearerCarry = Carry.IN_POCKET, rssiDbm = -78, readings = 50),
                // Not a catch: left out.
                catch.copy(anchor = CalibrationAnchor.ALL, rssiDbm = -90, readings = 100),
            ),
            at,
        )

        val samples = repository.catches().filter { it.hearerModel == model }
        assertEquals(listOf(CalibrationSample(model, "Pixel 8", -66, 25)), samples)
        // A metre (a catch) should sound like −60 dBm: this pair hears 6 dB too little.
        assertEquals(6.0, ModelOffsets.fromCatches(samples).of(model, "Pixel 8"))
    }
}
