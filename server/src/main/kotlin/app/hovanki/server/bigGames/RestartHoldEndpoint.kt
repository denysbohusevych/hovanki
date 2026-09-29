package app.hovanki.server.bigGames

import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.stereotype.Component

/**
 * `GET /actuator/restarthold`: whether restarting the server now would break a big game, and until when
 * (docs/adr/0010-big-games.md). deploy/hovanki-update.sh asks before an update and waits. Public like
 * `/actuator/health`: only a time, which the list of big games shows anyway.
 */
@Component
@Endpoint(id = "restarthold")
class RestartHoldEndpoint(private val bigGames: BigGameService) {
    @ReadOperation
    fun hold(): Map<String, Any> {
        val until = bigGames.restartHolds().values.maxOrNull() ?: return mapOf("held" to false)
        return mapOf("held" to true, "until" to until.toString())
    }
}
