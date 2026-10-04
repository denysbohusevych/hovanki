package app.hovanki.client.ui.lobby

import app.hovanki.client.account.AccountState
import app.hovanki.client.session.ConnectionStatus
import app.hovanki.client.session.DraftZone
import app.hovanki.client.session.SessionError
import app.hovanki.client.session.SessionState
import app.hovanki.client.social.UserRelation
import app.hovanki.client.ui.common.FormMessage
import app.hovanki.client.ui.common.PlayerAccount
import app.hovanki.client.ui.common.playerAccount
import app.hovanki.client.ui.game.ZoneTimeline
import app.hovanki.client.ui.settings.ChangedPart
import app.hovanki.client.ui.settings.Explainer
import app.hovanki.client.ui.settings.SettingsChange
import app.hovanki.client.ui.settings.SettingsPreview
import app.hovanki.client.ui.settings.SettingsTab
import app.hovanki.client.ui.settings.changedParts
import app.hovanki.shared.protocol.Audience
import app.hovanki.shared.protocol.BigGameInfo
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.BoardItem
import app.hovanki.shared.protocol.BuildingArea
import app.hovanki.shared.protocol.BuildingsResponse
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.Capabilities
import app.hovanki.shared.protocol.CapacityState
import app.hovanki.shared.protocol.FriendsResponse
import app.hovanki.shared.protocol.GameFeatures
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.GroupId
import app.hovanki.shared.protocol.GroupsResponse
import app.hovanki.shared.protocol.ItemId
import app.hovanki.shared.protocol.ItemKind
import app.hovanki.shared.protocol.PerkKind
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.QuestKind
import app.hovanki.shared.protocol.QuestView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.StreetZoneState
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.protocol.ZoneShape
import app.hovanki.shared.rules.BoardRules
import app.hovanki.shared.rules.Capacity
import app.hovanki.shared.rules.GameSetup
import app.hovanki.shared.rules.Glow
import app.hovanki.shared.rules.StreetZone
import app.hovanki.shared.rules.contains
import app.hovanki.shared.rules.withOpenBuildings
import kotlin.math.roundToInt

/**
 * Everything the lobby shows, in one object (docs/architecture.md, «Состояние экрана»): the game as the server sent it,
 * this phone's Bluetooth, and the panels the player has open with what they are choosing there. A panel is non-null
 * only while it is open for this game and allowed to this player (the host's panels for the host only).
 */
