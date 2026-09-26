package app.hovanki.client.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_cancel
import app.hovanki.client.resources.group_create
import app.hovanki.client.resources.group_members
import app.hovanki.client.resources.group_name_label
import app.hovanki.client.resources.group_new
import app.hovanki.client.resources.group_no_friends
import app.hovanki.client.resources.group_owner
import app.hovanki.client.resources.group_pick_friends
import app.hovanki.client.resources.groups_empty
import app.hovanki.client.resources.groups_hint
import app.hovanki.client.resources.working
import app.hovanki.client.ui.common.BusyRow
import app.hovanki.client.ui.common.CommandStatus
import app.hovanki.client.ui.common.PickRow
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.SectionTitle
import app.hovanki.client.ui.common.SystemBackHandler
import app.hovanki.shared.protocol.GroupView
import app.hovanki.shared.protocol.UserSummary
import org.jetbrains.compose.resources.stringResource

/** «Groups»: the player's groups (tap one for its panel) and a form for a new one. */
@Composable
fun GroupsTab(viewModel: GroupsViewModel) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    SystemBackHandler(enabled = viewModel.isCreating, onBack = viewModel::cancelCreating)

    ScreenColumn(modifier = Modifier.testTag(TestTags.GROUPS_SCREEN)) {
        SecondaryText(stringResource(Res.string.groups_hint))
        if (viewModel.isCreating) {
            NewGroupForm(viewModel, friends?.friends.orEmpty(), isBusy)
        } else {
            Button(
                onClick = viewModel::startCreating,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.GROUP_NEW),
            ) {
                Text(stringResource(Res.string.group_new))
            }
        }
        CommandStatus(
            isBusy = isBusy,
            message = message,
            onDismiss = viewModel::dismissMessage,
            errorTag = TestTags.SOCIAL_ERROR,
        )

        val loaded = groups
        when {
            loaded == null -> BusyRow(stringResource(Res.string.working))

            loaded.groups.isEmpty() -> SecondaryText(stringResource(Res.string.groups_empty))

            else -> loaded.groups.forEach { group ->
                GroupCard(group, isMine = viewModel.isOwner(group), onOpen = { viewModel.open(group.id) })
            }
        }
    }
}

@Composable
private fun NewGroupForm(viewModel: GroupsViewModel, friends: List<UserSummary>, isBusy: Boolean) {
    OutlinedTextField(
        value = viewModel.name,
        onValueChange = viewModel::onNameChange,
        label = { Text(stringResource(Res.string.group_name_label)) },
        singleLine = true,
        enabled = !isBusy,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth().testTag(TestTags.GROUP_NAME),
    )
    SectionTitle(stringResource(Res.string.group_pick_friends))
    if (friends.isEmpty()) SecondaryText(stringResource(Res.string.group_no_friends))
    friends.forEach { friend ->
        PickRow(
            title = friend.nickname,
            checked = friend.id in viewModel.picked,
            onCheckedChange = { viewModel.togglePick(friend.id) },
            enabled = !isBusy,
            modifier = Modifier.testTag(TestTags.groupPick(friend.id)),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = viewModel::create,
            enabled = !isBusy,
            modifier = Modifier.testTag(TestTags.GROUP_CREATE),
        ) {
            Text(stringResource(Res.string.group_create))
        }
        TextButton(onClick = viewModel::cancelCreating, enabled = !isBusy) {
            Text(stringResource(Res.string.action_cancel))
        }
    }
}

@Composable
private fun GroupCard(group: GroupView, isMine: Boolean, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag(TestTags.group(group.id))) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = group.name, style = MaterialTheme.typography.titleMedium)
            val members = stringResource(Res.string.group_members, group.members.size)
            SecondaryText(if (isMine) "$members · ${stringResource(Res.string.group_owner)}" else members)
        }
    }
}
