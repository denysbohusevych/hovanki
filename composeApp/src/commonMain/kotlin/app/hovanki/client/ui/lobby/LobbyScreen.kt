package app.hovanki.client.ui.lobby

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.automation.TestTags
import app.hovanki.client.radio.rememberBluetoothPermissionRequester
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_dismiss
import app.hovanki.client.resources.action_leave
import app.hovanki.client.resources.building_rule_off
import app.hovanki.client.resources.ic_back
import app.hovanki.client.resources.ic_copy
import app.hovanki.client.resources.ic_dice
import app.hovanki.client.resources.ic_person_add
import app.hovanki.client.resources.ic_share
import app.hovanki.client.resources.ic_sliders
import app.hovanki.client.resources.invites_sent
import app.hovanki.client.resources.lobby_bluetooth_by_player
import app.hovanki.client.resources.lobby_bluetooth_denied
import app.hovanki.client.resources.lobby_bluetooth_off
import app.hovanki.client.resources.lobby_bluetooth_on
import app.hovanki.client.resources.lobby_board_count
import app.hovanki.client.resources.lobby_buildings_loading
import app.hovanki.client.resources.lobby_buildings_ready
import app.hovanki.client.resources.lobby_chip_activity
import app.hovanki.client.resources.lobby_chip_checkpoints
import app.hovanki.client.resources.lobby_chip_glow
import app.hovanki.client.resources.lobby_chip_perks
import app.hovanki.client.resources.lobby_chip_pickups
import app.hovanki.client.resources.lobby_chip_pocket_stealth
import app.hovanki.client.resources.lobby_chip_precision
import app.hovanki.client.resources.lobby_chip_proximity
import app.hovanki.client.resources.lobby_chip_quests
import app.hovanki.client.resources.lobby_chip_radar
import app.hovanki.client.resources.lobby_chip_radar_required
import app.hovanki.client.resources.lobby_chip_sense
import app.hovanki.client.resources.lobby_chip_streets
import app.hovanki.client.resources.lobby_chip_time
import app.hovanki.client.resources.lobby_chip_zone
import app.hovanki.client.resources.lobby_code_copied
import app.hovanki.client.resources.lobby_code_hint
import app.hovanki.client.resources.lobby_code_title
import app.hovanki.client.resources.lobby_copy_code
import app.hovanki.client.resources.lobby_hider
import app.hovanki.client.resources.lobby_host
import app.hovanki.client.resources.lobby_invite
import app.hovanki.client.resources.lobby_my_radar
import app.hovanki.client.resources.lobby_my_radar_hint
import app.hovanki.client.resources.lobby_no_radar
import app.hovanki.client.resources.lobby_offline
import app.hovanki.client.resources.lobby_pick_seekers
import app.hovanki.client.resources.lobby_players
import app.hovanki.client.resources.lobby_radar_allow
import app.hovanki.client.resources.lobby_radar_denied
import app.hovanki.client.resources.lobby_radar_required_off
import app.hovanki.client.resources.lobby_radar_text
import app.hovanki.client.resources.lobby_radar_turn_on
import app.hovanki.client.resources.lobby_radar_unsupported
import app.hovanki.client.resources.lobby_random
import app.hovanki.client.resources.lobby_roles_by_host
import app.hovanki.client.resources.lobby_seeker
import app.hovanki.client.resources.lobby_settings
import app.hovanki.client.resources.lobby_share
import app.hovanki.client.resources.lobby_share_text
import app.hovanki.client.resources.lobby_start
import app.hovanki.client.resources.lobby_start_hint
import app.hovanki.client.resources.lobby_start_wait_streets
import app.hovanki.client.resources.lobby_streets_loading
import app.hovanki.client.resources.lobby_title
import app.hovanki.client.resources.lobby_uwb
import app.hovanki.client.resources.lobby_waiting
import app.hovanki.client.resources.lobby_you
import app.hovanki.client.resources.lobby_you_hide
import app.hovanki.client.resources.lobby_you_seek
import app.hovanki.client.resources.street_zone_off
import app.hovanki.client.share.ShareSheet
import app.hovanki.client.ui.chat.ChatIconButton
import app.hovanki.client.ui.chat.ChatPanel
import app.hovanki.client.ui.chat.ChatViewModel
import app.hovanki.client.ui.common.Avatar
import app.hovanki.client.ui.common.Banner
import app.hovanki.client.ui.common.CapsText
import app.hovanki.client.ui.common.CommandStatus
import app.hovanki.client.ui.common.LoadingScreen
import app.hovanki.client.ui.common.PlayerAccountBadge
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopChip
import app.hovanki.client.ui.common.PopIconButton
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.PopSurface
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.SessionBanners
import app.hovanki.client.ui.common.Toast
import app.hovanki.client.ui.common.plainTextClipEntry
import app.hovanki.client.ui.common.rememberReduceMotion
import app.hovanki.client.ui.common.rememberToastVisible
import app.hovanki.client.ui.theme.Hovanki
import app.hovanki.client.ui.theme.Motion
import app.hovanki.client.ui.theme.Palette
import app.hovanki.client.ui.theme.color
import app.hovanki.client.ui.theme.onColor
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.FeatureMode
import app.hovanki.shared.protocol.ZoneShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.random.Random
import app.hovanki.shared.protocol.Role as GameRole