data class LobbyUiState(
    val gameId: GameId,
    val joinCode: String,
    val players: List<LobbyPlayer>,
    val isHost: Boolean,
    /** The role the host gave this player so far: everybody sees the roles before the start. */
    val amSeeker: Boolean,
    val canStart: Boolean,
    val isStarting: Boolean,
    val connectionStatus: ConnectionStatus,
    val isSharingLocation: Boolean,
    val error: SessionError?,
    /** The zone's buildings: loading, ready ([buildingCount] once this phone has them) or unavailable. */
    val buildingsState: BuildingsState?,
    val buildingCount: Int?,
    /** The server could not load the zone's buildings: the game will run without that rule. */
    val isBuildingRuleOff: Boolean,
    /** The zone by streets is being built: the game can start once it is there (or given up on). */
    val isBuildingStreetZone: Boolean,
    /** No zone by streets could be built here: the game uses the circles. */
    val isStreetZoneOff: Boolean,
    /** Playing with an account: friends and groups can be invited. */
    val canInvite: Boolean,
    /** Accounts already in the game: no need to invite them. */
    val userIdsInGame: Set<UserId>,
    /** The settings, shown as chips: the zone at the start and its shape, the hiding and seeking times, the glow. */
    val zoneRadiusMeters: Int,
    val zoneShape: ZoneShape,
    val hidingMinutes: Int,
    val seekingMinutes: Int,
    /** Null: no glow in this game. */
    val glowEveryMinutes: Int?,
    /** When the host last drew the roles at random: every phone rolls the dice once for each new value. */
    val rolesDrawnAtMillis: Long?,
    /** The server features the operator has on: what the host may turn on (docs/adr/0012-nearby-radar.md). */
    val enabledFeatures: Set<ServerFeature>,
    /** What the host turned on for this game. */
    val features: GameFeatures,
    /** The catalog quests the host picked. */
    val questKinds: List<QuestKind>,
    /** The board as this player sees it (the host with the codes). */
    val items: List<BoardItem>,
    /** The host's own quests so far. */
    val quests: List<QuestView>,
    val zoneCenter: GeoPoint,
    /** The zone as the search starts (not started), for the maps of the lobby, the settings and the board. */
    val zone: ZoneTimeline,
    /** The zone by streets once this phone has it; null: circles, or not yet. */
    val streets: StreetZone? = null,
    /**
     * The zone's buildings once this phone has them, split into forbidden and open by the host's points of now
     * (docs/adr/0014-settings-lobby-redesign-open-buildings.md).
     */
    val buildings: BuildingsResponse? = null,
    /** This phone's Bluetooth, for the radar. */
    val bluetooth: BluetoothState,
    /** «The radar on my phone». */
    val radarEnabled: Boolean,
    /** About how many players the zone fits (docs/adr/0010-big-games.md); null until the server knows. */
    val capacity: Int? = null,
    /** The host's warning: too many players for the zone, or few places to hide; null: none (or played anyway). */
    val crowding: Crowding? = null,
    /**
     * A big game's lobby (docs/adr/0010-big-games.md): hosted by the server, it starts at [BigGameInfo.startsAtMillis];
     * no join code, no host, the list shows only [friendsHere].
     */
    val bigGame: BigGameInfo? = null,
    val friendsHere: List<LobbyPlayer> = emptyList(),
    /** Everybody in the lobby, also those a big game's [players] leaves out. */
    val playerCount: Int = players.size,
    /** Open to spectators (docs/adr/0011-spectators-and-recordings.md), [spectatorDelaySeconds] behind. */
    val openGame: Boolean = false,
    val spectatorDelaySeconds: Int = 0,
    /** How many watch right now. */
    val spectators: Int = 0,
    /** The invitations just went out: the lobby says so until dismissed. */
    val invitesSent: Boolean = false,
    /** A friend request from the lobby failed, or is on its way ([isBusy]). */
    val message: FormMessage? = null,
    val isBusy: Boolean = false,
    /** The zone full screen; everybody may open it. */
    val isMapOpen: Boolean = false,
    val invite: InvitePanelState? = null,
    val settings: SettingsPanelState? = null,
    /** The host's map of buildings, to open some for hiding (opened from the settings). */
    val buildingPicker: BuildingPickerState? = null,
    val board: BoardPanelState? = null,
)

/** Too many players for the zone ([isCrowded]: [players] where it fits [capacity]), or few places to hide. */
data class Crowding(val capacity: Int, val players: Int, val isCrowded: Boolean, val fewCovers: Boolean)

data class LobbyPlayer(
    val id: PlayerId,
    val name: String,
    val isMe: Boolean,
    val isHost: Boolean,
    val isSeeker: Boolean,
    /** The player's phone has not been heard from for a while (closed app, no network). */
    val isOffline: Boolean,
    val account: PlayerAccount,
    /** What the player's phone can do (the radar); null: it never said. */
    val capabilities: Capabilities?,
)

/** The invitations: friends and groups to pick ([friends] null until loaded), then one tap sends them. */
data class InvitePanelState(
    val friends: FriendsResponse?,
    val groups: GroupsResponse?,
    val pickedFriends: Set<UserId>,
    val pickedGroups: Set<GroupId>,
    val isSending: Boolean,
) {
    val canSend: Boolean get() = !isSending && (pickedFriends.isNotEmpty() || pickedGroups.isNotEmpty())
}

