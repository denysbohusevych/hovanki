package app.hovanki.server.social

import app.hovanki.server.game.GameException
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.rules.GroupRules

/** The errors of friends, blocks, groups and invites (the error table of docs/adr/0004-accounts-friends-chat.md). */
object SocialErrors {
    fun badRequest(message: String) = GameException(ErrorCode.BAD_REQUEST, message)

    /** Also for accounts whose email is not confirmed: nobody can find them. */
    fun userNotFound() = GameException(ErrorCode.NOT_FOUND, "No such user", ErrorReason.USER_NOT_FOUND)

    fun notFound(message: String) = GameException(ErrorCode.NOT_FOUND, message)

    fun limitReached(message: String) = GameException(ErrorCode.WRONG_STATE, message, ErrorReason.LIMIT_REACHED)

    fun blockedByYou() =
        GameException(ErrorCode.WRONG_STATE, "You blocked this user: unblock them first", ErrorReason.BLOCKED_BY_YOU)

    fun notFriends(message: String = "Not your friend") =
        GameException(ErrorCode.FORBIDDEN, message, ErrorReason.NOT_FRIENDS)

    /** Also for groups the caller is not in: nobody learns which group ids exist. */
    fun groupNotFound() = GameException(ErrorCode.NOT_FOUND, "No such group")

    fun notGroupOwner() =
        GameException(ErrorCode.FORBIDDEN, "Only the group's owner can do this", ErrorReason.NOT_GROUP_OWNER)

    fun notGroupMember(message: String = "Not a member of the group") =
        GameException(ErrorCode.FORBIDDEN, message, ErrorReason.NOT_GROUP_MEMBER)

    fun invalidGroupName() = GameException(
        ErrorCode.BAD_REQUEST,
        "Group name: 1..${GroupRules.NAME_MAX_LENGTH} characters",
        ErrorReason.INVALID_GROUP_NAME,
    )
}
