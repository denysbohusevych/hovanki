package app.hovanki.server.api

import app.hovanki.server.achievements.AchievementService
import app.hovanki.shared.protocol.AchievementsResponse
import app.hovanki.shared.protocol.AchievementsSeenRequest
import app.hovanki.shared.protocol.ApiRoutes
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** The caller's achievements (docs/adr/0021-achievements.md); all logic lives in [AchievementService]. Account token only. */
@RestController
class AchievementController(private val achievements: AchievementService) {
    @GetMapping(ApiRoutes.ME_ACHIEVEMENTS)
    fun achievements(user: AuthenticatedUser): AchievementsResponse = achievements.achievements(user)

    @PostMapping(ApiRoutes.ME_ACHIEVEMENTS_SEEN)
    fun seen(user: AuthenticatedUser, @RequestBody request: AchievementsSeenRequest): AchievementsResponse =
        achievements.markSeen(user, request.upToMillis)
}
