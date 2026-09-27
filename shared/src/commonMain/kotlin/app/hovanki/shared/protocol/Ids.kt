package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

// Typed ids: serialized as plain JSON strings, but can't be mixed up in code.

@Serializable
@JvmInline
value class GameId(val value: String)

@Serializable
@JvmInline
value class PlayerId(val value: String)

@Serializable
@JvmInline
value class CatchId(val value: String)

/** A registered account (docs/adr/0004-accounts-friends-chat.md); guests have none. */
@Serializable
@JvmInline
value class UserId(val value: String)

/** A group of friends ("компания"). */
@Serializable
@JvmInline
value class GroupId(val value: String)

/** An invitation into a game, shown in the invitee's inbox. */
@Serializable
@JvmInline
value class InviteId(val value: String)
