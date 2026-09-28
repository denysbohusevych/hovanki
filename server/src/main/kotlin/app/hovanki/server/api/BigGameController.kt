package app.hovanki.server.api

import app.hovanki.server.bigGames.BigGameService
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.BigGameCard
import app.hovanki.shared.protocol.BigGameId
import app.hovanki.shared.protocol.BigGamesResponse
import app.hovanki.shared.protocol.JoinBigGameRequest
import app.hovanki.shared.protocol.SessionResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** Big games for players (docs/adr/0010-big-games.md), with the account token; the rules are in [BigGameService]. */
@RestController
class BigGameController(private val bigGames: BigGameService) {
    @GetMapping(ApiRoutes.BIG_GAMES)
    fun list(user: AuthenticatedUser): BigGamesResponse = bigGames.cards(user)

    @PostMapping(ApiRoutes.BIG_GAME_SIGNUP)
    fun signUp(user: AuthenticatedUser, @PathVariable bigGameId: String): BigGameCard =
        bigGames.signUp(user, BigGameId(bigGameId))

    @PostMapping(ApiRoutes.BIG_GAME_SIGNUP_CANCEL)
    fun cancelSignup(user: AuthenticatedUser, @PathVariable bigGameId: String): BigGameCard =
        bigGames.cancelSignup(user, BigGameId(bigGameId))

    @PostMapping(ApiRoutes.BIG_GAME_JOIN)
    fun join(
        user: AuthenticatedUser,
        @PathVariable bigGameId: String,
        @RequestBody request: JoinBigGameRequest,
    ): SessionResponse = bigGames.join(user, BigGameId(bigGameId), request)
}
