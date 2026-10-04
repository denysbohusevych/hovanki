package app.hovanki.client.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.location.rememberLocationPermissionRequester
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_cancel
import app.hovanki.client.resources.action_save
import app.hovanki.client.resources.group_add_confirm
import app.hovanki.client.resources.group_add_members
import app.hovanki.client.resources.group_all_friends_in
import app.hovanki.client.resources.group_delete
import app.hovanki.client.resources.group_delete_confirm
import app.hovanki.client.resources.group_delete_text
import app.hovanki.client.resources.group_leave
import app.hovanki.client.resources.group_leave_owner_hint
import app.hovanki.client.resources.group_members
import app.hovanki.client.resources.group_name_label
import app.hovanki.client.resources.group_owner
import app.hovanki.client.resources.group_play
import app.hovanki.client.resources.group_play_hint
import app.hovanki.client.resources.group_remove_member
import app.hovanki.client.resources.group_rename
import app.hovanki.client.resources.lobby_you
import app.hovanki.client.ui.common.CommandStatus
import app.hovanki.client.ui.common.Panel
import app.hovanki.client.ui.common.PickRow
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.PopTextField
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.SectionTitle
import app.hovanki.client.ui.common.StartStatusBanners
import app.hovanki.client.ui.common.SystemBackHandler
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.protocol.GroupView
import app.hovanki.shared.protocol.UserId
import org.jetbrains.compose.resources.stringResource

/**
 * A group, full screen over the main screen: play with it, its members, and what the owner (add friends, remove
 * members, rename, delete) or a member (leave) can do. Back and the close button return to the list.
 */
@Composable
fun GroupPanel(group: GroupView, state: GroupsUiState, onEvent: (GroupsEvent) -> Unit) {
    Panel(
        title = group.name,
        onClose = { onEvent(GroupsEvent.ClosePanel) },
        modifier = Modifier.testTag(TestTags.GROUP_PANEL),
    ) {
        // A form of the panel goes back to the panel first (added after the panel's own back handler: it wins).
        SystemBackHandler(enabled = state.panelMode != GroupPanelMode.VIEW, onBack = { onEvent(GroupsEvent.Back) })
        ScreenColumn {
            when (state.panelMode) {
                GroupPanelMode.VIEW -> GroupOverview(group, state, onEvent)
                GroupPanelMode.ADD_MEMBERS -> AddMembersForm(group, state, onEvent)
                GroupPanelMode.RENAME -> RenameForm(state, onEvent)
                GroupPanelMode.CONFIRM_DELETE -> ConfirmDelete(group, state, onEvent)
            }
            CommandStatus(
                isBusy = state.isBusy,
                message = state.message,
                onDismiss = { onEvent(GroupsEvent.DismissMessage) },
                errorTag = TestTags.SOCIAL_ERROR,
            )
        }
    }
}

@Composable
private fun GroupOverview(group: GroupView, state: GroupsUiState, onEvent: (GroupsEvent) -> Unit) {
    val isBusy = state.isBusy
    val startStatus = state.startStatus
    val requestLocationThenPlay = rememberLocationPermissionRequester { granted ->
        onEvent(GroupsEvent.PlayWithGroup(granted))
    }
    val isOwner = state.isOwner(group)
    val myId = state.myId

    PopButton(
        text = stringResource(Res.string.group_play),
        onClick = requestLocationThenPlay,
        enabled = !isBusy && !startStatus.isBusy,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.GROUP_PLAY),
    )
    SecondaryText(stringResource(Res.string.group_play_hint))
    StartStatusBanners(
        status = startStatus,
        sessionError = state.sessionError,
        onDismiss = { onEvent(GroupsEvent.DismissMessage) },
        onLeaveOtherGame = { onEvent(GroupsEvent.LeaveOtherGameAndRetry) },
    )
    HorizontalDivider()

    SectionTitle(stringResource(Res.string.group_members, group.members.size))
    group.members.forEach { member ->
        MemberRow(
            nickname = member.nickname,
            isOwner = member.id == group.ownerId,
            isMe = member.id == myId,
            canRemove = isOwner && member.id != myId,
            isBusy = isBusy,
            userId = member.id,
            onRemove = { onEvent(GroupsEvent.RemoveMember(member.id)) },
        )
    }
    HorizontalDivider()

    if (isOwner) {
        PopButton(
            text = stringResource(Res.string.group_add_members),
            onClick = { onEvent(GroupsEvent.StartAdding) },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.GROUP_ADD_MEMBERS),
            style = PopStyle.Outline,
        )
        PopButton(
            text = stringResource(Res.string.group_rename),
            onClick = { onEvent(GroupsEvent.StartRenaming(group)) },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.GROUP_RENAME),
            style = PopStyle.Outline,
        )
    }
    PopButton(
        text = stringResource(Res.string.group_leave),
        onClick = { onEvent(GroupsEvent.Leave) },
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.GROUP_LEAVE),
        style = PopStyle.Outline,
    )
    if (isOwner) {
        SecondaryText(stringResource(Res.string.group_leave_owner_hint))
        TextButton(
            onClick = { onEvent(GroupsEvent.AskToDelete) },
            enabled = !isBusy,
            colors = ButtonDefaults.textButtonColors(contentColor = Palette.PinkInk),
            modifier = Modifier.fillMaxWidth().testTag(TestTags.GROUP_DELETE),
        ) {
            Text(stringResource(Res.string.group_delete))
        }
    }
}

