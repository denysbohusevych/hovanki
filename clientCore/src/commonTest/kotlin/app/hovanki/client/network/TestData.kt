package app.hovanki.client.network

import app.hovanki.shared.protocol.BigGameId
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.ChatChannel
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.CustomQuestRequest
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.InviteRequest
import app.hovanki.shared.protocol.ItemId
import app.hovanki.shared.protocol.JoinBigGameRequest
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.MyState
import app.hovanki.shared.protocol.PauseRequest
import app.hovanki.shared.protocol.PlaceItemRequest
import app.hovanki.shared.protocol.PlayAgainRequest
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.QuestId
import app.hovanki.shared.protocol.QuestReviewRequest
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.RolesRequest
import app.hovanki.shared.protocol.SendChatRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.SettingsPreviewRequest
import app.hovanki.shared.protocol.SettingsPreviewResponse
import app.hovanki.shared.protocol.SettingsRequest
import app.hovanki.shared.protocol.SosRequest
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.StreetZoneResponse
import app.hovanki.shared.protocol.SyncRequest
import app.hovanki.shared.protocol.TracksResponse
import app.hovanki.shared.protocol.UsePerkRequest
import app.hovanki.shared.rules.shrinkingZone

val testSession = PlayerSession(GameId("game1"), PlayerId("player1"), token = "secret-token")

fun testSample(timestampMillis: Long) = LocationSample(
    point = GeoPoint(50.4501, 30.5234),
    accuracyMeters = 5.0,
    timestampMillis = timestampMillis,
)

/** Anna, the player of [testSession]. */
val testPlayer = PlayerView(testSession.playerId, "Anna", Role.HIDER, PlayerStatus.ACTIVE)

fun testSnapshot(
    serverTimeMillis: Long = 1_000L,
    syncIntervalSeconds: Int = 3,
    phase: GamePhase = GamePhase.LOBBY,
    buildings: BuildingsState? = null,
    players: List<PlayerView> = listOf(testPlayer),
    chat: List<ChatMessage> = emptyList(),
) = GameSnapshot(
    gameId = testSession.gameId,
    joinCode = "ABC234",
    hostId = testSession.playerId,
    phase = phase,
    settings = GameSettings(
        zone = shrinkingZone(GeoPoint(50.4501, 30.5234)),
        rules = GameRules(syncIntervalSeconds = syncIntervalSeconds),
    ),
    serverTimeMillis = serverTimeMillis,
    players = players,
    me = MyState(testSession.playerId, Role.HIDER, PlayerStatus.ACTIVE),
    buildings = buildings,
    chat = chat,
)

/** A chat message [seq] from [from] (by default the viewer, [testSession]'s player). */
fun testMessage(seq: Long, from: PlayerId = testSession.playerId, channel: ChatChannel = ChatChannel.ALL) =
    ChatMessage(seq, from, "message $seq", sentAtMillis = 1_000L + seq, channel)

/**
 * [GameApi] scripted by the test: `sync` always, the other calls when a test gives them an answer (unused ones fail).
 * Records what was sent.
 */
