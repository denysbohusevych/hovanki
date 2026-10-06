package app.hovanki.client.ui.lobby

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.radio.rememberBluetoothPermissionRequester
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.ic_chevron_right
import app.hovanki.client.resources.ic_copy
import app.hovanki.client.resources.ic_expand
import app.hovanki.client.resources.ic_eye
import app.hovanki.client.resources.ic_info
import app.hovanki.client.resources.ic_radar
import app.hovanki.client.resources.ic_share
import app.hovanki.client.resources.ic_sliders
import app.hovanki.client.resources.lobby_bluetooth_by_player
import app.hovanki.client.resources.lobby_bluetooth_denied
import app.hovanki.client.resources.lobby_bluetooth_off
import app.hovanki.client.resources.lobby_bluetooth_on
import app.hovanki.client.resources.lobby_board_count
import app.hovanki.client.resources.lobby_buildings_loading
import app.hovanki.client.resources.lobby_buildings_ready
import app.hovanki.client.resources.lobby_change
import app.hovanki.client.resources.lobby_chip_activity
import app.hovanki.client.resources.lobby_chip_checkpoints
import app.hovanki.client.resources.lobby_chip_open
import app.hovanki.client.resources.lobby_chip_open_live
import app.hovanki.client.resources.lobby_chip_perks
import app.hovanki.client.resources.lobby_chip_pickups
import app.hovanki.client.resources.lobby_chip_pocket_stealth
import app.hovanki.client.resources.lobby_chip_precision
import app.hovanki.client.resources.lobby_chip_proximity
import app.hovanki.client.resources.lobby_chip_quests
import app.hovanki.client.resources.lobby_chip_radar
import app.hovanki.client.resources.lobby_chip_radar_required
import app.hovanki.client.resources.lobby_chip_sense
import app.hovanki.client.resources.lobby_code_title
import app.hovanki.client.resources.lobby_copy_code
import app.hovanki.client.resources.lobby_map_circle
import app.hovanki.client.resources.lobby_map_drawn
import app.hovanki.client.resources.lobby_map_expand
import app.hovanki.client.resources.lobby_map_open
import app.hovanki.client.resources.lobby_map_shrinks
import app.hovanki.client.resources.lobby_map_streets
import app.hovanki.client.resources.lobby_map_title
import app.hovanki.client.resources.lobby_my_radar
import app.hovanki.client.resources.lobby_my_radar_hint
import app.hovanki.client.resources.lobby_radar_allow
import app.hovanki.client.resources.lobby_radar_denied
import app.hovanki.client.resources.lobby_radar_more
import app.hovanki.client.resources.lobby_radar_required_off
import app.hovanki.client.resources.lobby_radar_text
import app.hovanki.client.resources.lobby_radar_turn_on
import app.hovanki.client.resources.lobby_radar_unsupported
import app.hovanki.client.resources.lobby_recorded
import app.hovanki.client.resources.lobby_recorded_more
import app.hovanki.client.resources.lobby_recorded_short
import app.hovanki.client.resources.lobby_roles_by_host
import app.hovanki.client.resources.lobby_settings
import app.hovanki.client.resources.lobby_share
import app.hovanki.client.resources.lobby_share_text
import app.hovanki.client.resources.lobby_streets_loading
import app.hovanki.client.resources.lobby_summary_capacity
import app.hovanki.client.resources.lobby_summary_glow
import app.hovanki.client.resources.lobby_summary_no_glow
import app.hovanki.client.resources.lobby_summary_time
import app.hovanki.client.resources.lobby_tile_capacity
import app.hovanki.client.resources.lobby_tile_capacity_value
import app.hovanki.client.resources.lobby_tile_glow
import app.hovanki.client.resources.lobby_tile_glow_off
import app.hovanki.client.resources.lobby_tile_glow_value
import app.hovanki.client.resources.lobby_tile_time
import app.hovanki.client.resources.lobby_tile_time_value
import app.hovanki.client.resources.lobby_tile_unknown
import app.hovanki.client.resources.lobby_you_hide
import app.hovanki.client.resources.lobby_you_seek
import app.hovanki.client.session.ZoneCue
import app.hovanki.client.share.ShareSheet
import app.hovanki.client.ui.common.Banner
import app.hovanki.client.ui.common.CapsText
import app.hovanki.client.ui.common.Panel
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopChip
import app.hovanki.client.ui.common.PopIconButton
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.PopSurface
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.SpectatorsChip
import app.hovanki.client.ui.common.plainTextClipEntry
import app.hovanki.client.ui.game.GameMap
import app.hovanki.client.ui.game.toMapItem
import app.hovanki.client.ui.settings.SettingsTab
import app.hovanki.client.ui.settings.distanceText
import app.hovanki.client.ui.theme.Hovanki
import app.hovanki.client.ui.theme.Motion
import app.hovanki.client.ui.theme.Palette
import app.hovanki.client.ui.theme.color
import app.hovanki.client.ui.theme.onColor
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.FeatureMode
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.ZoneShape
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/*
 * The lobby's parts (docs/adr/0014-settings-lobby-redesign-open-buildings.md, «Лобби»): short, one line each, the
 * details a tap away.
 */