/**
 * The lobby (docs/design.md, «Лобби»): the join code on a lime card, the game's settings (the host changes them), the
 * players with their role pills (the host switches them, everybody sees them), and «Start» at the bottom.
 */
@Composable
fun LobbyScreen(viewModel: LobbyViewModel = koinViewModel(), chat: ChatViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val chatState by chat.uiState.collectAsStateWithLifecycle()
    val state = uiState
    if (state == null) {
        LoadingScreen()
        return
    }
    if (chatState.isOpen) {
        ChatPanel(chat)
        return
    }
    if (state.canInvite && viewModel.invitePanelIn == state.gameId) {
        InvitePanel(state, viewModel)
        return
    }
    if (state.isHost && viewModel.settingsPanelIn == state.gameId) {
        SettingsPanel(state, viewModel)
        return
    }
    if (state.isHost && viewModel.boardPanelIn == state.gameId) {
        BoardPanel(state, viewModel)
        return
    }
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val reduceMotion = rememberReduceMotion()
    // «Random»: on every phone the dice wobbles and the pills flicker for a moment, then show the server's draw.
    var shuffles by remember { mutableIntStateOf(0) }
    var seenDraw by remember { mutableStateOf(state.rolesDrawnAtMillis) }
    LaunchedEffect(state.rolesDrawnAtMillis) {
        if (state.rolesDrawnAtMillis != seenDraw) shuffles++
        seenDraw = state.rolesDrawnAtMillis
    }
    var codeCopies by remember { mutableIntStateOf(0) }
    // Players already here when the lobby opens just show; the ones joining later slide in.
    val initialPlayers = remember { state.players.map { it.id }.toSet() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().testTag(TestTags.LOBBY_SCREEN)) {
            ScreenColumn(modifier = Modifier.weight(1f)) {
                Header(chatUnread = chatState.unread, onOpenChat = chat::open, onLeave = viewModel::leave)
                JoinCodeCard(state.joinCode, onCopied = { codeCopies++ })
                SessionBanners(
                    connectionStatus = state.connectionStatus,
                    isSharingLocation = state.isSharingLocation,
                    error = state.error,
                    onDismissError = viewModel::dismissError,
                    onLocationPermissionGranted = viewModel::onLocationPermissionGranted,
                )
                SettingsChips(state, onOpenSettings = viewModel::openSettings, onOpenBoard = viewModel::openBoard)
                if (state.features.hasRadar) RadarCard(state, viewModel)
                if (state.isBuildingRuleOff) {
                    Banner(
                        text = stringResource(Res.string.building_rule_off),
                        modifier = Modifier.testTag(TestTags.BUILDING_RULE_OFF),
                    )
                }
                if (state.isStreetZoneOff) {
                    Banner(
                        text = stringResource(Res.string.street_zone_off),
                        modifier = Modifier.testTag(TestTags.STREET_ZONE_OFF),
                    )
                }
                if (!state.isHost) MyRoleCard(isSeeker = state.amSeeker)

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.lobby_players, state.players.size),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.isHost) {
                        PopButton(
                            onClick = viewModel::drawSeekers,
                            enabled = state.players.size >= 2,
                            style = PopStyle.Pink,
                            height = 40.dp,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.testTag(TestTags.LOBBY_RANDOM),
                        ) {
                            val wobble = remember { Animatable(0f) }
                            LaunchedEffect(shuffles) {
                                if (shuffles == 0 || reduceMotion) return@LaunchedEffect
                                for (angle in DICE_WOBBLE) wobble.animateTo(angle, tween(DICE_STEP_MILLIS))
                            }
                            Icon(
                                painterResource(Res.drawable.ic_dice),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp).rotate(wobble.value),
                            )
                            Text(stringResource(Res.string.lobby_random), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (state.canInvite) {
                        PopIconButton(
                            icon = Res.drawable.ic_person_add,
                            contentDescription = stringResource(Res.string.lobby_invite),
                            onClick = { viewModel.openInvites(state.gameId) },
                            size = 40.dp,
                            iconSize = 20.dp,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag(TestTags.LOBBY_INVITE),
                        )
                    }
                }
                if (viewModel.invitesSentIn == state.gameId) {
                    Banner(
                        text = stringResource(Res.string.invites_sent),
                        modifier = Modifier.testTag(TestTags.INVITES_SENT),
                        actionLabel = stringResource(Res.string.action_dismiss),
                        onAction = viewModel::dismissInvitesSent,
                    )
                }
                if (state.isHost) SecondaryText(stringResource(Res.string.lobby_pick_seekers))
                PopCard(contentPadding = PaddingValues(0.dp), verticalArrangement = Arrangement.Top) {
                    state.players.forEachIndexed { index, player ->
                        key(player.id) {
                            val isNew = player.id !in initialPlayers && !reduceMotion
                            val appeared = remember { MutableTransitionState(!isNew) }
                            appeared.targetState = true
                            AnimatedVisibility(
                                visibleState = appeared,
                                enter = expandVertically(Motion.base()) + slideInVertically(Motion.base()) { it } +
                                    fadeIn(Motion.base()),
                            ) {
                                Column {
                                    if (index > 0) HorizontalDivider(color = Palette.Line, thickness = 1.5.dp)
                                    PlayerRow(
                                        player = player,
                                        showRadar = state.features.hasRadar,
                                        canPickRoles = state.isHost,
                                        isBusy = isBusy,
                                        isNew = isNew,
                                        shuffles = if (reduceMotion) 0 else shuffles,
                                        onToggleSeeker = { viewModel.toggleSeeker(player.id) },
                                        onAddFriend = { player.account.userId?.let(viewModel::addFriend) },
                                    )
                                }
                            }
                        }
                    }
                }
                CommandStatus(
                    isBusy = false,
                    message = message,
                    onDismiss = viewModel::dismissMessage,
                    errorTag = TestTags.SOCIAL_ERROR,
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (state.isHost) {
                    PopButton(
                        text = stringResource(Res.string.lobby_start),
                        onClick = viewModel::start,
                        enabled = state.canStart && !state.isStarting,
                        height = 60.dp,
                        textStyle = MaterialTheme.typography.headlineSmall.copy(fontSize = 18.sp),
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.LOBBY_START),
                    )
                    SecondaryText(
                        if (state.isBuildingStreetZone) {
                            stringResource(Res.string.lobby_start_wait_streets)
                        } else {
                            stringResource(Res.string.lobby_start_hint, state.hidingMinutes)
                        },
                    )
                } else {
                    Text(
                        text = stringResource(Res.string.lobby_waiting),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 12.dp).testTag(TestTags.LOBBY_WAITING),
                    )
                }
            }
        }
        Toast(
            visible = rememberToastVisible(codeCopies),
            text = stringResource(Res.string.lobby_code_copied),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp),
        )
    }
}