class FakeGameApi(
    private val onJoin: suspend (JoinGameRequest) -> SessionResponse = { unused() },
    private val onBuildings: suspend () -> BuildingsResponse = { unused() },
    private val onCreate: suspend (CreateGameRequest) -> SessionResponse = { unused() },
    private val onSendChat: suspend (SendChatRequest) -> GameSnapshot = { unused() },
    private val onReportChat: suspend (Long) -> GameSnapshot = { unused() },
    private val onInvite: suspend (InviteRequest) -> GameSnapshot = { unused() },
    private val onClaim: suspend (PlayerId, String?) -> GameSnapshot = { _, _ -> unused() },
    private val onConfirm: suspend (CatchId, String) -> GameSnapshot = { _, _ -> unused() },
    private val onTracks: suspend () -> TracksResponse = { unused() },
    private val onRoles: suspend (RolesRequest) -> GameSnapshot = { unused() },
    private val onSettings: suspend (SettingsRequest) -> GameSnapshot = { unused() },
    private val onPreview: suspend (SettingsPreviewRequest) -> SettingsPreviewResponse = { unused() },
    private val onStreetZone: suspend () -> StreetZoneResponse = { unused() },
    private val onLeave: suspend () -> Unit = {},
    private val onPlayAgain: suspend (PlayerSession, PlayAgainRequest) -> SessionResponse = { _, _ -> unused() },
    /** Every call of the board and the perks (items, checkpoints, perks, quests) answers with this. */
    private val onBoard: suspend () -> GameSnapshot = { unused() },
    private val onAcceptCrowding: suspend () -> GameSnapshot = { unused() },
    private val onJoinBigGame: suspend (BigGameId, JoinBigGameRequest) -> SessionResponse = { _, _ -> unused() },
    private val onServerTime: suspend () -> Long = { unused() },
    private val onSync: suspend (SyncRequest) -> GameSnapshot,
) : GameApi {
    val placedItems = mutableListOf<PlaceItemRequest>()
    val removedItems = mutableListOf<ItemId>()
    val scannedCodes = mutableListOf<String>()
    val perksUsed = mutableListOf<UsePerkRequest>()
    val questsAdded = mutableListOf<CustomQuestRequest>()
    val questsDone = mutableListOf<QuestId>()
    val questReviews = mutableListOf<Pair<QuestId, QuestReviewRequest>>()

    override suspend fun serverTime(): Long = onServerTime()

    override suspend fun placeItem(session: PlayerSession, request: PlaceItemRequest): GameSnapshot {
        placedItems += request
        return onBoard()
    }

    override suspend fun removeItem(session: PlayerSession, itemId: ItemId): GameSnapshot {
        removedItems += itemId
        return onBoard()
    }

    override suspend fun scanCheckpoint(session: PlayerSession, code: String): GameSnapshot {
        scannedCodes += code
        return onBoard()
    }

    override suspend fun usePerk(session: PlayerSession, request: UsePerkRequest): GameSnapshot {
        perksUsed += request
        return onBoard()
    }

    val pauses = mutableListOf<PauseRequest>()
    val sosRequests = mutableListOf<SosRequest>()

    override suspend fun setPaused(session: PlayerSession, request: PauseRequest): GameSnapshot {
        pauses += request
        return onBoard()
    }

    override suspend fun sos(session: PlayerSession, request: SosRequest): GameSnapshot {
        sosRequests += request
        return onBoard()
    }

    override suspend fun addQuest(session: PlayerSession, request: CustomQuestRequest): GameSnapshot {
        questsAdded += request
        return onBoard()
    }

    override suspend fun questDone(session: PlayerSession, questId: QuestId): GameSnapshot {
        questsDone += questId
        return onBoard()
    }

    override suspend fun reviewQuest(
        session: PlayerSession,
        questId: QuestId,
        request: QuestReviewRequest,
    ): GameSnapshot {
        questReviews += questId to request
        return onBoard()
    }

    /** Every big-game join as (big game, request, account token). */
    val bigGameJoins = mutableListOf<Triple<BigGameId, JoinBigGameRequest, String>>()

    override suspend fun joinBigGame(
        id: BigGameId,
        request: JoinBigGameRequest,
        accountToken: String,
    ): SessionResponse {
        bigGameJoins += Triple(id, request, accountToken)
        return onJoinBigGame(id, request)
    }

    var crowdingAccepts = 0

    override suspend fun acceptCrowding(session: PlayerSession): GameSnapshot {
        crowdingAccepts++
        return onAcceptCrowding()
    }

    val rolesRequests = mutableListOf<RolesRequest>()
    val settingsRequests = mutableListOf<SettingsRequest>()
    var streetZoneRequests = 0

    /** Sessions the app left the game with. */
    val leaves = mutableListOf<PlayerSession>()

    override suspend fun setRoles(session: PlayerSession, request: RolesRequest): GameSnapshot {
        rolesRequests += request
        return onRoles(request)
    }

    override suspend fun updateSettings(session: PlayerSession, request: SettingsRequest): GameSnapshot {
        settingsRequests += request
        return onSettings(request)
    }

    val previewRequests = mutableListOf<SettingsPreviewRequest>()

    override suspend fun previewSettings(
        session: PlayerSession,
        request: SettingsPreviewRequest,
    ): SettingsPreviewResponse {
        previewRequests += request
        return onPreview(request)
    }

    override suspend fun leave(session: PlayerSession) {
        leaves += session
        onLeave()
    }

    /** Every «Play again» as the session it came from and the request. */
    val playAgains = mutableListOf<Pair<PlayerSession, PlayAgainRequest>>()

    override suspend fun playAgain(session: PlayerSession, request: PlayAgainRequest): SessionResponse {
        playAgains += session to request
        return onPlayAgain(session, request)
    }

    override suspend fun streetZone(session: PlayerSession): StreetZoneResponse {
        streetZoneRequests++
        return onStreetZone()
    }

    var buildingsRequests = 0

    val syncRequests = mutableListOf<SyncRequest>()

    /** Sessions the syncs were sent with (the token goes into the Authorization header). */
    val syncSessions = mutableListOf<PlayerSession>()

    /** The account token of every create and join, in order (null: as a guest). */
    val accountTokens = mutableListOf<String?>()

    val chatRequests = mutableListOf<SendChatRequest>()

    val reportedSeqs = mutableListOf<Long>()

    val inviteRequests = mutableListOf<InviteRequest>()

    override suspend fun sync(session: PlayerSession, request: SyncRequest): GameSnapshot {
        syncRequests += request
        syncSessions += session
        return onSync(request)
    }

    override suspend fun createGame(request: CreateGameRequest, accountToken: String?): SessionResponse {
        accountTokens += accountToken
        return onCreate(request)
    }

    override suspend fun joinGame(request: JoinGameRequest, accountToken: String?): SessionResponse {
        accountTokens += accountToken
        return onJoin(request)
    }

    override suspend fun startGame(session: PlayerSession, request: StartGameRequest): GameSnapshot = unused()

    /** Every claim as (hider, code). */
    val claims = mutableListOf<Pair<PlayerId, String?>>()

    /** Every code typed (or sent after a scan) as (claim, code). */
    val confirms = mutableListOf<Pair<CatchId, String>>()

    var tracksRequests = 0

    override suspend fun claimCatch(session: PlayerSession, hiderId: PlayerId, code: String?): GameSnapshot {
        claims += hiderId to code
        return onClaim(hiderId, code)
    }

    override suspend fun confirmCatch(session: PlayerSession, catchId: CatchId, code: String): GameSnapshot {
        confirms += catchId to code
        return onConfirm(catchId, code)
    }

    override suspend fun tracks(session: PlayerSession): TracksResponse {
        tracksRequests++
        return onTracks()
    }

    override suspend fun disputeCatch(session: PlayerSession, catchId: CatchId): GameSnapshot = unused()

    override suspend fun vote(session: PlayerSession, catchId: CatchId, confirm: Boolean): GameSnapshot = unused()

    override suspend fun buildings(session: PlayerSession): BuildingsResponse {
        buildingsRequests++
        return onBuildings()
    }

    override suspend fun sendChat(session: PlayerSession, request: SendChatRequest): GameSnapshot {
        chatRequests += request
        return onSendChat(request)
    }

    override suspend fun reportChat(session: PlayerSession, seq: Long): GameSnapshot {
        reportedSeqs += seq
        return onReportChat(seq)
    }

    override suspend fun invite(session: PlayerSession, request: InviteRequest): GameSnapshot {
        inviteRequests += request
        return onInvite(request)
    }
}

private fun unused(): Nothing = error("Not scripted by the test")
