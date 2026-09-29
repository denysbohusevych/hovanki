package app.hovanki.client.ui.common

import androidx.compose.runtime.Composable
import app.hovanki.client.network.ApiResult
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.error_account_banned
import app.hovanki.client.resources.error_account_banned_forever
import app.hovanki.client.resources.error_account_required
import app.hovanki.client.resources.error_big_game_signup_required
import app.hovanki.client.resources.error_blocked_by_you
import app.hovanki.client.resources.error_chat_muted
import app.hovanki.client.resources.error_chat_muted_forever
import app.hovanki.client.resources.error_checkpoint_taken
import app.hovanki.client.resources.error_code_expired
import app.hovanki.client.resources.error_email_not_verified
import app.hovanki.client.resources.error_email_taken
import app.hovanki.client.resources.error_feature_disabled
import app.hovanki.client.resources.error_feature_missing
import app.hovanki.client.resources.error_game_not_open
import app.hovanki.client.resources.error_in_another_game
import app.hovanki.client.resources.error_invalid_email
import app.hovanki.client.resources.error_invalid_group_name
import app.hovanki.client.resources.error_invalid_message
import app.hovanki.client.resources.error_invalid_nickname
import app.hovanki.client.resources.error_invalid_password
import app.hovanki.client.resources.error_item_limit
import app.hovanki.client.resources.error_limit_reached
import app.hovanki.client.resources.error_network
import app.hovanki.client.resources.error_nickname_taken
import app.hovanki.client.resources.error_not_enough_sparks
import app.hovanki.client.resources.error_not_friends
import app.hovanki.client.resources.error_not_group_member
import app.hovanki.client.resources.error_not_group_owner
import app.hovanki.client.resources.error_not_nearby
import app.hovanki.client.resources.error_perk_unavailable
import app.hovanki.client.resources.error_playing_this_game
import app.hovanki.client.resources.error_quest_not_active
import app.hovanki.client.resources.error_session_expired
import app.hovanki.client.resources.error_too_many_requests
import app.hovanki.client.resources.error_too_many_requests_wait
import app.hovanki.client.resources.error_user_not_found
import app.hovanki.client.resources.error_wrong_code
import app.hovanki.client.resources.error_wrong_login
import app.hovanki.client.resources.error_zone_not_ready
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** A message for the player; view models keep these, the screen turns them into text ([text]). */
sealed interface Notice {
    /** One of the app's texts with its format arguments. */
    data class Text(val resource: StringResource, val args: List<Any> = emptyList()) : Notice

    /** Words the app has no text for: the server's own explanation of a refusal. */
    data class Raw(val text: String) : Notice
}

/** What a form tells the player after a command: what went wrong, or news such as "a new code is on its way". */
data class FormMessage(val notice: Notice, val isError: Boolean = true)

@Composable
fun Notice.text(): String = when (this) {
    is Notice.Text -> stringResource(resource, *args.toTypedArray())
    is Notice.Raw -> text
}

/**
 * What to tell the player about a failed account or social command; null when it succeeded. [wrongCredentials] is the
 * text for [ErrorReason.WRONG_CREDENTIALS]: a failed login, or a wrong current password (change password, delete).
 */
fun ApiResult<*>.notice(wrongCredentials: StringResource = Res.string.error_wrong_login): Notice? = when (this) {
    is ApiResult.Success -> null

    is ApiResult.Network -> Notice.Text(Res.string.error_network)

    is ApiResult.Rejected -> reason?.let { reasonNotice(it, retryAfterSeconds, wrongCredentials, untilMillis) }
        // A wrong emailed code is the only refusal without a reason the player can do something about.
        ?: if (code == ErrorCode.INVALID_CODE) Notice.Text(Res.string.error_wrong_code) else Notice.Raw(message)
}

/**
 * The exact cause of a refusal in the player's words (see [notice] for [wrongCredentials]). [untilMillis]: when a ban
 * or a chat ban ends (docs/adr/0008-admin.md), null forever.
 */
