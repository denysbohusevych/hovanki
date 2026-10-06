package app.hovanki.server.api

import app.hovanki.server.leaderboard.LeaderboardService
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.LeaderboardResponse
import app.hovanki.shared.protocol.LeaderboardScope
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** The leaderboard (docs/adr/0020-leaderboard.md); all logic lives in [LeaderboardService]. Account token only. */
@RestController
class LeaderboardController(private val leaderboard: LeaderboardService) {
    @GetMapping(ApiRoutes.ME_LEADERBOARD)
    fun leaderboard(
        user: AuthenticatedUser,
        @RequestParam(required = false) scope: LeaderboardScope?,
    ): LeaderboardResponse = leaderboard.leaderboard(user, scope ?: LeaderboardScope.WORLD)
}