/** The join code on green in one row: the code, «Share» (the system menu) and «Copy». */
@Composable
internal fun JoinCodeRow(joinCode: String, onCopied: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val shareSheet = koinInject<ShareSheet>()
    val shareText = stringResource(Res.string.lobby_share_text, joinCode)
    PopCard(
        modifier = Modifier.fillMaxWidth(),
        color = Palette.Green,
        borderWidth = 2.5.dp,
        shadow = 5.dp,
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                CapsText(stringResource(Res.string.lobby_code_title))
                Text(
                    text = joinCode,
                    style = Hovanki.text.code.copy(fontSize = 30.sp, letterSpacing = 3.sp),
                    modifier = Modifier.testTag(TestTags.LOBBY_JOIN_CODE),
                )
            }
            PopIconButton(
                icon = Res.drawable.ic_share,
                contentDescription = stringResource(Res.string.lobby_share),
                onClick = { shareSheet.share(shareText) },
                style = PopStyle.Dark,
                size = 48.dp,
                iconSize = 20.dp,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.testTag(TestTags.LOBBY_SHARE),
            )
            PopIconButton(
                icon = Res.drawable.ic_copy,
                contentDescription = stringResource(Res.string.lobby_copy_code),
                onClick = {
                    scope.launch { clipboard.setClipEntry(plainTextClipEntry(joinCode)) }
                    onCopied()
                },
                size = 48.dp,
                iconSize = 20.dp,
                shape = RoundedCornerShape(16.dp),
            )
        }
    }
}

/**
 * «Where we play»: the zone on the map for everybody, its shape and whether it shrinks, the buildings (pink, open ones
 * green) and the state of the map's data. A tap opens it full screen.
 */
@Composable
internal fun WherePlayCard(state: LobbyUiState, onExpand: () -> Unit) {
    val expand = stringResource(Res.string.lobby_map_expand)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(MAP_CARD_HEIGHT)
            .background(Palette.Paper, RoundedCornerShape(22.dp))
            .border(2.5.dp, Palette.Ink, RoundedCornerShape(22.dp))
            .padding(2.5.dp)
            .testTag(TestTags.LOBBY_MAP),
    ) {
        LobbyMap(state, interactive = false, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)))
        // Over the map, which takes no gestures here: the whole card opens it.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember {
                        MutableInteractionSource()
                    },
                    indication = null,
                ) { onExpand() }
                .semantics { contentDescription = expand },
        )
        MapLabel(zoneLabel(state), Modifier.align(Alignment.TopStart).padding(10.dp))
        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val open = state.buildings?.open?.size ?: 0
            if (open > 0) {
                MapLabel(
                    stringResource(Res.string.lobby_map_open, open),
                    color = Palette.Green,
                    contentColor = Palette.Ink,
                )
            }
            MapDataLabel(state)
        }
        Icon(
            painter = painterResource(Res.drawable.ic_expand),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp)
                .size(36.dp)
                .background(Palette.Paper, CircleShape)
                .border(2.dp, Palette.Ink, CircleShape)
                .padding(8.dp),
        )
    }
}