/** The host's game setup (docs/adr/0014-settings-lobby-redesign-open-buildings.md, section 2), sent on «Save». */
data class SettingsPanelState(
    /** What the host is choosing. */
    val setup: GameSetup,
    /** [setup] as the game's settings: around the draft's center, with the game's thresholds. */
    val draft: GameSettings,
    /** What differs from the game's setup so far. */
    val changedParts: List<ChangedPart>,
    /** Where this phone first saw the game's zone: the pin moves around it. */
    val origin: GeoPoint,
    val tab: SettingsTab,
    /** The «?» open over the settings; null: none. */
    val helpFor: Explainer?,
    /** The extra shown above the «More» tab. */
    val focusedExtra: Explainer,
    /** What plays on the settings' map; null: nothing, the draft stands still. */
    val preview: SettingsPreview?,
    /** The host moves the zone's center: the map pans under a pin. */
    val isMovingCenter: Boolean,
    /** «What changes», asked before a setup that touches the map is sent; null: not asked. */
    val pendingChanges: List<SettingsChange>?,
    val isSaving: Boolean,
    /** The draft's zone by streets, built by the server before «Save» (section 2.3); null: not asked. */
    val draftZone: DraftZone?,
)

/** The zone's buildings to open for hiding (docs/adr/0014-settings-lobby-redesign-open-buildings.md, section 4). */
data class BuildingPickerState(
    /** The building where the host tapped (the whole outline of adjoining houses); null: none picked. */
    val picked: BuildingArea?,
    /** [picked] is open for hiding already. */
    val isPickedOpen: Boolean,
    val isToggling: Boolean,
)

/** The host's board (docs/adr/0013-quests-sparks-and-sensors.md): what to place where, and the host's own quests. */
data class BoardPanelState(
    /** The kinds of items the game's features allow. */
    val kinds: List<ItemKind>,
    /** Where the host tapped on the map; null: not yet. */
    val pick: GeoPoint?,
    val kind: ItemKind,
    val audience: Audience,
    val name: String,
    /** The sparks the item gives: the host's choice, or the kind's default. */
    val sparks: Int,
    val perk: PerkKind,
    val isPlacing: Boolean,
    /** The host's own quest being written. */
    val questText: String,
    val questAudience: Audience,
    val isAddingQuest: Boolean,
)

/** What the player does in the lobby: everything goes through [LobbyViewModel.onEvent]. */
sealed interface LobbyEvent {
    data class ToggleSeeker(val playerId: PlayerId) : LobbyEvent

    data object DrawSeekers : LobbyEvent

    data object Start : LobbyEvent

    data object PlayAnyway : LobbyEvent

    data object Leave : LobbyEvent

    data class SetRadarEnabled(val enabled: Boolean) : LobbyEvent

    data object DismissError : LobbyEvent

    data object LocationPermissionGranted : LobbyEvent

    data class AddFriend(val userId: UserId) : LobbyEvent

    data object DismissMessage : LobbyEvent

    data object OpenMap : LobbyEvent

    data object CloseMap : LobbyEvent

    data object DismissInvitesSent : LobbyEvent

    /** The invitations panel. */
    sealed interface Invite : LobbyEvent {
        data object Open : Invite

        data object Close : Invite

        data class ToggleFriend(val userId: UserId) : Invite

        data class ToggleGroup(val groupId: GroupId) : Invite

        data object Send : Invite
    }

    /** The host's settings panel. */
    sealed interface Settings : LobbyEvent {
        data class Open(val tab: SettingsTab = SettingsTab.ZONE) : Settings

        data object Close : Settings

        data class Edit(val setup: GameSetup) : Settings

        data class PickTab(val tab: SettingsTab) : Settings

        data class ShowHelp(val explainer: Explainer) : Settings

        data object CloseHelp : Settings

        data class FocusExtra(val explainer: Explainer) : Settings

        /** Plays the preview on the settings' map, or stops it when it plays. */
        data class TogglePreview(val kind: SettingsPreview) : Settings

        data object StopPreview : Settings

        data object ToggleMovingCenter : Settings

        /** The map stopped under the pin. */
        data class MoveCenter(val point: GeoPoint) : Settings

        data object Save : Settings

        data object ConfirmSave : Settings

        data object CancelSave : Settings
    }

    /** The host's map of buildings. */
    sealed interface Buildings : LobbyEvent {
        data object Open : Buildings

        data object Close : Buildings

        /** The host tapped the map: a building there is picked, elsewhere nothing is. */
        data class Pick(val point: GeoPoint) : Buildings

        /** Opens the picked building for hiding, or closes it again. */
        data object Toggle : Buildings
    }

    /** The host's board. */
    sealed interface Board : LobbyEvent {
        data object Open : Board

        data object Close : Board

        data class PickPoint(val point: GeoPoint) : Board

