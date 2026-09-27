package app.hovanki.client.session

import app.hovanki.client.network.testSession
import app.hovanki.client.network.testSnapshot
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.CatchView
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.MyState
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.totp.CatchCodePayload
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

    @Test
    fun anActiveHiderCanShowTheCodeWithoutAClaimWhileTheSeekersSearch() {
        val seeking = hider.copy(phase = GamePhase.SEEKING)
        val code = seeking.myCatchCode(serverNowMillis = 61_000)

        assertEquals(catchCodeTotp(secret, hider.settings.rules).codeAt(61_000), code?.code)
        assertEquals(
            CatchCodePayload(testSession.gameId, testSession.playerId, code!!.code).encode(),
            seeking.catchQr(code),
        )
        assertNull(hider.myCatchCode(0), "not in the lobby")
        assertNull(hider.copy(phase = GamePhase.HIDING).myCatchCode(0), "nobody searches yet")
        assertNull(seeking.copy(me = seeking.me.copy(status = PlayerStatus.CAUGHT)).myCatchCode(0), "caught already")
        assertNull(seeking.copy(me = seeking.me.copy(role = Role.SEEKER)).myCatchCode(0))
    }

    @Test
    fun theSeekersCameraTakesTheCodesOfHidersStillPlaying() {
        val anna = PlayerView(PlayerId("anna"), "Anna", Role.HIDER, PlayerStatus.ACTIVE)
        val boris = PlayerView(PlayerId("boris"), "Boris", Role.HIDER, PlayerStatus.CAUGHT)
        val sam = PlayerView(PlayerId("sam"), "Sam", Role.SEEKER, PlayerStatus.ACTIVE)
        val seeker = testSnapshot(phase = GamePhase.SEEKING, players = listOf(anna, boris, sam))
        fun qr(player: PlayerId, game: GameId = testSession.gameId) = CatchCodePayload(game, player, "1234").encode()

        assertEquals(CatchCodePayload(testSession.gameId, anna.id, "1234"), seeker.catchableScan(qr(anna.id)))
        assertNull(seeker.catchableScan(qr(boris.id)), "caught already")
        assertNull(seeker.catchableScan(qr(sam.id)), "a seeker")
        assertNull(seeker.catchableScan(qr(PlayerId("nobody"))))
        assertNull(seeker.catchableScan(qr(anna.id, GameId("another game"))))
        assertNull(seeker.catchableScan("https://example.com"))
    }
}