/** The zone full screen, for everybody before the start: where it is, its buildings and the board they may see. */
@Composable
fun LobbyMapPanel(state: LobbyUiState, onEvent: (LobbyEvent) -> Unit) {
    Panel(
        title = stringResource(Res.string.lobby_map_title),
        onClose = { onEvent(LobbyEvent.CloseMap) },
        modifier = Modifier.testTag(TestTags.LOBBY_MAP_PANEL),
        screen = "lobby_map",
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LobbyMap(state, interactive = true, modifier = Modifier.fillMaxSize())
            MapLabel(zoneLabel(state), Modifier.align(Alignment.TopStart).padding(12.dp))
        }
    }
}

@Composable
private fun LobbyMap(state: LobbyUiState, interactive: Boolean, modifier: Modifier) {
    GameMap(
        zone = state.zone,
        serverNow = { 0L },
        cue = ZoneCue.CALM,
        myLocation = null,
        myRole = if (state.amSeeker) Role.SEEKER else Role.HIDER,
        markers = emptyList(),
        buildings = state.buildings,
        items = state.items.map { it.toMapItem(null) },
        interactive = interactive,
        modifier = modifier,
    )
}

/** «Circle 500 m · shrinks». */
@Composable
private fun zoneLabel(state: LobbyUiState): String {
    val size = distanceText(state.zoneRadiusMeters.toDouble())
    val shape = when (state.zoneShape) {
        ZoneShape.STREETS -> stringResource(Res.string.lobby_map_streets, size)
        ZoneShape.DRAWN -> stringResource(Res.string.lobby_map_drawn, size)
        ZoneShape.CIRCLE -> stringResource(Res.string.lobby_map_circle, size)
    }
    return if (state.zone.schedule.stages.isNotEmpty()) stringResource(Res.string.lobby_map_shrinks, shape) else shape
}

/** The map's data on its way: the zone by streets being built, the buildings loading or how many. */
@Composable
private fun MapDataLabel(state: LobbyUiState) {
    val (text, tag) = when {
        state.isBuildingStreetZone -> stringResource(Res.string.lobby_streets_loading) to TestTags.LOBBY_STREETS

        state.buildingsState == BuildingsState.LOADING ->
            stringResource(Res.string.lobby_buildings_loading) to TestTags.LOBBY_BUILDINGS

        state.buildingCount != null ->
            stringResource(Res.string.lobby_buildings_ready, state.buildingCount) to TestTags.LOBBY_BUILDINGS

        else -> return
    }
    MapLabel(text, color = Palette.Sand, contentColor = Palette.Ink2, modifier = Modifier.testTag(tag))
}

