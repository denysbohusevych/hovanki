package app.hovanki.client.ui.lobby

import app.hovanki.client.account.AccountState
import app.hovanki.client.session.SessionState
import app.hovanki.client.ui.settings.ChangedPart
import app.hovanki.client.ui.settings.Explainer
import app.hovanki.client.ui.settings.SettingsTab
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.BuildingArea
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.GameFeatures
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ItemKind
import app.hovanki.shared.protocol.MyState
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.rules.BoardRules
import app.hovanki.shared.rules.GameSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LobbyStateTest {
    private val gameId = GameId("g1")
    private val host = PlayerId("p-host")
    private val guest = PlayerId("p-guest")
    private val center = GeoPoint(50.4501, 30.5234)
    private val setup = GameSetup()

    private fun snapshot(
        me: PlayerId = host,
        features: GameFeatures = GameFeatures(),
        buildings: BuildingsState? = null,
    ) = GameSnapshot(
        gameId = gameId,
        joinCode = "ABCD",
        hostId = host,
        phase = GamePhase.LOBBY,
        settings = setup.copy(features = features).settings(center),
        serverTimeMillis = 1_000L,
        players = listOf(player(host, Role.SEEKER), player(guest, Role.HIDER)),
        me = MyState(me, Role.HIDER, PlayerStatus.ACTIVE),
        buildings = buildings,
    )

    private fun player(id: PlayerId, role: Role) = PlayerView(id, id.value, role, PlayerStatus.ACTIVE)

    private fun build(
        snapshot: GameSnapshot? = snapshot(),
        local: LobbyLocal = LobbyLocal(),
        buildings: BuildingsResponse? = null,
        origin: GeoPoint? = null,
    ): LobbyUiState? = LobbyStateBuilder().build(
        LobbyInputs(
            session = SessionState(snapshot = snapshot, buildings = buildings),
            account = AccountState(isRestored = true),
        ),
        local,
        zoneOrigin = { origin },
    )

    private val allOpen = LobbyLocal(
        invite = InviteDraft(gameId),
        invitesSentIn = gameId,
        settings = SettingsDraft(gameId, setup),
        buildings = BuildingsDraft(gameId),
        mapIn = gameId,
        boardIn = gameId,
    )

    @Test
    fun noGameNoLobby() {
        assertNull(build(snapshot = null))
    }

    @Test
    fun panelsOpenForAnotherGameStayClosed() {
        val other = GameId("g0")
        val state = assertNotNull(
            build(
                local = LobbyLocal(
                    settings = SettingsDraft(other, setup),
                    buildings = BuildingsDraft(other),
                    mapIn = other,
                    boardIn = other,
                    invitesSentIn = other,
                ),
            ),
        )

        assertNull(state.settings)
        assertNull(state.buildingPicker)
        assertNull(state.board)
        assertFalse(state.isMapOpen)
        assertFalse(state.invitesSent)
    }

    @Test
    fun theHostsPanelsAreTheHostsOnly() {
        val state = assertNotNull(build(snapshot = snapshot(me = guest), local = allOpen))

        assertNull(state.settings)
        assertNull(state.buildingPicker)
        assertNull(state.board)
        assertTrue(state.isMapOpen)
        assertTrue(state.invitesSent)
        // A guest without an account can't invite anybody.
        assertNull(state.invite)
    }

    @Test
    fun aPickNotYetAnsweredShowsAtOnce() {
        val state = assertNotNull(build(local = LobbyLocal(pendingSeekers = setOf(guest))))

        assertEquals(listOf(false, true), state.players.map { it.isSeeker })
        assertFalse(state.amSeeker)
        assertTrue(state.canStart)
        assertFalse(assertNotNull(build(local = LobbyLocal(pendingSeekers = setOf(host, guest)))).canStart)
    }

    @Test
    fun theSettingsShowTheDraftAndWhatChanged() {
        val moved = center.moveBy(300.0, 0.0)
        val draft = SettingsDraft(gameId, setup.copy(hidingMinutes = 8), center = moved, tab = SettingsTab.TIME)
        val settings = assertNotNull(build(local = LobbyLocal(settings = draft)).let { it?.settings })

        assertEquals(listOf(ChangedPart.PLACE, ChangedPart.TIME), settings.changedParts)
        assertEquals(moved, settings.draft.zone.initial.center)
        assertEquals(8 * 60, settings.draft.hidingSeconds)
        assertEquals(SettingsTab.TIME, settings.tab)
        // Where this phone first saw the zone; the zone's center while it never did.
        assertEquals(center, settings.origin)
        assertEquals(Explainer.OPEN_GAME, settings.focusedExtra)
    }

    @Test
    fun theBoardOffersWhatTheFeaturesAllow() {
        val features = GameFeatures(checkpoints = true, pickups = true)
        val local = LobbyLocal(boardIn = gameId, board = BoardDraft(kind = ItemKind.CHECKPOINT_GEO))
        val board = assertNotNull(build(snapshot = snapshot(features = features), local = local)?.board)

        assertEquals(listOf(ItemKind.CHECKPOINT_GEO, ItemKind.CHECKPOINT_SCAN, ItemKind.PICKUP), board.kinds)
        assertEquals(BoardRules.defaultSparks(ItemKind.CHECKPOINT_GEO), board.sparks)
        val picked =
            build(snapshot = snapshot(features = features), local = local.copy(board = local.board.copy(sparks = 2)))
        assertEquals(2, picked?.board?.sparks)
    }

    @Test
    fun theBuildingPickerShowsTheBuildingTappedAndWhetherItIsOpen() {
        val house = square(center, 20.0)
        val shed = square(center.moveBy(200.0, 0.0), 10.0)
        val buildings = BuildingsResponse(state = BuildingsState.READY, buildings = listOf(house, shed))
        fun pickerAt(tap: GeoPoint?) = build(
            snapshot = snapshot(buildings = BuildingsState.READY),
            local = LobbyLocal(buildings = BuildingsDraft(gameId, tap = tap)),
            buildings = buildings,
        )?.buildingPicker

        assertNull(assertNotNull(pickerAt(null)).picked)
        val picker = assertNotNull(pickerAt(center))
        assertEquals(house, picker.picked)
        assertFalse(picker.isPickedOpen)
        assertNull(assertNotNull(pickerAt(center.moveBy(0.0, 500.0))).picked)
    }

    private fun square(at: GeoPoint, half: Double) = BuildingArea(
        listOf(
            at.moveBy(-half, -half),
            at.moveBy(half, -half),
            at.moveBy(half, half),
            at.moveBy(-half, half),
            at.moveBy(-half, -half),
        ),
    )
}
