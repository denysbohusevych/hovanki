package app.hovanki.server.api

import app.hovanki.server.game.GameService
import app.hovanki.server.game.PlayerRef
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.ClaimCatchRequest
import app.hovanki.shared.protocol.ConfirmCatchRequest
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.VoteRequest
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** Thin HTTP adapter: routes and DTOs come from `:shared`, all logic lives in [GameService]. */
@RestController
class GameController(private val games: GameService) {
    @PostMapping(ApiRoutes.GAMES)
    fun create(@RequestBody request: CreateGameRequest): SessionResponse = games.create(request)

    @PostMapping(ApiRoutes.JOIN)
    fun join(@RequestBody request: JoinGameRequest): SessionResponse = games.join(request)

    @PostMapping(ApiRoutes.START)
    fun start(player: PlayerRef, @PathVariable gameId: String, @RequestBody request: StartGameRequest): GameSnapshot =
        games.start(player, GameId(gameId), request)

    /** Called by every client every few seconds: sends new fixes, returns the fresh state. */
    @PostMapping(ApiRoutes.SYNC)
    fun sync(player: PlayerRef, @PathVariable gameId: String, @RequestBody request: SyncRequest): GameSnapshot =
        games.sync(player, GameId(gameId), request)

    @PostMapping(ApiRoutes.CATCHES)
    fun claimCatch(
        player: PlayerRef,
        @PathVariable gameId: String,
        @RequestBody request: ClaimCatchRequest,
    ): GameSnapshot = games.claimCatch(player, GameId(gameId), request)

    @PostMapping(ApiRoutes.CATCH_CONFIRM)
    fun confirmCatch(
        player: PlayerRef,
        @PathVariable gameId: String,
        @PathVariable catchId: String,
        @RequestBody request: ConfirmCatchRequest,
    ): GameSnapshot = games.confirmCatch(player, GameId(gameId), CatchId(catchId), request)

    @PostMapping(ApiRoutes.CATCH_DISPUTE)
    fun disputeCatch(player: PlayerRef, @PathVariable gameId: String, @PathVariable catchId: String): GameSnapshot =
        games.disputeCatch(player, GameId(gameId), CatchId(catchId))

    @PostMapping(ApiRoutes.CATCH_VOTE)
    fun vote(
        player: PlayerRef,
        @PathVariable gameId: String,
        @PathVariable catchId: String,
        @RequestBody request: VoteRequest,
    ): GameSnapshot = games.vote(player, GameId(gameId), CatchId(catchId), request)
}
