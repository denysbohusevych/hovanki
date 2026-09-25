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
