package app.hovanki.client.ui.friends

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_accept
import app.hovanki.client.resources.action_decline
import app.hovanki.client.resources.friends_add
import app.hovanki.client.resources.friends_add_title
import app.hovanki.client.resources.friends_block
import app.hovanki.client.resources.friends_block_hint
import app.hovanki.client.resources.friends_blocked
import app.hovanki.client.resources.friends_empty
import app.hovanki.client.resources.friends_incoming
import app.hovanki.client.resources.friends_list
import app.hovanki.client.resources.friends_nickname_label
import app.hovanki.client.resources.friends_outgoing
import app.hovanki.client.resources.friends_remove
import app.hovanki.client.resources.friends_unblock
import app.hovanki.client.resources.friends_withdraw
import app.hovanki.client.resources.working
import app.hovanki.client.ui.common.Avatar
import app.hovanki.client.ui.common.BusyRow
import app.hovanki.client.ui.common.CommandStatus
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.PopTextField
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.SectionTitle
import app.hovanki.shared.protocol.FriendsResponse
import app.hovanki.shared.protocol.UserSummary
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** «Friends»: add by nickname, requests both ways, friends (tap one to remove or block), blocked users. */
@Composable
fun FriendsTab(viewModel: FriendsViewModel = koinViewModel()) {
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    ScreenColumn(modifier = Modifier.testTag(TestTags.FRIENDS_SCREEN)) {
        Text(text = stringResource(Res.string.friends_add_title), style = MaterialTheme.typography.titleMedium)
        PopTextField(
            value = viewModel.nickname,
            onValueChange = viewModel::onNicknameChange,
            label = { Text(stringResource(Res.string.friends_nickname_label)) },
            singleLine = true,
            enabled = !isBusy,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { viewModel.sendRequest() }),
            modifier = Modifier.fillMaxWidth().testTag(TestTags.FRIENDS_NICKNAME),
        )
        PopButton(
            text = stringResource(Res.string.friends_add),
            onClick = viewModel::sendRequest,
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.FRIENDS_ADD),
        )
        CommandStatus(
            isBusy = isBusy,
            message = message,
            onDismiss = viewModel::dismissMessage,
            errorTag = TestTags.SOCIAL_ERROR,
            infoTag = TestTags.SOCIAL_INFO,
        )

        val current = friends
        if (current == null) {
            BusyRow(stringResource(Res.string.working))
        } else {
            FriendLists(current, viewModel, isBusy)
        }
    }
}

/** Requests to the player, friends, the player's own requests and blocked users. */
@Composable
private fun FriendLists(current: FriendsResponse, viewModel: FriendsViewModel, isBusy: Boolean) {
    if (current.incoming.isNotEmpty()) {
        SectionTitle(stringResource(Res.string.friends_incoming))
        current.incoming.forEach { user ->
            PersonRow(user) {
                TextButton(
                    onClick = { viewModel.accept(user) },
                    enabled = !isBusy,
                    modifier = Modifier.testTag(TestTags.friendAccept(user.id)),
                ) {
                    Text(stringResource(Res.string.action_accept))
                }
                TextButton(
                    onClick = { viewModel.decline(user) },
                    enabled = !isBusy,
                    modifier = Modifier.testTag(TestTags.friendDecline(user.id)),
                ) {
                    Text(stringResource(Res.string.action_decline))
                }
            }
        }
    }

    SectionTitle(stringResource(Res.string.friends_list))
    if (current.friends.isEmpty()) SecondaryText(stringResource(Res.string.friends_empty))
    current.friends.forEach { user ->
        FriendRow(
            user = user,
            isExpanded = viewModel.expanded == user.id,
            isBusy = isBusy,
            onToggle = { viewModel.toggle(user) },
            onRemove = { viewModel.remove(user) },
            onBlock = { viewModel.block(user) },
        )
    }

    if (current.outgoing.isNotEmpty()) {
        SectionTitle(stringResource(Res.string.friends_outgoing))
        current.outgoing.forEach { user ->
            PersonRow(user) {
                TextButton(
                    onClick = { viewModel.decline(user) },
                    enabled = !isBusy,
                    modifier = Modifier.testTag(TestTags.friendCancel(user.id)),
                ) {
                    Text(stringResource(Res.string.friends_withdraw))
                }
            }
        }
    }

    if (current.blocked.isNotEmpty()) {
        SectionTitle(stringResource(Res.string.friends_blocked))
        current.blocked.forEach { user ->
            PersonRow(user) {
                TextButton(
                    onClick = { viewModel.unblock(user) },
                    enabled = !isBusy,
                    modifier = Modifier.testTag(TestTags.friendUnblock(user.id)),
                ) {
                    Text(stringResource(Res.string.friends_unblock))
                }
            }
        }
    }
}

/** A friend; tapping the row shows what can be done: remove, block. */
@Composable
private fun FriendRow(
    user: UserSummary,
    isExpanded: Boolean,
    isBusy: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onBlock: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PersonRow(user, modifier = Modifier.clickable(onClick = onToggle))
        if (isExpanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PopButton(
                        text = stringResource(Res.string.friends_remove),
                        onClick = onRemove,
                        enabled = !isBusy,
                        modifier = Modifier.testTag(TestTags.friendRemove(user.id)),
                        style = PopStyle.Outline,
                    )
                    PopButton(
                        text = stringResource(Res.string.friends_block),
                        onClick = onBlock,
                        enabled = !isBusy,
                        modifier = Modifier.testTag(TestTags.friendBlock(user.id)),
                        style = PopStyle.Danger,
                    )
                }
                SecondaryText(stringResource(Res.string.friends_block_hint))
            }
        }
    }
}

/** A user's nickname with [actions] on the right. */
@Composable
private fun PersonRow(
    user: UserSummary,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().testTag(TestTags.friend(user.id)).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = user.nickname, size = 36.dp, modifier = Modifier.padding(end = 12.dp))
        Text(text = user.nickname, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        actions()
    }
}