        data class PickKind(val kind: ItemKind) : Board

        data class PickAudience(val audience: Audience) : Board

        data class EditName(val name: String) : Board

        data class EditSparks(val sparks: Int) : Board

        data class PickPerk(val perk: PerkKind) : Board

        /** Places what is picked where the host tapped. */
        data object Place : Board

        data class Remove(val itemId: ItemId) : Board

        data class EditQuestText(val text: String) : Board

        data class PickQuestAudience(val audience: Audience) : Board

        data object AddQuest : Board
    }
}

/** What the lobby's state is made of besides this phone's own choices: the game and the managers' flows. */
internal data class LobbyInputs(
    val session: SessionState,
    val friends: FriendsResponse? = null,
    val groups: GroupsResponse? = null,
    val account: AccountState = AccountState(),
    val bluetooth: BluetoothState = BluetoothState.UNSUPPORTED,
    val radarEnabled: Boolean = true,
    val draftZone: DraftZone? = null,
    val message: FormMessage? = null,
    val isBusy: Boolean = false,
)

/**
 * This phone's own part of the lobby: the roles the host just picked, the panels open and what is chosen in them. Panels
 * remember their game: the view model outlives the lobby (the round starts, the next game's lobby).
 */
internal data class LobbyLocal(
    /** The seekers the host just picked, shown until the server has answered: a tap shows at once. */
    val pendingSeekers: Set<PlayerId>? = null,
    val isStarting: Boolean = false,
    val invite: InviteDraft? = null,
    /** The game the last invitations went out for. */
    val invitesSentIn: GameId? = null,
    val settings: SettingsDraft? = null,
    /** Kept between openings of the settings. */
    val focusedExtra: Explainer = Explainer.OPEN_GAME,
    val buildings: BuildingsDraft? = null,
    val mapIn: GameId? = null,
    /** The game whose board is open; null: closed. [board] stays between openings. */
    val boardIn: GameId? = null,
    val board: BoardDraft = BoardDraft(),
)

internal data class InviteDraft(
    val gameId: GameId,
    val pickedFriends: Set<UserId> = emptySet(),
    val pickedGroups: Set<GroupId> = emptySet(),
    val isSending: Boolean = false,
)

internal data class SettingsDraft(
    val gameId: GameId,
    val setup: GameSetup,
    /** Where the host moved the zone on the settings' map; null: where it is. */
    val center: GeoPoint? = null,
    val tab: SettingsTab = SettingsTab.ZONE,
    val helpFor: Explainer? = null,
    val preview: SettingsPreview? = null,
    val isMovingCenter: Boolean = false,
    val pendingChanges: List<SettingsChange>? = null,
    val isSaving: Boolean = false,
) {
    /** The draft as the game's settings: around [center] (or the zone's center), with the game's thresholds. */
    fun settings(current: GameSettings): GameSettings =
        setup.coerced().settings(center ?: current.zone.initial.center, current.rules)

    fun changedParts(current: GameSettings): List<ChangedPart> {
        val saved = current.zone.initial.center
        return changedParts(GameSetup.of(current).coerced(), saved, setup.coerced(), center ?: saved)
    }
}

internal data class BuildingsDraft(
    val gameId: GameId,
    /** Where the host tapped on the map of buildings: the building there is the one they look at. */
    val tap: GeoPoint? = null,
    val isToggling: Boolean = false,
)

internal data class BoardDraft(
    val pick: GeoPoint? = null,
    val kind: ItemKind = ItemKind.QUEST_POINT,
    val audience: Audience = Audience.ALL,
    val name: String = "",
    /** Null: the kind's default. */
    val sparks: Int? = null,
    val perk: PerkKind = PerkKind.SENSE,
    val isPlacing: Boolean = false,
    val questText: String = "",
    val questAudience: Audience = Audience.ALL,
    val isAddingQuest: Boolean = false,
)

/** The kinds of items the game's features allow on the board. */
internal fun allowedKinds(features: GameFeatures): List<ItemKind> = buildList {
    if (features.quests) add(ItemKind.QUEST_POINT)
    if (features.checkpoints) {
        add(ItemKind.CHECKPOINT_GEO)
        add(ItemKind.CHECKPOINT_SCAN)
    }
    if (features.pickups) add(ItemKind.PICKUP)
}

