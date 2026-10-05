package app.hovanki.client.ui.results

import app.hovanki.client.account.AccountState
import app.hovanki.client.session.SessionState
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.MyState
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.StreetZoneResponse
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.rules.GameSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class ResultsStateTest {
    private val me = PlayerId("p-me")
    private val user = UserId("u-me")
    private val center = GeoPoint(50.4501, 30.5234)
    private val snapshot = GameSnapshot(
        gameId = GameId("g1"),
        joinCode = "ABCD",
        hostId = me,
        phase = GamePhase.FINISHED,
        settings = GameSetup().settings(center),
        serverTimeMillis = 1_000L,
        players = listOf(PlayerView(me, "Anna", Role.HIDER, PlayerStatus.ACTIVE, userId = user)),
        me = MyState(me, Role.HIDER, PlayerStatus.ACTIVE),
    )
    private val square = ZonePolygon(
        listOf(center, center.moveBy(0.0, 100.0), center.moveBy(100.0, 100.0), center.moveBy(100.0, 0.0)),
    )

    private fun streets(stages: Int = snapshot.settings.zone.stages.size + 1, polygon: ZonePolygon = square) =
        StreetZoneResponse(mapRevision = snapshot.mapRevision, stages = List(stages) { polygon })

    private fun account(saveRoutes: Boolean = true, id: UserId = user) = AccountState(
        user = UserProfile(id, "Anna", "anna@example.com", true, 0L, saveRoutes = saveRoutes),
        isRestored = true,
    )

    @Test
    fun streetZoneNeedsEveryStageOfTheGame() {
        assertNotNull(streetZoneOf(SessionState(snapshot = snapshot, streetZone = streets())))
        assertNull(streetZoneOf(SessionState(snapshot = snapshot, streetZone = streets(stages = 1))))
        val line = ZonePolygon(listOf(center, center.moveBy(0.0, 100.0)))
        assertNull(streetZoneOf(SessionState(snapshot = snapshot, streetZone = streets(polygon = line))))
        assertNull(streetZoneOf(SessionState(snapshot = snapshot)))
    }

    @Test
    fun streetZoneStaysTheSameAcrossPolls() {
        val builder = ResultsStateBuilder()
        val session = SessionState(snapshot = snapshot, streetZone = streets())
        val first = builder.build(session, null, AccountState(), null, false).streetZone
        val second = builder.build(session.copy(chatReadSeq = 3), null, AccountState(), null, false).streetZone
        assertNotNull(first)
        assertSame(first, second)
        assertNull(builder.build(session.copy(streetZone = null), null, AccountState(), null, false).streetZone)
    }

    @Test
    fun saveRoutesOnlyForTheAccountThatPlayed() {
        val session = SessionState(snapshot = snapshot)
        assertEquals(true, saveRoutes(session, account()))
        assertEquals(false, saveRoutes(session, account(saveRoutes = false)))
        assertNull(saveRoutes(session, account(id = UserId("u-other"))))
        assertNull(saveRoutes(session, AccountState(isRestored = true)))
    }
}
