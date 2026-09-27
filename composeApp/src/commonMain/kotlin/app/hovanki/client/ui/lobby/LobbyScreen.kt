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
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_dismiss
import app.hovanki.client.resources.action_leave
import app.hovanki.client.resources.building_rule_off
import app.hovanki.client.resources.ic_back
import app.hovanki.client.resources.ic_copy
import app.hovanki.client.resources.ic_dice
import app.hovanki.client.resources.ic_person_add
import app.hovanki.client.resources.invites_sent
import app.hovanki.client.resources.lobby_chip_time
import app.hovanki.client.resources.lobby_chip_zone
import app.hovanki.client.resources.lobby_code_copied
import app.hovanki.client.resources.lobby_code_hint
import app.hovanki.client.resources.lobby_code_title
import app.hovanki.client.resources.lobby_copy_code
import app.hovanki.client.resources.lobby_hider
import app.hovanki.client.resources.lobby_host
import app.hovanki.client.resources.lobby_invite
import app.hovanki.client.resources.lobby_pick_seekers
import app.hovanki.client.resources.lobby_players
import app.hovanki.client.resources.lobby_random
import app.hovanki.client.resources.lobby_seeker
import app.hovanki.client.resources.lobby_start
import app.hovanki.client.resources.lobby_start_hint
import app.hovanki.client.resources.lobby_title
import app.hovanki.client.resources.lobby_waiting
import app.hovanki.client.resources.lobby_you
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.random.Random
import app.hovanki.shared.protocol.Role as GameRole

/**
 * The lobby (docs/design.md, «Лобби»): the join code on a lime card, the game's settings, the players with the host's
 * role pills, and «Start» at the bottom.
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
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val reduceMotion = rememberReduceMotion()
    // «Random»: the dice wobbles and the pills flicker for a moment before the server's answer settles them.
    var shuffles by remember { mutableIntStateOf(0) }
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PopChip(stringResource(Res.string.lobby_chip_zone, state.zoneRadiusMeters))
                    PopChip(stringResource(Res.string.lobby_chip_time, state.hidingMinutes, state.seekingMinutes))
                }
                if (state.isBuildingRuleOff) {
                    Banner(
                        text = stringResource(Res.string.building_rule_off),
                        modifier = Modifier.testTag(TestTags.BUILDING_RULE_OFF),
                    )
                }

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
                            onClick = {
                                shuffles++
                                viewModel.shuffleSeekers()
                            },
                            style = PopStyle.Pink,
                            height = 40.dp,
                            contentPadding = PaddingValues(horizontal = 12.dp),
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
                    SecondaryText(stringResource(Res.string.lobby_start_hint, state.hidingMinutes))
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

@Composable
private fun JoinCodeCard(joinCode: String, onCopied: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
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
 * A player: avatar, name, «you»/«host», «guest» or what they are to the viewer (add as a friend), and for the host a
 * pill with the role: tap it to switch between «hides» and «seeks».
 */
@Composable
private fun PlayerRow(
    player: LobbyPlayer,
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
    val role = if (player.isSeeker) GameRole.SEEKER else GameRole.HIDER
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
        if (canPickRoles) {
            val avatarColor by animateColorAsState(role.color, Motion.fast())
            Avatar(
                name = player.name,
                color = avatarColor,
                contentColor = role.onColor,
                size = 36.dp,
                modifier = avatarModifier,
            )
        } else {
            Avatar(name = player.name, size = 36.dp, modifier = avatarModifier)
        }
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
            PlayerAccountBadge(player.id, player.account, isBusy = isBusy, onAddFriend = onAddFriend)
        }
        if (canPickRoles) {
            RolePill(
                isSeeker = rememberFlicker(shuffles, player.isSeeker, seed = player.id.value.hashCode()),
                onClick = onToggleSeeker,
                modifier = Modifier.testTag(TestTags.seekerSwitch(player.id)),
            )
        }
    }
}

/** «Seeks» in orange or «hides» in violet; the host taps it to switch. */
@Composable
private fun RolePill(isSeeker: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
