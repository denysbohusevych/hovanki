package app.hovanki.server.game

import app.hovanki.shared.protocol.ErrorCode

/** Expected, client-visible failure. Mapped to [app.hovanki.shared.protocol.ApiError] by the API layer. */
class GameException(val code: ErrorCode, message: String) : RuntimeException(message)
