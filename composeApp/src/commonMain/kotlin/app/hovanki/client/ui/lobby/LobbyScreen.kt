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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_dismiss
import app.hovanki.client.resources.action_leave
import app.hovanki.client.resources.big_games_title
import app.hovanki.client.resources.big_lobby_counts
import app.hovanki.client.resources.big_lobby_friends
import app.hovanki.client.resources.big_lobby_no_friends
import app.hovanki.client.resources.big_lobby_roles
import app.hovanki.client.resources.big_lobby_starting
import app.hovanki.client.resources.big_lobby_starts_in
import app.hovanki.client.resources.building_rule_off
import app.hovanki.client.resources.ic_back
import app.hovanki.client.resources.ic_dice
import app.hovanki.client.resources.ic_person_add
import app.hovanki.client.resources.invites_sent
import app.hovanki.client.resources.lobby_code_copied
import app.hovanki.client.resources.lobby_crowded
import app.hovanki.client.resources.lobby_few_covers
import app.hovanki.client.resources.lobby_hider
import app.hovanki.client.resources.lobby_host
import app.hovanki.client.resources.lobby_invite
import app.hovanki.client.resources.lobby_no_radar
import app.hovanki.client.resources.lobby_offline
import app.hovanki.client.resources.lobby_pick_seekers
import app.hovanki.client.resources.lobby_play_anyway
import app.hovanki.client.resources.lobby_players
import app.hovanki.client.resources.lobby_random
import app.hovanki.client.resources.lobby_seeker
import app.hovanki.client.resources.lobby_start
import app.hovanki.client.resources.lobby_start_hint
import app.hovanki.client.resources.lobby_start_wait_streets
import app.hovanki.client.resources.lobby_title
import app.hovanki.client.resources.lobby_uwb
import app.hovanki.client.resources.lobby_waiting
import app.hovanki.client.resources.lobby_you
import app.hovanki.client.resources.street_zone_off
import app.hovanki.client.session.ServerClock
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
import app.hovanki.client.ui.common.PopIconButton
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.PopSurface
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.SectionTitle
import app.hovanki.client.ui.common.SessionBanners
import app.hovanki.client.ui.common.Toast
import app.hovanki.client.ui.common.formatDateTimeIn
import app.hovanki.client.ui.common.rememberReduceMotion
import app.hovanki.client.ui.common.rememberToastVisible
import app.hovanki.client.ui.settings.BuildingsPanel
import app.hovanki.client.ui.settings.SettingsPanel
import app.hovanki.client.ui.theme.Motion
import app.hovanki.client.ui.theme.Palette
import app.hovanki.client.ui.theme.color
import app.hovanki.client.ui.theme.onColor
import app.hovanki.shared.protocol.BigGameInfo
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.UserId
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
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
    if (state.isHost && viewModel.buildingsPanelIn == state.gameId) {
        BuildingsPanel(state, viewModel)
        return
    }
    if (state.isHost && viewModel.settingsPanelIn == state.gameId) {
        SettingsPanel(state, viewModel)
        return
    }
    if (viewModel.mapPanelIn == state.gameId) {
        LobbyMapPanel(state, viewModel)
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
                val bigGame = state.bigGame
                if (bigGame != null) {
                    BigGameCard(bigGame, playersHere = state.playerCount)
                } else {
                    JoinCodeRow(state.joinCode, onCopied = { codeCopies++ })
                    if (!state.isHost) MyRoleRow(isSeeker = state.amSeeker)
                }
                SessionBanners(
                    connectionStatus = state.connectionStatus,
                    isSharingLocation = state.isSharingLocation,
                    error = state.error,
                    onDismissError = viewModel::dismissError,
                    onLocationPermissionGranted = viewModel::onLocationPermissionGranted,
                )
                WherePlayCard(state, onExpand = viewModel::openMap)
                SettingsTiles(state, onOpenSettings = viewModel::openSettings, onOpenBoard = viewModel::openBoard)
                if (state.features.hasRadar) RadarRow(state, viewModel)
                // Everybody is told before the round (docs/adr/0011-spectators-and-recordings.md), guests too. A big
                // game is not recorded.
                if (state.bigGame == null) RecordingLine()
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
                state.crowding?.let { crowding ->
                    CrowdingBanner(crowding, onPlayAnyway = viewModel::playAnyway)
                }
                if (state.bigGame != null) {
                    BigGameFriends(state, isBusy = isBusy, onAddFriend = viewModel::addFriend)
                } else {
                    PlayersSection(state, viewModel, isBusy, reduceMotion, shuffles, initialPlayers)
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
                val bigGame = state.bigGame
                if (bigGame != null) {
                    BigGameCountdown(bigGame.startsAtMillis)
                } else if (state.isHost) {
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

/** The players with their roles: «Random» and the invitations for the host, the list with the role pills. */
@Composable
private fun PlayersSection(
    state: LobbyUiState,
    viewModel: LobbyViewModel,
    isBusy: Boolean,
    reduceMotion: Boolean,
    shuffles: Int,
    initialPlayers: Set<PlayerId>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
    }
}

/**
 * «Leave» on the edge the cards start at, the title in the middle of the screen (not of the room between the buttons),
 * the chat on the right (docs/adr/0014-settings-lobby-redesign-open-buildings.md, «Лобби»).
 */
@Composable
private fun Header(chatUnread: Int, onOpenChat: () -> Unit, onLeave: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
        PopButton(
            onClick = onLeave,
            style = PopStyle.Outline,
            height = 40.dp,
            contentPadding = PaddingValues(start = 8.dp, end = 14.dp),
            modifier = Modifier.align(Alignment.CenterStart).testTag(TestTags.LOBBY_LEAVE),
        ) {
            Icon(painterResource(Res.drawable.ic_back), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(Res.string.action_leave), style = MaterialTheme.typography.labelLarge)
        }
        Text(
            text = stringResource(Res.string.lobby_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
        ChatIconButton(
            unread = chatUnread,
            onClick = onOpenChat,
            size = 44.dp,
            modifier = Modifier.align(Alignment.CenterEnd),
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
 * The host's warning (docs/adr/0010-big-games.md): the zone has room for fewer players than there are, and/or few places
 * to hide. A recommendation: «Play anyway» takes it away for the rest of the game.
 */
@Composable
private fun CrowdingBanner(crowding: Crowding, onPlayAnyway: () -> Unit) {
    val crowded =
        pluralStringResource(Res.plurals.lobby_crowded, crowding.capacity, crowding.capacity, crowding.players)
    val fewCovers = stringResource(Res.string.lobby_few_covers)
    val text = listOfNotNull(crowded.takeIf { crowding.isCrowded }, fewCovers.takeIf { crowding.fewCovers })
        .joinToString(" ")
    Banner(
        text = text,
        isError = true,
        actionLabel = stringResource(Res.string.lobby_play_anyway),
        onAction = onPlayAnyway,
        modifier = Modifier.testTag(TestTags.LOBBY_CROWDED),
        actionModifier = Modifier.testTag(TestTags.LOBBY_PLAY_ANYWAY),
    )
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

/**
 * A big game's lobby (docs/adr/0010-big-games.md): the title on violet, when it starts in the place's time, and how many
 * are here and signed up. No join code: only the signed-up come in, from their «Play» tab.
 */
@Composable
private fun BigGameCard(bigGame: BigGameInfo, playersHere: Int) {
    PopCard(
        modifier = Modifier.fillMaxWidth().testTag(TestTags.BIG_LOBBY),
        color = Palette.Violet,
        borderWidth = 2.5.dp,
        shadow = 5.dp,
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CapsText(stringResource(Res.string.big_games_title), color = Color.White)
        Text(text = bigGame.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Text(
            text = formatDateTimeIn(bigGame.startsAtMillis, bigGame.timeZone),
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
        )
        Text(
            text = stringResource(Res.string.big_lobby_counts, playersHere, bigGame.signedUp),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
    }
}

/** Friends in a big game's lobby (the list of everybody would be hundreds long); the server draws the roles. */
@Composable
private fun BigGameFriends(state: LobbyUiState, isBusy: Boolean, onAddFriend: (UserId) -> Unit) {
    SecondaryText(stringResource(Res.string.big_lobby_roles))
    SectionTitle(stringResource(Res.string.big_lobby_friends))
    if (state.friendsHere.isEmpty()) {
        SecondaryText(stringResource(Res.string.big_lobby_no_friends))
        return
    }
    PopCard(contentPadding = PaddingValues(0.dp), verticalArrangement = Arrangement.Top) {
        state.friendsHere.forEachIndexed { index, player ->
            key(player.id) {
                if (index > 0) HorizontalDivider(color = Palette.Line, thickness = 1.5.dp)
                PlayerRow(
                    player = player,
                    showRadar = state.features.hasRadar,
                    canPickRoles = false,
                    isBusy = isBusy,
                    isNew = false,
                    shuffles = 0,
                    onToggleSeeker = {},
                    onAddFriend = { player.account.userId?.let(onAddFriend) },
                )
            }
        }
    }
}

/** Until the start by the server's clock, every second; then «Starting…» until the round is there. */
@Composable
private fun BigGameCountdown(startsAtMillis: Long) {
    val clock = koinInject<ServerClock>()
    val left by produceState(startsAtMillis - clock.now(), startsAtMillis) {
        while (true) {
            value = startsAtMillis - clock.now()
            delay(COUNTDOWN_TICK_MILLIS)
        }
    }
    Text(
        text = if (left > 0) {
            stringResource(Res.string.big_lobby_starts_in, countdownText(left))
        } else {
            stringResource(Res.string.big_lobby_starting)
        },
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(vertical = 12.dp).testTag(TestTags.BIG_LOBBY_COUNTDOWN),
    )
}

/** «1:05:09», «4:07». */
private fun countdownText(millis: Long): String {
    val seconds = (millis + 999) / 1000
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val rest = (seconds % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$rest" else "$minutes:$rest"
}

private const val COUNTDOWN_TICK_MILLIS = 1_000L

private val DICE_WOBBLE = listOf(-22f, 18f, -10f, 0f)
private const val DICE_STEP_MILLIS = 90
private const val FLICKER_MILLIS = 300
private const val FLICKER_STEP_MILLIS = 60
private const val PILL_POP_FROM = 0.86f
