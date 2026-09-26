package app.hovanki.client.ui.common

import app.hovanki.client.network.ApiResult
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.error_network
import app.hovanki.client.resources.error_nickname_taken
import app.hovanki.client.resources.error_too_many_requests
import app.hovanki.client.resources.error_too_many_requests_wait
import app.hovanki.client.resources.error_wrong_code
import app.hovanki.client.resources.error_wrong_login
import app.hovanki.client.resources.error_wrong_password
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoticeTest {
    @Test
    fun successNeedsNoMessage() {
        assertNull(ApiResult.Success(Unit).notice())
    }

    @Test
    fun theReasonDecides() {
        val taken = ApiResult.Rejected(ErrorCode.WRONG_STATE, ErrorReason.NICKNAME_TAKEN, "Nickname taken")

        assertEquals(Notice.Text(Res.string.error_nickname_taken), taken.notice())
    }

    @Test
    fun aWrongPasswordReadsAsTheFormNeedsIt() {
        val wrong = ApiResult.Rejected(ErrorCode.FORBIDDEN, ErrorReason.WRONG_CREDENTIALS, "Wrong credentials")

        assertEquals(Notice.Text(Res.string.error_wrong_login), wrong.notice())
        assertEquals(Notice.Text(Res.string.error_wrong_password), wrong.notice(Res.string.error_wrong_password))
    }

    @Test
    fun aRateLimitSaysHowLongToWaitWhenTheServerDoes() {
        val limited = ApiResult.Rejected(ErrorCode.WRONG_STATE, ErrorReason.TOO_MANY_REQUESTS, "Slow down", 75)

        assertEquals(Notice.Text(Res.string.error_too_many_requests_wait, listOf("1:15")), limited.notice())
        assertEquals(Notice.Text(Res.string.error_too_many_requests), limited.copy(retryAfterSeconds = null).notice())
    }

    @Test
    fun aWrongEmailedCodeComesWithoutReason() {
        val wrong = ApiResult.Rejected(ErrorCode.INVALID_CODE, null, "Wrong code")

        assertEquals(Notice.Text(Res.string.error_wrong_code), wrong.notice())
    }

    @Test
    fun otherRefusalsShowTheServersWordsAndNetworkProblemsSaySo() {
        assertEquals(
            Notice.Raw("Game is full"),
            ApiResult.Rejected(ErrorCode.WRONG_STATE, null, "Game is full").notice(),
        )
        assertEquals(Notice.Text(Res.string.error_network), ApiResult.Network("timeout").notice())
    }
}
