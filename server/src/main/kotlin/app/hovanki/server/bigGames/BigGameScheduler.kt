package app.hovanki.server.bigGames

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Opens the lobbies of the big games that are due, starts their rounds on time and notes their end
 * ([BigGameService.tick]), every `hovanki.big-games.tick`. One thread: ticks never overlap; an admin's action on the same
 * big game waits for its row lock.
 */
@Component
class BigGameScheduler(private val bigGames: BigGameService) {
    @Scheduled(fixedDelayString = "\${hovanki.big-games.tick:PT10S}")
    fun tick() = bigGames.tick()
}