/** «Leave» on the left, the title, the chat on the right. */
@Composable
private fun Header(chatUnread: Int, onOpenChat: () -> Unit, onLeave: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onLeave) {
            Icon(painterResource(Res.drawable.ic_back), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(Res.string.action_leave), style = MaterialTheme.typography.labelLarge)
        }
        Text(
            text = stringResource(Res.string.lobby_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        ChatIconButton(unread = chatUnread, onClick = onOpenChat, size = 44.dp)
    }
}

/** The join code on lime: «Share» sends it through the system menu, the corner button copies it. */
@Composable
private fun JoinCodeCard(joinCode: String, onCopied: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val shareSheet = koinInject<ShareSheet>()
    val shareText = stringResource(Res.string.lobby_share_text, joinCode)
    Box {
        PopCard(
            modifier = Modifier.fillMaxWidth(),
            color = Palette.Lime,
            borderWidth = 2.5.dp,
            shadow = 5.dp,
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            CapsText(stringResource(Res.string.lobby_code_title))
            Text(
                text = joinCode,
                style = Hovanki.text.code.copy(fontSize = 34.sp, letterSpacing = 3.sp),
                modifier = Modifier.testTag(TestTags.LOBBY_JOIN_CODE),
            )
            Text(
                text = stringResource(Res.string.lobby_code_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.LimeInk,
                modifier = Modifier.padding(end = 48.dp),
            )
            PopButton(
                text = stringResource(Res.string.lobby_share),
                onClick = { shareSheet.share(shareText) },
                style = PopStyle.Dark,
                height = 44.dp,
                icon = Res.drawable.ic_share,
                modifier = Modifier.padding(top = 10.dp).testTag(TestTags.LOBBY_SHARE),
            )
        }
        PopIconButton(
            icon = Res.drawable.ic_copy,
            contentDescription = stringResource(Res.string.lobby_copy_code),
            onClick = {
                scope.launch { clipboard.setClipEntry(plainTextClipEntry(joinCode)) }
                onCopied()
            },
            style = PopStyle.Dark,
            size = 44.dp,
            iconSize = 20.dp,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 14.dp),
        )
    }
}

