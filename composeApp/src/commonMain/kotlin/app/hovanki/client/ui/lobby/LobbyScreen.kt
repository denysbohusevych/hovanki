package app.hovanki.client.ui.lobby

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import app.hovanki.client.resources.ic_dice
import app.hovanki.client.resources.ic_person_add
import app.hovanki.client.resources.invites_sent
import app.hovanki.client.resources.lobby_chip_time
import app.hovanki.client.resources.lobby_chip_zone
import app.hovanki.client.resources.lobby_code_hint
import app.hovanki.client.resources.lobby_code_title
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
import app.hovanki.client.ui.theme.Hovanki
import app.hovanki.client.ui.theme.Motion
import app.hovanki.client.ui.theme.Palette
import app.hovanki.client.ui.theme.color
import app.hovanki.client.ui.theme.onColor
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
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

    Column(modifier = Modifier.fillMaxSize().testTag(TestTags.LOBBY_SCREEN)) {
        ScreenColumn(modifier = Modifier.weight(1f)) {
            Header(chatUnread = chatState.unread, onOpenChat = chat::open, onLeave = viewModel::leave)
            JoinCodeCard(state.joinCode)
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
                        onClick = viewModel::shuffleSeekers,
                        style = PopStyle.Pink,
                        height = 40.dp,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(painterResource(Res.drawable.ic_dice), contentDescription = null, Modifier.size(18.dp))
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
                    if (index > 0) HorizontalDivider(color = Palette.Line, thickness = 1.5.dp)
                    PlayerRow(
                        player = player,
                        canPickRoles = state.isHost,
                        isBusy = isBusy,
                        onToggleSeeker = { viewModel.toggleSeeker(player.id) },
                        onAddFriend = { player.account.userId?.let(viewModel::addFriend) },
                    )
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
private fun JoinCodeCard(joinCode: String) {
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
        if (canPickRoles) {
            val avatarColor by animateColorAsState(role.color, Motion.fast())
            Avatar(name = player.name, color = avatarColor, contentColor = role.onColor, size = 36.dp)
        } else {
            Avatar(name = player.name, size = 36.dp)
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
                isSeeker = player.isSeeker,
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
    PopSurface(
        modifier = modifier.height(36.dp).width(104.dp),
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
