package app.hovanki.client.network

import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.MyState
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
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

fun testSnapshot(serverTimeMillis: Long = 1_000L, syncIntervalSeconds: Int = 3) = GameSnapshot(
    gameId = testSession.gameId,
    joinCode = "ABC234",
    hostId = testSession.playerId,
    phase = GamePhase.LOBBY,
    settings = GameSettings(
        zone = shrinkingZone(GeoPoint(50.4501, 30.5234)),
        rules = GameRules(syncIntervalSeconds = syncIntervalSeconds),
    ),
    serverTimeMillis = serverTimeMillis,
    players = listOf(PlayerView(testSession.playerId, "Anna", Role.HIDER, PlayerStatus.ACTIVE)),
    me = MyState(testSession.playerId, Role.HIDER, PlayerStatus.ACTIVE),
)

/** [GameApi] whose `sync` is scripted by the test; everything else is unused by the connection. */
class FakeGameApi(private val onSync: suspend (SyncRequest) -> GameSnapshot) : GameApi {
    val syncRequests = mutableListOf<SyncRequest>()

    override suspend fun sync(session: PlayerSession, request: SyncRequest): GameSnapshot {
        syncRequests += request
        return onSync(request)
    }

    override suspend fun createGame(request: CreateGameRequest): SessionResponse = unused()

    override suspend fun joinGame(request: JoinGameRequest): SessionResponse = unused()

    override suspend fun startGame(session: PlayerSession, request: StartGameRequest): GameSnapshot = unused()

    override suspend fun claimCatch(session: PlayerSession, hiderId: PlayerId): GameSnapshot = unused()

    override suspend fun confirmCatch(session: PlayerSession, catchId: CatchId, code: String): GameSnapshot = unused()

    override suspend fun disputeCatch(session: PlayerSession, catchId: CatchId): GameSnapshot = unused()

    override suspend fun vote(session: PlayerSession, catchId: CatchId, confirm: Boolean): GameSnapshot = unused()

    private fun unused(): Nothing = error("Not used by the connection")
}