/**
 * A player: avatar, name, «you»/«host», «not connected», «guest» or what they are to the viewer (add as a friend), and
 * a pill with the role: the host taps it to switch between «hides» and «seeks», everybody else just sees it.
 */
@Composable
private fun PlayerRow(
    player: LobbyPlayer,
    showRadar: Boolean,
    canPickRoles: Boolean,
    isBusy: Boolean,
    isNew: Boolean,
    shuffles: Int,
    onToggleSeeker: () -> Unit,
    onAddFriend: () -> Unit,
) {
    val youTag = stringResource(Res.string.lobby_you)
    val hostTag = stringResource(Res.string.lobby_host)
    val tags = listOfNotNull(youTag.takeIf { player.isMe }, hostTag.takeIf { player.isHost })
    val shownSeeker = rememberFlicker(shuffles, player.isSeeker, seed = player.id.value.hashCode())
    val role = if (shownSeeker) GameRole.SEEKER else GameRole.HIDER
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(TestTags.lobbyPlayer(player.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // A player who just joined: the avatar pops.
        val avatarScale = remember { Animatable(if (isNew) 0.3f else 1f) }
        LaunchedEffect(Unit) { avatarScale.animateTo(1f, Motion.pop()) }
        val avatarModifier = Modifier.scale(avatarScale.value)
        val avatarColor by animateColorAsState(role.color, Motion.fast())
        Avatar(
            name = player.name,
            color = avatarColor,
            contentColor = role.onColor,
            size = 36.dp,
            modifier = avatarModifier,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = player.name, style = MaterialTheme.typography.titleSmall)
                if (tags.isNotEmpty()) {
                    Text(
                        text = tags.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Ink2,
                    )
                }
            }
            if (player.isOffline) {
                Text(
                    text = stringResource(Res.string.lobby_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.PinkInk,
                    modifier = Modifier.testTag(TestTags.lobbyOffline(player.id)),
                )
            }
            // What the phone can do for the radar (docs/adr/0012): «no radar» when it can't take part, UWB when it can
            // do more; nothing said, nothing shown.
            if (showRadar) {
                val capabilities = player.capabilities
                val ability = when {
                    capabilities == null -> null
                    capabilities.bluetooth != BluetoothState.ON -> stringResource(Res.string.lobby_no_radar)
                    capabilities.uwb -> stringResource(Res.string.lobby_uwb)
                    else -> null
                }
                if (ability != null) {
                    Text(
                        text = ability,
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Ink2,
                        modifier = Modifier.testTag(TestTags.lobbyCapability(player.id)),
                    )
                }
            }
            PlayerAccountBadge(player.id, player.account, isBusy = isBusy, onAddFriend = onAddFriend)
        }
        RolePill(
            isSeeker = shownSeeker,
            onClick = onToggleSeeker.takeIf { canPickRoles },
            modifier = Modifier.testTag(
                if (canPickRoles) TestTags.seekerSwitch(player.id) else TestTags.lobbyRole(player.id),
            ),
        )
    }
}

