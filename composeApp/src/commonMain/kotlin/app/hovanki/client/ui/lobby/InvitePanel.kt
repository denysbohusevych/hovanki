package app.hovanki.client.ui.lobby

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_dismiss
import app.hovanki.client.resources.group_members
import app.hovanki.client.resources.invite_friends
import app.hovanki.client.resources.invite_groups
import app.hovanki.client.resources.invite_in_game
import app.hovanki.client.resources.invite_nobody
import app.hovanki.client.resources.invite_send
import app.hovanki.client.resources.invite_title
import app.hovanki.client.resources.working
import app.hovanki.client.session.SessionError
import app.hovanki.client.ui.common.Banner
import app.hovanki.client.ui.common.BusyRow
import app.hovanki.client.ui.common.Panel
import app.hovanki.client.ui.common.PickRow
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.SectionTitle
import app.hovanki.client.ui.common.describe
import org.jetbrains.compose.resources.stringResource

/**
 * The lobby's invitations, full screen: friends (not those in the game already) and groups to pick, then one tap
 * sends them; they show up in the invitees' inbox until the game starts. Back and the close button return to the
 * lobby.
 */
@Composable
fun InvitePanel(state: LobbyUiState, invite: InvitePanelState, onEvent: (LobbyEvent) -> Unit) {
    val friends = invite.friends
    val groups = invite.groups
    val isSending = invite.isSending
    val friendList = friends?.friends.orEmpty()
    val groupList = groups?.groups.orEmpty()

    Panel(
        title = stringResource(Res.string.invite_title),
        onClose = { onEvent(LobbyEvent.Invite.Close) },
        modifier = Modifier.testTag(TestTags.INVITE_PANEL),
        screen = "invite",
    ) {
        ScreenColumn {
            if (groupList.isNotEmpty()) {
                SectionTitle(stringResource(Res.string.invite_groups))
                groupList.forEach { group ->
                    PickRow(
                        title = group.name,
                        subtitle = stringResource(Res.string.group_members, group.members.size),
                        checked = group.id in invite.pickedGroups,
                        onCheckedChange = { onEvent(LobbyEvent.Invite.ToggleGroup(group.id)) },
                        enabled = !isSending,
                        modifier = Modifier.testTag(TestTags.inviteGroup(group.id)),
                    )
                }
            }
            if (friendList.isNotEmpty()) {
                SectionTitle(stringResource(Res.string.invite_friends))
                friendList.forEach { friend ->
                    val isInGame = friend.id in state.userIdsInGame
                    PickRow(
                        title = friend.nickname,
                        subtitle = if (isInGame) stringResource(Res.string.invite_in_game) else null,
                        checked = friend.id in invite.pickedFriends,
                        onCheckedChange = { onEvent(LobbyEvent.Invite.ToggleFriend(friend.id)) },
                        enabled = !isSending && !isInGame,
                        modifier = Modifier.testTag(TestTags.inviteFriend(friend.id)),
                    )
                }
            }
            when {
                friends == null -> BusyRow(stringResource(Res.string.working))
                friendList.isEmpty() && groupList.isEmpty() -> SecondaryText(stringResource(Res.string.invite_nobody))
            }

            PopButton(
                text = stringResource(Res.string.invite_send),
                onClick = { onEvent(LobbyEvent.Invite.Send) },
                enabled = invite.canSend,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.INVITE_SEND),
            )
            if (isSending) BusyRow(stringResource(Res.string.working))
            state.error?.let { error -> InviteError(error, onDismiss = { onEvent(LobbyEvent.DismissError) }) }
        }
    }
}

/** Why the invitations did not go out (not friends any more, the game started...). */
@Composable
private fun InviteError(error: SessionError, onDismiss: () -> Unit) {
    Banner(
        text = error.describe(),
        modifier = Modifier.testTag(TestTags.BANNER_ERROR),
        isError = true,
        actionLabel = stringResource(Res.string.action_dismiss),
        onAction = onDismiss,
    )
}