@Composable
private fun MemberRow(
    nickname: String,
    isOwner: Boolean,
    isMe: Boolean,
    canRemove: Boolean,
    isBusy: Boolean,
    userId: UserId,
    onRemove: () -> Unit,
) {
    val labels = listOfNotNull(
        stringResource(Res.string.lobby_you).takeIf { isMe },
        stringResource(Res.string.group_owner).takeIf { isOwner },
    )
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = nickname, style = MaterialTheme.typography.bodyLarge)
            if (labels.isNotEmpty()) SecondaryText(labels.joinToString())
        }
        if (canRemove) {
            TextButton(
                onClick = onRemove,
                enabled = !isBusy,
                modifier = Modifier.testTag(TestTags.groupMemberRemove(userId)),
            ) {
                Text(stringResource(Res.string.group_remove_member))
            }
        }
    }
}

/** Owner: friends who are not in the group yet, to pick. */
@Composable
private fun AddMembersForm(group: GroupView, state: GroupsUiState, onEvent: (GroupsEvent) -> Unit) {
    val isBusy = state.isBusy
    val candidates = state.candidates(group)

    SectionTitle(stringResource(Res.string.group_add_members))
    if (candidates.isEmpty()) SecondaryText(stringResource(Res.string.group_all_friends_in))
    candidates.forEach { friend ->
        PickRow(
            title = friend.nickname,
            checked = friend.id in state.picked,
            onCheckedChange = { onEvent(GroupsEvent.TogglePick(friend.id)) },
            enabled = !isBusy,
            modifier = Modifier.testTag(TestTags.groupPick(friend.id)),
        )
    }
    FormButtons(
        confirm = stringResource(Res.string.group_add_confirm),
        confirmTag = TestTags.GROUP_ADD_CONFIRM,
        enabled = !isBusy && state.picked.isNotEmpty(),
        onConfirm = { onEvent(GroupsEvent.AddMembers) },
        onCancel = { onEvent(GroupsEvent.Back) },
    )
}

@Composable
private fun RenameForm(state: GroupsUiState, onEvent: (GroupsEvent) -> Unit) {
    val isBusy = state.isBusy
    PopTextField(
        value = state.name,
        onValueChange = { onEvent(GroupsEvent.NameChanged(it)) },
        label = { Text(stringResource(Res.string.group_name_label)) },
        singleLine = true,
        enabled = !isBusy,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onEvent(GroupsEvent.SaveName) }),
        modifier = Modifier.fillMaxWidth().testTag(TestTags.GROUP_NAME),
    )
    FormButtons(
        confirm = stringResource(Res.string.action_save),
        confirmTag = TestTags.GROUP_SAVE_NAME,
        enabled = !isBusy,
        onConfirm = { onEvent(GroupsEvent.SaveName) },
        onCancel = { onEvent(GroupsEvent.Back) },
    )
}

@Composable
private fun ConfirmDelete(group: GroupView, state: GroupsUiState, onEvent: (GroupsEvent) -> Unit) {
    val isBusy = state.isBusy
    Text(
        text = stringResource(Res.string.group_delete_text, group.name),
        style = MaterialTheme.typography.bodyLarge,
    )
    FormButtons(
        confirm = stringResource(Res.string.group_delete_confirm),
        confirmTag = TestTags.GROUP_DELETE_CONFIRM,
        enabled = !isBusy,
        isDestructive = true,
        onConfirm = { onEvent(GroupsEvent.Delete) },
        onCancel = { onEvent(GroupsEvent.Back) },
    )
}

@Composable
private fun FormButtons(
    confirm: String,
    confirmTag: String,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    isDestructive: Boolean = false,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PopButton(
            text = confirm,
            onClick = onConfirm,
            enabled = enabled,
            style = if (isDestructive) PopStyle.Danger else PopStyle.Primary,
            modifier = Modifier.testTag(confirmTag),
        )
        TextButton(onClick = onCancel) {
            Text(stringResource(Res.string.action_cancel))
        }
    }
}