/** «Seeks» in orange or «hides» in violet; the host taps it to switch ([onClick]), the others only see it. */
@Composable
private fun RolePill(isSeeker: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val role = if (isSeeker) GameRole.SEEKER else GameRole.HIDER
    val container by animateColorAsState(role.color, Motion.fast())
    val content by animateColorAsState(role.onColor, Motion.fast())
    // A light pop on every switch.
    val scale = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(isSeeker) }
    LaunchedEffect(isSeeker) {
        if (previous == isSeeker) return@LaunchedEffect
        previous = isSeeker
        scale.snapTo(PILL_POP_FROM)
        scale.animateTo(1f, Motion.pop())
    }
    PopSurface(
        modifier = modifier.scale(scale.value).height(36.dp).width(104.dp),
        shape = RoundedCornerShape(18.dp),
        color = container,
        contentColor = content,
        borderWidth = 2.dp,
        onClick = onClick,
        role = Role.Switch,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(if (isSeeker) Res.string.lobby_seeker else Res.string.lobby_hider),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * The game's setup as chips (the zone and its shape, hiding + search time, the glow, the extras that are on), the
 * state of the zone's map data, the board when the game has one, and for the host the button to change the setup.
 */
@Composable
private fun SettingsChips(state: LobbyUiState, onOpenSettings: () -> Unit, onOpenBoard: () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PopChip(
            if (state.zoneShape == ZoneShape.STREETS) {
                stringResource(Res.string.lobby_chip_streets, state.zoneRadiusMeters)
            } else {
                stringResource(Res.string.lobby_chip_zone, state.zoneRadiusMeters)
            },
        )
        PopChip(stringResource(Res.string.lobby_chip_time, state.hidingMinutes, state.seekingMinutes))
        state.glowEveryMinutes?.let { PopChip(stringResource(Res.string.lobby_chip_glow, it)) }
        FeatureChips(state)
        when {
            state.isBuildingStreetZone -> PopChip(
                text = stringResource(Res.string.lobby_streets_loading),
                color = Palette.Sand,
                contentColor = Palette.Ink2,
                modifier = Modifier.testTag(TestTags.LOBBY_STREETS),
            )

            state.buildingsState == BuildingsState.LOADING -> PopChip(
                text = stringResource(Res.string.lobby_buildings_loading),
                color = Palette.Sand,
                contentColor = Palette.Ink2,
                modifier = Modifier.testTag(TestTags.LOBBY_BUILDINGS),
            )

            state.buildingCount != null -> PopChip(
                text = stringResource(Res.string.lobby_buildings_ready, state.buildingCount),
                color = Palette.Sand,
                contentColor = Palette.Ink2,
                modifier = Modifier.testTag(TestTags.LOBBY_BUILDINGS),
            )
        }
        if (state.features.hasBoard) {
            PopChip(
                text = stringResource(Res.string.lobby_board_count, state.items.size),
                color = Palette.Orange,
                contentColor = Palette.Ink,
                border = Palette.Ink,
                onClick = onOpenBoard.takeIf { state.isHost },
                modifier = Modifier.testTag(TestTags.LOBBY_BOARD),
            )
        }
        if (state.isHost) {
            PopChip(
                text = stringResource(Res.string.lobby_settings),
                color = Palette.Lime,
                contentColor = Palette.Ink,
                border = Palette.Ink,
                icon = Res.drawable.ic_sliders,
                onClick = onOpenSettings,
                modifier = Modifier.testTag(TestTags.LOBBY_SETTINGS),
            )
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
    chips.forEach { chip ->
        PopChip(
            text = stringResource(chip),
            color = Palette.Violet,
            contentColor = androidx.compose.ui.graphics.Color.White,
        )
    }
}

/**
 * The radar on this phone (docs/adr/0012-nearby-radar.md, section 4.4): what the game does with Bluetooth, whether
 * this phone can take part (allow it, turn it on), and «the radar on my phone» when the game leaves the choice.
 */
@Composable
private fun RadarCard(state: LobbyUiState, viewModel: LobbyViewModel) {
    val requestPermission = rememberBluetoothPermissionRequester {}
    val bluetooth = state.bluetooth
    val (stateText, stateColor) = when {
        bluetooth == BluetoothState.ON && !state.radarEnabled ->
            stringResource(Res.string.lobby_bluetooth_by_player) to Palette.Sand

        bluetooth == BluetoothState.ON -> stringResource(Res.string.lobby_bluetooth_on) to Palette.Lime

        bluetooth == BluetoothState.DENIED -> stringResource(Res.string.lobby_bluetooth_denied) to Palette.Pink

        else -> stringResource(Res.string.lobby_bluetooth_off) to Palette.Pink
    }
    PopCard(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.lobby_chip_radar),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            PopChip(
                text = stateText,
                color = stateColor,
                contentColor = Palette.Ink,
                border = Palette.Ink,
                modifier = Modifier.testTag(TestTags.LOBBY_BLUETOOTH),
            )
        }
        SecondaryText(stringResource(Res.string.lobby_radar_text))
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
                onCheckedChange = viewModel::setRadarEnabled,
                tag = TestTags.LOBBY_MY_RADAR,
            )
            SecondaryText(stringResource(Res.string.lobby_my_radar_hint))
        }
    }
}

