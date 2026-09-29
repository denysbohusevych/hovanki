package app.hovanki.server.radio

import app.hovanki.server.game.CalibrationAnchor
import app.hovanki.server.game.CalibrationBucket
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
}
