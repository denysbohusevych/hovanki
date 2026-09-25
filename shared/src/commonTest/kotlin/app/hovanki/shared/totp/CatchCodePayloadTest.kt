package app.hovanki.shared.totp

import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatchCodePayloadTest {
    @Test
    fun roundTrip() {
        val payload = CatchCodePayload(GameId("g1"), PlayerId("p1"), "0427")
        assertEquals("hovanki:1:g1:p1:0427", payload.encode())
        assertEquals(payload, CatchCodePayload.decode(payload.encode()))
    }

    @Test
    fun rejectsForeignQrCodes() {
        assertNull(CatchCodePayload.decode("https://example.com"))
        assertNull(CatchCodePayload.decode("hovanki:2:g1:p1:0427"))
        assertNull(CatchCodePayload.decode("hovanki:1:g1::0427"))
    }
}