/** A player who is not the host: the role the host gave them so far, big, in its color. */
@Composable
private fun MyRoleCard(isSeeker: Boolean) {
    val role = if (isSeeker) GameRole.SEEKER else GameRole.HIDER
    val container by animateColorAsState(role.color, Motion.fast())
    val content by animateColorAsState(role.onColor, Motion.fast())
    PopCard(
        modifier = Modifier.fillMaxWidth(),
        color = container,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(if (isSeeker) Res.string.lobby_you_seek else Res.string.lobby_you_hide),
            style = MaterialTheme.typography.headlineSmall,
            color = content,
            modifier = Modifier.testTag(TestTags.LOBBY_MY_ROLE),
        )
        Text(
            text = stringResource(Res.string.lobby_roles_by_host),
            style = MaterialTheme.typography.bodySmall,
            color = content,
        )
    }
}

/**
 * For [FLICKER_MILLIS] after [trigger] changes (above zero), a role that flips at random, then [actual]: the roles
 * «shuffling» after «Random».
 */
@Composable
private fun rememberFlicker(trigger: Int, actual: Boolean, seed: Int): Boolean {
    var shown by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        val random = Random(seed + trigger)
        repeat(FLICKER_MILLIS / FLICKER_STEP_MILLIS) {
            shown = random.nextBoolean()
            delay(FLICKER_STEP_MILLIS.toLong())
        }
        shown = null
    }
    return shown ?: actual
}

private val DICE_WOBBLE = listOf(-22f, 18f, -10f, 0f)
private const val DICE_STEP_MILLIS = 90
private const val FLICKER_MILLIS = 300
private const val FLICKER_STEP_MILLIS = 60
private const val PILL_POP_FROM = 0.86f