@Composable
private fun MapLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.Ink,
    contentColor: Color = Color.White,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = contentColor,
        maxLines = 1,
        modifier = modifier
            .background(color, RoundedCornerShape(12.dp))
            .border(if (color == Palette.Ink) 0.dp else 2.dp, Palette.Ink, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/**
 * The game's setup in three tiles (time, glow, how many fit), the extras as chips, open to spectators, the board, and
 * for the host «Settings». The host's tap on a tile opens its tab.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsTiles(state: LobbyUiState, onOpenSettings: (SettingsTab) -> Unit, onOpenBoard: () -> Unit) {
    val open = onOpenSettings.takeIf { state.isHost && state.bigGame == null }
    SettingsSummary(state, onChange = open?.let { { it(SettingsTab.ZONE) } })
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FeatureChips(state)
        if (state.openGame) {
            PopChip(
                text = if (state.spectatorDelaySeconds > 0) {
                    stringResource(Res.string.lobby_chip_open, spectatorDelayText(state.spectatorDelaySeconds))
                } else {
                    stringResource(Res.string.lobby_chip_open_live)
                },
                color = Palette.Ink,
                contentColor = Palette.Green,
                icon = Res.drawable.ic_eye,
                modifier = Modifier.testTag(TestTags.LOBBY_OPEN),
            )
            if (state.spectators > 0) SpectatorsChip(state.spectators)
        }
        if (state.features.hasBoard) {
            PopChip(
                text = stringResource(Res.string.lobby_board_count, state.items.size),
                color = Palette.Green,
                contentColor = Palette.Ink,
                border = Palette.Ink,
                onClick = onOpenBoard.takeIf { state.isHost },
                modifier = Modifier.testTag(TestTags.LOBBY_BOARD),
            )
        }
    }
}

/**
 * The game's setup in one white row (docs/design.md, «Лобби»): an ink sliders badge, the zone, then the time, the glow
 * and how many fit; the host's «Change ›» opens the settings.
 */
@Composable
private fun SettingsSummary(state: LobbyUiState, onChange: (() -> Unit)?) {
    PopSurface(
        modifier = Modifier.fillMaxWidth().then(
            if (onChange != null) Modifier.testTag(TestTags.LOBBY_SETTINGS) else Modifier,
        ),
        shape = RoundedCornerShape(20.dp),
        onClick = onChange,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(Palette.Ink, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(Res.drawable.ic_sliders),
                    contentDescription = null,
                    tint = Palette.Green,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = zoneLabel(state), style = MaterialTheme.typography.titleMedium)
                val details = listOf(
                    stringResource(Res.string.lobby_summary_time, state.hidingMinutes + state.seekingMinutes),
                    state.glowEveryMinutes?.let { stringResource(Res.string.lobby_summary_glow, it) }
                        ?: stringResource(Res.string.lobby_summary_no_glow),
                )
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Ink2,
                )
                state.capacity?.let { capacity ->
                    Text(
                        text = stringResource(Res.string.lobby_summary_capacity, capacity),
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Ink2,
                        modifier = Modifier.testTag(TestTags.LOBBY_CAPACITY),
                    )
                }
            }
            if (onChange != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.lobby_change), style = MaterialTheme.typography.labelLarge)
                    Icon(
                        painterResource(Res.drawable.ic_chevron_right),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** The extras the host turned on (docs/adr/0012-nearby-radar.md, docs/adr/0013), one chip each. */
@Composable
private fun FeatureChips(state: LobbyUiState) {
    val features = state.features
    val chips = listOfNotNull(
        when (features.radar) {
            FeatureMode.OFF -> null
            FeatureMode.OPTIONAL -> Res.string.lobby_chip_radar
            FeatureMode.REQUIRED -> Res.string.lobby_chip_radar_required
        },
        Res.string.lobby_chip_sense.takeIf { features.hiderSense },
        Res.string.lobby_chip_proximity.takeIf { features.proximityCatch },
        Res.string.lobby_chip_pocket_stealth.takeIf { features.pocketStealth },
        Res.string.lobby_chip_precision.takeIf { features.precisionRadar },
        Res.string.lobby_chip_quests.takeIf { features.quests },
        Res.string.lobby_chip_perks.takeIf { features.perks },
        Res.string.lobby_chip_checkpoints.takeIf { features.checkpoints },
        Res.string.lobby_chip_pickups.takeIf { features.pickups },
        Res.string.lobby_chip_activity.takeIf { features.activity },
    )
    chips.forEach { chip -> PopChip(text = stringResource(chip), color = Palette.Pink, contentColor = Color.White) }
}

/**
 * Everybody is told the game is recorded (docs/adr/0011-spectators-and-recordings.md), guests too: one line always
 * there, the whole of it behind «i».
 */
@Composable
internal fun RecordingLine() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val more = stringResource(Res.string.lobby_recorded_more)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Sand)
            .clickable(onClickLabel = more) { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(TestTags.LOBBY_RECORDED),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(Res.drawable.ic_info), contentDescription = null, modifier = Modifier.size(22.dp))
        Text(
            text = stringResource(if (expanded) Res.string.lobby_recorded else Res.string.lobby_recorded_short),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The radar on this phone (docs/adr/0012-nearby-radar.md, section 4.4) in one row: Bluetooth's state and «the radar on
 * my phone»; what is wrong and what to do only when something is; the rest behind «i».
 */
@Composable
internal fun RadarRow(state: LobbyUiState, onEvent: (LobbyEvent) -> Unit) {
    val requestPermission = rememberBluetoothPermissionRequester {}
    var expanded by rememberSaveable { mutableStateOf(false) }
    val bluetooth = state.bluetooth
    val (stateText, stateColor) = when {
        bluetooth == BluetoothState.ON && !state.radarEnabled ->
            stringResource(Res.string.lobby_bluetooth_by_player) to Palette.Sand

        bluetooth == BluetoothState.ON -> stringResource(Res.string.lobby_bluetooth_on) to Palette.Green

        bluetooth == BluetoothState.DENIED -> stringResource(Res.string.lobby_bluetooth_denied) to Palette.Pink

        else -> stringResource(Res.string.lobby_bluetooth_off) to Palette.Pink
    }
    PopCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(Res.drawable.ic_radar), contentDescription = null, modifier = Modifier.size(24.dp))
            Text(
                text = stringResource(Res.string.lobby_chip_radar),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            PopChip(
                text = stateText,
                color = stateColor,
                contentColor = Palette.Ink,
                border = Palette.Ink,
                modifier = Modifier.testTag(TestTags.LOBBY_BLUETOOTH),
            )
            InfoButton(stringResource(Res.string.lobby_radar_more), onClick = { expanded = !expanded })
        }
        if (expanded) SecondaryText(stringResource(Res.string.lobby_radar_text))
        when (bluetooth) {
            BluetoothState.DENIED -> {
                SecondaryText(stringResource(Res.string.lobby_radar_denied))
                PopButton(
                    text = stringResource(Res.string.lobby_radar_allow),
                    onClick = requestPermission,
                    height = 44.dp,
                    modifier = Modifier.testTag(TestTags.LOBBY_BLUETOOTH_ALLOW),
                )
            }

            BluetoothState.OFF -> SecondaryText(stringResource(Res.string.lobby_radar_turn_on))

            BluetoothState.UNSUPPORTED -> SecondaryText(stringResource(Res.string.lobby_radar_unsupported))

            BluetoothState.ON, BluetoothState.OFF_BY_PLAYER -> Unit
        }
        if (state.features.radar == FeatureMode.REQUIRED && (bluetooth != BluetoothState.ON || !state.radarEnabled)) {
            Banner(text = stringResource(Res.string.lobby_radar_required_off))
        }
        if (bluetooth == BluetoothState.ON) {
            SwitchRow(
                text = stringResource(Res.string.lobby_my_radar),
                checked = state.radarEnabled,
                onCheckedChange = { onEvent(LobbyEvent.SetRadarEnabled(it)) },
                tag = TestTags.LOBBY_MY_RADAR,
            )
            if (expanded) SecondaryText(stringResource(Res.string.lobby_my_radar_hint))
        }
    }
}

/** A player who is not the host: the role the host gave them so far, in its color, in one row. */
@Composable
internal fun MyRoleRow(isSeeker: Boolean) {
    val role = if (isSeeker) Role.SEEKER else Role.HIDER
    val container by animateColorAsState(role.color, Motion.fast())
    val content by animateColorAsState(role.onColor, Motion.fast())
    PopCard(
        modifier = Modifier.fillMaxWidth(),
        color = container,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(if (isSeeker) Res.string.lobby_you_seek else Res.string.lobby_you_hide),
                style = MaterialTheme.typography.titleLarge,
                color = content,
                modifier = Modifier.weight(1f).testTag(TestTags.LOBBY_MY_ROLE),
            )
            Text(
                text = stringResource(Res.string.lobby_roles_by_host),
                style = MaterialTheme.typography.bodySmall,
                color = content,
            )
        }
    }
}

/** «i»: the rest of a line. */
@Composable
private fun InfoButton(description: String, onClick: () -> Unit) {
    Icon(
        painter = painterResource(Res.drawable.ic_info),
        contentDescription = description,
        modifier = Modifier
            .size(36.dp)
            .clickable(onClick = onClick)
            .padding(6.dp),
    )
}

private val MAP_CARD_HEIGHT = 170.dp