fun reasonNotice(
    reason: ErrorReason,
    retryAfterSeconds: Long? = null,
    wrongCredentials: StringResource = Res.string.error_wrong_login,
    untilMillis: Long? = null,
): Notice {
    val resource = when (reason) {
        ErrorReason.NICKNAME_TAKEN -> Res.string.error_nickname_taken

        ErrorReason.EMAIL_TAKEN -> Res.string.error_email_taken

        ErrorReason.INVALID_NICKNAME -> Res.string.error_invalid_nickname

        ErrorReason.INVALID_EMAIL -> Res.string.error_invalid_email

        ErrorReason.INVALID_PASSWORD -> Res.string.error_invalid_password

        ErrorReason.WRONG_CREDENTIALS -> wrongCredentials

        ErrorReason.CODE_EXPIRED -> Res.string.error_code_expired

        ErrorReason.SESSION_EXPIRED -> Res.string.error_session_expired

        ErrorReason.ACCOUNT_REQUIRED -> Res.string.error_account_required

        ErrorReason.USER_NOT_FOUND -> Res.string.error_user_not_found

        ErrorReason.NOT_FRIENDS -> Res.string.error_not_friends

        ErrorReason.BLOCKED_BY_YOU -> Res.string.error_blocked_by_you

        ErrorReason.NOT_GROUP_OWNER -> Res.string.error_not_group_owner

        ErrorReason.NOT_GROUP_MEMBER -> Res.string.error_not_group_member

        ErrorReason.INVALID_GROUP_NAME -> Res.string.error_invalid_group_name

        ErrorReason.LIMIT_REACHED -> Res.string.error_limit_reached

        ErrorReason.INVALID_MESSAGE -> Res.string.error_invalid_message

        ErrorReason.EMAIL_NOT_VERIFIED -> Res.string.error_email_not_verified

        ErrorReason.IN_ANOTHER_GAME -> Res.string.error_in_another_game

        ErrorReason.ZONE_NOT_READY -> Res.string.error_zone_not_ready

        ErrorReason.NOT_NEARBY -> Res.string.error_not_nearby

        ErrorReason.FEATURE_MISSING -> Res.string.error_feature_missing

        ErrorReason.FEATURE_DISABLED -> Res.string.error_feature_disabled

        ErrorReason.NOT_ENOUGH_SPARKS -> Res.string.error_not_enough_sparks

        ErrorReason.PERK_UNAVAILABLE -> Res.string.error_perk_unavailable

        ErrorReason.CHECKPOINT_TAKEN -> Res.string.error_checkpoint_taken

        ErrorReason.QUEST_NOT_ACTIVE -> Res.string.error_quest_not_active

        ErrorReason.ITEM_LIMIT -> Res.string.error_item_limit

        ErrorReason.BIG_GAME_SIGNUP_REQUIRED -> Res.string.error_big_game_signup_required

        ErrorReason.GAME_NOT_OPEN -> Res.string.error_game_not_open

        ErrorReason.PLAYING_THIS_GAME -> Res.string.error_playing_this_game

        ErrorReason.ACCOUNT_BANNED -> return untilMillis?.let {
            Notice.Text(Res.string.error_account_banned, listOf(formatDateTime(it)))
        } ?: Notice.Text(Res.string.error_account_banned_forever)

        ErrorReason.CHAT_MUTED -> return untilMillis?.let {
            Notice.Text(Res.string.error_chat_muted, listOf(formatDateTime(it)))
        } ?: Notice.Text(Res.string.error_chat_muted_forever)

        ErrorReason.TOO_MANY_REQUESTS -> {
            val seconds = retryAfterSeconds?.takeIf { it > 0 }
                ?: return Notice.Text(Res.string.error_too_many_requests)
            return Notice.Text(Res.string.error_too_many_requests_wait, listOf(formatCountdown(seconds * 1000)))
        }
    }
    return Notice.Text(resource)
}
