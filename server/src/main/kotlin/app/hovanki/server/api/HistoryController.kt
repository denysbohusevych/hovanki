package app.hovanki.server.api

import app.hovanki.server.history.HistoryService
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.GameHistoryResponse
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameRoute
import app.hovanki.shared.protocol.PlayerStats
import app.hovanki.shared.protocol.PrivacyRequest
import app.hovanki.shared.protocol.UserProfile
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * The caller's own history, statistics and saved routes, and the consent to keep routes
 * (docs/adr/0007-game-history-and-routes.md); all logic lives in [HistoryService]. Account token only.
 */
@RestController
class HistoryController(private val history: HistoryService) {
    @PostMapping(ApiRoutes.ME_PRIVACY)
    fun setPrivacy(user: AuthenticatedUser, @RequestBody request: PrivacyRequest): UserProfile =
        history.setPrivacy(user, request)

    @GetMapping(ApiRoutes.ME_STATS)
    fun stats(user: AuthenticatedUser): PlayerStats = history.stats(user)

    @GetMapping(ApiRoutes.ME_GAMES)
    fun games(user: AuthenticatedUser, @RequestParam(required = false) before: Long?): GameHistoryResponse =
        history.games(user, before)

    @GetMapping(ApiRoutes.ME_GAME_ROUTE)
    fun route(user: AuthenticatedUser, @PathVariable gameId: String): GameRoute = history.route(user, GameId(gameId))

    @PostMapping(ApiRoutes.ME_GAME_ROUTE_DELETE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteRoute(user: AuthenticatedUser, @PathVariable gameId: String) = history.deleteRoute(user, GameId(gameId))
}
