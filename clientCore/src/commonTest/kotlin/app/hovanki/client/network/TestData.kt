package app.hovanki.client.network

import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.ChatChannel
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.InviteRequest
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.MyState
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.SendChatRequest
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.SyncRequest
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
    private val onSync: suspend (SyncRequest) -> GameSnapshot,
) : GameApi {
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

    override suspend fun claimCatch(session: PlayerSession, hiderId: PlayerId): GameSnapshot = unused()

    override suspend fun confirmCatch(session: PlayerSession, catchId: CatchId, code: String): GameSnapshot = unused()

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
