package app.hovanki.server.api

import app.hovanki.server.game.GameService
import app.hovanki.server.game.PlayerRef
import app.hovanki.server.social.InviteService
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.ClaimCatchRequest
import app.hovanki.shared.protocol.ConfirmCatchRequest
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.CustomQuestRequest
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.InviteRequest
import app.hovanki.shared.protocol.ItemId
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.PlaceItemRequest
import app.hovanki.shared.protocol.QuestId
import app.hovanki.shared.protocol.QuestReviewRequest
import app.hovanki.shared.protocol.RolesRequest
import app.hovanki.shared.protocol.ScanCheckpointRequest
import app.hovanki.shared.protocol.SendChatRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.SettingsRequest
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.StreetZoneResponse
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.TracksResponse
import app.hovanki.shared.protocol.UsePerkRequest
import app.hovanki.shared.protocol.VoteRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Thin HTTP adapter: routes and DTOs come from `:shared`, all logic lives in [GameService] (invitations:
 * [InviteService]).
 */
@RestController
class GameController(private val games: GameService, private val invites: InviteService) {
    // Create and join take an optional account token: without one, the caller plays as a guest (and as before).

    @PostMapping(ApiRoutes.GAMES)
    fun create(user: AuthenticatedUser?, @RequestBody request: CreateGameRequest): SessionResponse =
        games.create(request, user)

    @PostMapping(ApiRoutes.JOIN)
    fun join(user: AuthenticatedUser?, @RequestBody request: JoinGameRequest): SessionResponse =
        games.join(request, user)

    @PostMapping(ApiRoutes.START)
    fun start(player: PlayerRef, @PathVariable gameId: String, @RequestBody request: StartGameRequest): GameSnapshot =
        games.start(player, GameId(gameId), request)

    /** The host picks or draws the roles in the lobby. */
    @PostMapping(ApiRoutes.ROLES)
    fun roles(player: PlayerRef, @PathVariable gameId: String, @RequestBody request: RolesRequest): GameSnapshot =
        games.setRoles(player, GameId(gameId), request)

    /** The host changes the setup in the lobby. */
    @PostMapping(ApiRoutes.SETTINGS)
    fun settings(player: PlayerRef, @PathVariable gameId: String, @RequestBody request: SettingsRequest): GameSnapshot =
        games.updateSettings(player, GameId(gameId), request)

    /** The player leaves the game for good; the token stops working. */
    @PostMapping(ApiRoutes.LEAVE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun leave(player: PlayerRef, @PathVariable gameId: String) = games.leave(player, GameId(gameId))

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

    /** Once per game, when the snapshot says the buildings are READY: what the map shows as forbidden. */
    @GetMapping(ApiRoutes.BUILDINGS)
    fun buildings(player: PlayerRef, @PathVariable gameId: String): BuildingsResponse =
        games.buildings(player, GameId(gameId))

    /** Once per map revision of a zone by streets, when the snapshot says it is READY: what the map draws. */
    @GetMapping(ApiRoutes.STREET_ZONE)
    fun streetZone(player: PlayerRef, @PathVariable gameId: String): StreetZoneResponse =
        games.streetZone(player, GameId(gameId))

    /** Once, when the game is over: where everybody went, for the replay on the results screen. */
    @GetMapping(ApiRoutes.TRACKS)
    fun tracks(player: PlayerRef, @PathVariable gameId: String): TracksResponse = games.tracks(player, GameId(gameId))

    @PostMapping(ApiRoutes.CATCH_VOTE)
    fun vote(
        player: PlayerRef,
        @PathVariable gameId: String,
        @PathVariable catchId: String,
        @RequestBody request: VoteRequest,
    ): GameSnapshot = games.vote(player, GameId(gameId), CatchId(catchId), request)

    /** The response brings the new messages after the request's chat cursor, this one included. */
    @PostMapping(ApiRoutes.CHAT)
    fun sendChat(player: PlayerRef, @PathVariable gameId: String, @RequestBody request: SendChatRequest): GameSnapshot =
        games.sendChat(player, GameId(gameId), request)

    /** Reports chat message [seq] to the moderators; a seq that is not a number is a 400. */
    @PostMapping(ApiRoutes.CHAT_REPORT)
    fun reportChat(player: PlayerRef, @PathVariable gameId: String, @PathVariable seq: Long): GameSnapshot =
        games.reportChat(player, GameId(gameId), seq)

    /** Invites friends or a group into the game: logged-in players, in the lobby. */
    @PostMapping(ApiRoutes.GAME_INVITES)
    fun invite(player: PlayerRef, @PathVariable gameId: String, @RequestBody request: InviteRequest): GameSnapshot =
        invites.invite(player, GameId(gameId), request)

    // The board and the perks (docs/adr/0013-quests-sparks-and-sensors.md).

    /** The host places an item on the map, in the lobby; the snapshot has it (with the code of a scan checkpoint). */
    @PostMapping(ApiRoutes.ITEMS)
    fun placeItem(
        player: PlayerRef,
        @PathVariable gameId: String,
        @RequestBody request: PlaceItemRequest,
    ): GameSnapshot = games.placeItem(player, GameId(gameId), request)

    @PostMapping(ApiRoutes.ITEM_REMOVE)
    fun removeItem(player: PlayerRef, @PathVariable gameId: String, @PathVariable itemId: String): GameSnapshot =
        games.removeItem(player, GameId(gameId), ItemId(itemId))

    /** The player scanned the QR code of a checkpoint. */
    @PostMapping(ApiRoutes.CHECKPOINT_SCAN)
    fun scanCheckpoint(
        player: PlayerRef,
        @PathVariable gameId: String,
        @RequestBody request: ScanCheckpointRequest,
    ): GameSnapshot = games.scanCheckpoint(player, GameId(gameId), request)

    /** The player uses a perk: one found on the map, else bought for sparks. */
    @PostMapping(ApiRoutes.PERKS)
    fun usePerk(player: PlayerRef, @PathVariable gameId: String, @RequestBody request: UsePerkRequest): GameSnapshot =
        games.usePerk(player, GameId(gameId), request)

    /** The host makes up a quest in words. */
    @PostMapping(ApiRoutes.QUESTS)
    fun addQuest(
        player: PlayerRef,
        @PathVariable gameId: String,
        @RequestBody request: CustomQuestRequest,
    ): GameSnapshot = games.addCustomQuest(player, GameId(gameId), request)

    /** The player says they did the host's quest; the host confirms it at [reviewQuest]. */
    @PostMapping(ApiRoutes.QUEST_DONE)
    fun questDone(player: PlayerRef, @PathVariable gameId: String, @PathVariable questId: String): GameSnapshot =
        games.markQuestDone(player, GameId(gameId), QuestId(questId))

    @PostMapping(ApiRoutes.QUEST_REVIEW)
    fun reviewQuest(
        player: PlayerRef,
        @PathVariable gameId: String,
        @PathVariable questId: String,
        @RequestBody request: QuestReviewRequest,
    ): GameSnapshot = games.reviewQuest(player, GameId(gameId), QuestId(questId), request)
}
