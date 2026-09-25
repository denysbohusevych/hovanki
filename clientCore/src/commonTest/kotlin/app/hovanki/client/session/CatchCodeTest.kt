package app.hovanki.client.session

import app.hovanki.client.network.testSession
import app.hovanki.client.network.testSnapshot
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.CatchView
import app.hovanki.shared.protocol.MyState
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.totp.catchCodeTotp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatchCodeTest {
    private val secret = "00112233445566778899aabbccddeeff00112233"
    private val hider = testSnapshot().copy(
        me = MyState(testSession.playerId, Role.HIDER, PlayerStatus.ACTIVE, catchCodeSecret = secret),
    )
    private val claim = CatchView(CatchId("c1"), PlayerId("seeker"), testSession.playerId, CatchStatus.AWAITING_CODE, 0)

    @Test
    fun codeOfTheCurrentPeriodWhileAClaimAwaitsIt() {
        val code = hider.copy(catches = listOf(claim)).catchCodeToShow(serverNowMillis = 61_000)

        assertEquals(catchCodeTotp(secret, hider.settings.rules).codeAt(61_000), code?.code)
        assertEquals(29_000, code?.millisUntilNext)
    }

    @Test
    fun noCodeWithoutAClaimAwaitingIt() {
        assertNull(hider.catchCodeToShow(0))
        assertNull(hider.copy(catches = listOf(claim.copy(status = CatchStatus.DISPUTED))).catchCodeToShow(0))
        assertNull(hider.copy(catches = listOf(claim.copy(hiderId = PlayerId("other")))).catchCodeToShow(0))
    }
}
