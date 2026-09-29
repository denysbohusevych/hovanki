package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

/** Answer of [ApiRoutes.TIME]: the server's clock when it answered, epoch millis. */
@Serializable
data class ServerTimeResponse(val serverTimeMillis: Long)