/** Puts the lobby's state together; keeps the last zone by streets so that it stays the same object while unchanged. */
internal class LobbyStateBuilder {
    private var streetZoneCache: Pair<List<ZonePolygon>, StreetZone>? = null

    private fun streetZoneOf(stages: List<ZonePolygon>): StreetZone {
        streetZoneCache?.let { (cached, zone) -> if (cached == stages) return zone }
        return StreetZone(stages).also { streetZoneCache = stages to it }
    }

    /** Null: no game yet. [zoneOrigin]: where this phone first saw the game's zone. */
    fun build(inputs: LobbyInputs, local: LobbyLocal, zoneOrigin: (GameId) -> GeoPoint?): LobbyUiState? {
        val state = inputs.session
        val snapshot = state.snapshot ?: return null
        val me = snapshot.me.playerId
        val settings = snapshot.settings
        val isHost = snapshot.amHost
        val players = snapshot.players.map { player ->
            val silentFor = player.lastSeenMillis?.let { snapshot.serverTimeMillis - it }
            LobbyPlayer(
                id = player.id,
                name = player.name,
                isMe = player.id == me,
                isHost = player.id == snapshot.hostId,
                // A pick of an earlier game doesn't match any id here.
                isSeeker = local.pendingSeekers?.let { player.id in it } ?: (player.role == Role.SEEKER),
                isOffline = silentFor != null && silentFor > OFFLINE_AFTER_MILLIS,
                account = playerAccount(player, me, inputs.account, inputs.friends),
                capabilities = player.capabilities,
            )
        }
        val seekerCount = players.count { it.isSeeker }
        val capacity = snapshot.capacity?.takeIf { it.state == CapacityState.READY }
        val streetZone = snapshot.streetZone
        val buildings = state.buildings
            ?.takeIf { it.mapRevision == snapshot.mapRevision }
            ?.withOpenBuildings(settings.openBuildings)
            ?.takeIf { snapshot.buildings == BuildingsState.READY }
        val streets = state.streetZone
            ?.takeIf { it.mapRevision == snapshot.mapRevision && it.stages.size == settings.zone.stages.size + 1 }
            ?.takeIf { zone -> zone.stages.all { it.outline.size >= MIN_OUTLINE_POINTS } }
            ?.let { streetZoneOf(it.stages) }
        // The server takes invitations from players who play with an account (logged in on this phone).
        val canInvite = inputs.account.isLoggedIn && snapshot.players.any { it.id == me && it.userId != null }
        return LobbyUiState(
            gameId = snapshot.gameId,
            joinCode = snapshot.joinCode,
            players = players,
            isHost = isHost,
            amSeeker = players.firstOrNull { it.isMe }?.isSeeker == true,
            // At least one seeker and at least one hider, and the zone by streets built (or given up on).
            canStart = seekerCount in 1 until players.size && streetZone != StreetZoneState.LOADING,
            isStarting = local.isStarting,
            connectionStatus = state.connectionStatus,
            isSharingLocation = state.isSharingLocation,
            error = state.lastError,
            buildingsState = snapshot.buildings,
            buildingCount = buildings?.buildings?.size,
            isBuildingRuleOff = snapshot.buildings == BuildingsState.UNAVAILABLE,
            isBuildingStreetZone = streetZone == StreetZoneState.LOADING,
            isStreetZoneOff = streetZone == StreetZoneState.UNAVAILABLE,
            canInvite = canInvite,
            userIdsInGame = snapshot.players.mapNotNull { it.userId }.toSet(),
            zoneRadiusMeters = settings.zone.initial.radiusMeters.roundToInt(),
            zoneShape = settings.zoneShape,
            hidingMinutes = settings.hidingSeconds.minutesRoundedUp(),
            seekingMinutes = settings.seekingSeconds.minutesRoundedUp(),
            glowEveryMinutes = settings.glowEverySeconds.minutesRoundedUp().takeIf { Glow.isOn(settings) },
            rolesDrawnAtMillis = snapshot.rolesDrawnAtMillis,
            enabledFeatures = snapshot.enabledFeatures.mapNotNull { name ->
                ServerFeature.entries.firstOrNull { it.name == name }
            }.toSet(),
            features = settings.features,
            questKinds = settings.quests,
            items = snapshot.items,
            quests = snapshot.quests,
            zoneCenter = settings.zone.initial.center,
            zone = ZoneTimeline(settings.zone, startedAtMillis = null, streets = streets),
            streets = streets,
            buildings = buildings,
            bluetooth = inputs.bluetooth,
            radarEnabled = inputs.radarEnabled,
            capacity = capacity?.players,
            bigGame = snapshot.bigGame,
            friendsHere = players.filter { it.account.relation == UserRelation.FRIEND },
            // A big game's poll lists only the player and their friends; the server counts everybody.
            playerCount = snapshot.counts?.players ?: players.size,
            crowding = capacity?.takeIf { isHost && Capacity.needsWarning(it, players.size) }?.let {
                Crowding(
                    capacity = it.players ?: 0,
                    players = players.size,
                    isCrowded = Capacity.isCrowded(it, players.size),
                    fewCovers = it.fewCovers,
                )
            },
            openGame = settings.openGame,
            spectatorDelaySeconds = settings.spectatorDelaySeconds,
            spectators = snapshot.spectators,
            invitesSent = local.invitesSentIn == snapshot.gameId,
            message = inputs.message,
            isBusy = inputs.isBusy,
            isMapOpen = local.mapIn == snapshot.gameId,
            invite = local.invite?.takeIf { canInvite && it.gameId == snapshot.gameId }?.let { invite ->
                InvitePanelState(
                    friends = inputs.friends,
                    groups = inputs.groups,
                    pickedFriends = invite.pickedFriends,
                    pickedGroups = invite.pickedGroups,
                    isSending = invite.isSending,
                )
            },
            settings = local.settings?.takeIf { isHost && it.gameId == snapshot.gameId }?.let { draft ->
                SettingsPanelState(
                    setup = draft.setup,
                    draft = draft.settings(settings),
                    changedParts = draft.changedParts(settings),
                    origin = zoneOrigin(snapshot.gameId) ?: settings.zone.initial.center,
                    tab = draft.tab,
                    helpFor = draft.helpFor,
                    focusedExtra = local.focusedExtra,
                    preview = draft.preview,
                    isMovingCenter = draft.isMovingCenter,
                    pendingChanges = draft.pendingChanges,
                    isSaving = draft.isSaving,
                    draftZone = inputs.draftZone,
                )
            },
            buildingPicker = local.buildings?.takeIf { isHost && it.gameId == snapshot.gameId }?.let { picker ->
                val picked = picker.tap?.let { tap -> buildings?.pickedAt(tap) }
                BuildingPickerState(
                    picked = picked,
                    isPickedOpen = picked != null && buildings?.open?.contains(picked) == true,
                    isToggling = picker.isToggling,
                )
            },
            board = local.board.takeIf { isHost && local.boardIn == snapshot.gameId }?.let { board ->
                BoardPanelState(
                    kinds = allowedKinds(settings.features),
                    pick = board.pick,
                    kind = board.kind,
                    audience = board.audience,
                    name = board.name,
                    sparks = board.sparks ?: BoardRules.defaultSparks(board.kind),
                    perk = board.perk,
                    isPlacing = board.isPlacing,
                    questText = board.questText,
                    questAudience = board.questAudience,
                    isAddingQuest = board.isAddingQuest,
                )
            },
        )
    }

    private fun Int.minutesRoundedUp(): Int = (this + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE

    private companion object {
        const val SECONDS_PER_MINUTE = 60

        /** A zone polygon needs a closed ring. */
        const val MIN_OUTLINE_POINTS = 4

        /** A player whose phone has not asked the server for this long is shown as not connected. */
        const val OFFLINE_AFTER_MILLIS = 20_000L
    }
}

/** The building (forbidden or open) at [point]; null: none there. */
internal fun BuildingsResponse.pickedAt(point: GeoPoint): BuildingArea? =
    (buildings + open).firstOrNull { it.contains(point) }

/** This phone hosts the snapshot's game: the host's panels open only for the host. */
internal val GameSnapshot.amHost: Boolean get() = hostId == me.playerId
