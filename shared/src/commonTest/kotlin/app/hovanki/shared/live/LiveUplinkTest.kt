package app.hovanki.shared.live

import app.hovanki.shared.live.v2.Up
import app.hovanki.shared.protocol.ApiError
import app.hovanki.shared.protocol.ChatChannel
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.DeviceReport
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.NearbySighting
import app.hovanki.shared.protocol.PlayerId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Up both ways, the values' encoding, the chat and the errors of a Down (docs/live-v2/spec.md §7, §10, §11, §13).
 */
class LiveUplinkTest {
    private val time = 1_760_457_600_000L

    private fun throughUp(
        samples: List<LocationSample> = emptyList(),
        nearby: List<NearbySighting> = emptyList(),
        device: DeviceReport? = null,
    ): Up = Up.ADAPTER.decode(Up.ADAPTER.encode(LiveUplink.up(1, 0, time, samples, nearby, device)))

    @Test
    fun fixesComeBackRounded() {
        val random = Random(7)
        val samples =
            List(100) {
                LocationSample(
                    point = GeoPoint(random.nextDouble(-90.0, 90.0), random.nextDouble(-180.0, 180.0)),
                    accuracyMeters = random.nextDouble(0.1, 200.0),
                    timestampMillis = time - random.nextLong(0, 60_000),
                    isMock = random.nextBoolean(),
                )
            }
        val back = LiveUplink.samples(throughUp(samples))
        assertEquals(
            samples.map {
                it.copy(
                    point = LiveValues.round(it.point),
                    accuracyMeters = LiveValues.roundAccuracy(it.accuracyMeters),
                )
            },
            back,
        )
    }

    @Test
    fun aJumpAcrossTheAntimeridianComesBack() {
        val samples =
            listOf(
                LocationSample(GeoPoint(-89.9999999, -179.9999999), 1.0, time),
                LocationSample(GeoPoint(89.9999999, 179.9999999), 1.0, time),
                LocationSample(GeoPoint(-89.9999999, -179.9999999), 1.0, time),
            )
        assertEquals(samples, LiveUplink.samples(throughUp(samples)))
    }

    @Test
    fun atMostOneHundredFixesAndTwoHundredSightingsGo() {
        val up =
            LiveUplink.up(
                seq = 1,
                ackState = 0,
                timeMillis = time,
                samples = List(150) { LocationSample(GeoPoint(50.0, 30.0), 5.0, time) },
                nearby = List(250) { NearbySighting("0000000a", -60, time) },
            )
        assertEquals(LiveLimits.MAX_FIXES_PER_UP, up.fixes.size)
        assertEquals(LiveLimits.MAX_SIGHTINGS_PER_UP, up.sightings.size)
    }

    @Test
    fun aSightingWithoutATokenIsNotSent() {
        val nearby =
            listOf(
                NearbySighting("3fa91c07", -71, time - 1),
                NearbySighting("3FA91C07", -71, time),
                NearbySighting("3fa91c0", -71, time),
                NearbySighting("ffffffff", -100, time + 5),
            )
        assertEquals(listOf(nearby[0], nearby[3]), LiveUplink.nearby(throughUp(nearby = nearby)))
    }

    @Test
    fun theDeviceReportComesBack() {
        assertEquals(LiveFixtures.device(), LiveUplink.deviceReport(throughUp(device = LiveFixtures.device()).device!!))
        assertEquals(DeviceReport(), LiveUplink.deviceReport(throughUp(device = DeviceReport()).device!!))
        assertNull(throughUp().device)
    }

    @Test
    fun valuesAreEncodedAsTheSpecSays() {
        assertEquals(504501000, LiveValues.toE7(50.4501))
        // Half away from zero, both signs.
        assertEquals(1, LiveValues.toE7(0.00000005))
        assertEquals(-1, LiveValues.toE7(-0.00000005))
        assertEquals(1_800_000_000, LiveValues.toE7(180.0))
        assertEquals(-1_800_000_000, LiveValues.toE7(-180.0))
        // Accuracy in decimetres, up; whole decimetres stay.
        assertEquals(201, LiveValues.toDecimetres(20.04))
        assertEquals(42, LiveValues.toDecimetres(4.2))
        assertEquals(7, LiveValues.toDecimetres(0.7))
        assertEquals(39, LiveValues.toDecimetres(3.81))
        assertEquals(0, LiveValues.toDecimetres(0.0))
        assertEquals(0, LiveValues.toDecimetres(Double.NaN))
        // Tokens: 8 lowercase hex characters, big-endian.
        assertEquals(0x3fa91c07, LiveValues.tokenToInt("3fa91c07"))
        assertEquals("ffffffff", LiveValues.tokenFromInt(-1))
        assertEquals("00000000", LiveValues.tokenFromInt(0))
        assertNull(LiveValues.tokenToInt("3fa91c0g"))
        assertNull(LiveValues.tokenToInt("3fa91c071"))
        // Secrets: hex both ways, null as empty.
        assertEquals("00ff7a", LiveValues.secretFromBytes(LiveValues.secretToBytes("00ff7a")))
        assertNull(LiveValues.secretFromBytes(LiveValues.secretToBytes(null)))
    }

    @Test
    fun theChatFitsItsLimitAndSaysWhenMoreWaits() {
        val messages =
            List(100) { ChatMessage(it + 1L, PlayerId("p$it"), "ж".repeat(150), time + it, ChatChannel.HIDERS) }
        val chat = LiveDownlink.fitChat(messages)
        assertTrue(chat.more)
        assertTrue(chat.messages.size in 2..messages.size - 1)
        val bytes = chat.messages.sumOf { app.hovanki.shared.live.v2.ChatMessage.ADAPTER.encodedSize(it) + 3 }
        assertTrue(bytes <= LiveLimits.MAX_CHAT_BYTES)
        assertEquals(messages.take(chat.messages.size), chat.messages.map(LiveDownlink::chatMessage))
        val few = LiveDownlink.fitChat(messages.take(3))
        assertEquals(3, few.messages.size)
        assertEquals(false, few.more)
        // One message always goes, whatever the limit.
        assertEquals(1, LiveDownlink.fitChat(messages, maxBytes = 1).messages.size)
    }

    @Test
    fun errorsComeBackAndAnUnknownReasonIsNull() {
        val error = ApiError(ErrorCode.BAD_REQUEST, "slow down", ErrorReason.TOO_MANY_REQUESTS, untilMillis = time)
        val wire = LiveDownlink.error(error, retryAfterSeconds = 3)
        assertEquals(3, wire.retry_after_s)
        assertEquals(error, LiveDownlink.apiError(wire))
        assertEquals(ErrorCode.INTERNAL, LiveDownlink.apiError(app.hovanki.shared.live.v2.Error()).code)
        assertNull(LiveDownlink.apiError(wire.copy(reason = "SOMETHING_NEWER")).reason)
        for (code in ErrorCode.entries) {
            assertEquals(
                code,
                LiveDownlink.apiError(LiveDownlink.error(ApiError(code, ""))).code,
            )
        }
    }
}
