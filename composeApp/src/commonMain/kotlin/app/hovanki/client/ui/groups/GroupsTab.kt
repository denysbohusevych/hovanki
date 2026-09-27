package app.hovanki.client.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.hovanki.client.resources.ic_arrow_right
import app.hovanki.client.resources.working
import app.hovanki.client.ui.common.BusyRow
import app.hovanki.client.ui.common.CommandStatus
import app.hovanki.client.ui.common.PickRow
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopSurface
import app.hovanki.client.ui.common.PopTextField
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.SectionTitle
import app.hovanki.client.ui.common.SystemBackHandler
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.protocol.GroupView
import app.hovanki.shared.protocol.UserSummary
import org.jetbrains.compose.resources.painterResource
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
            PopButton(
                text = stringResource(Res.string.group_new),
                onClick = viewModel::startCreating,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.GROUP_NEW),
            )
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
    PopTextField(
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
        PopButton(
            text = stringResource(Res.string.group_create),
            onClick = viewModel::create,
            enabled = !isBusy,
            modifier = Modifier.testTag(TestTags.GROUP_CREATE),
        )
        TextButton(onClick = viewModel::cancelCreating, enabled = !isBusy) {
            Text(stringResource(Res.string.action_cancel))
        }
    }
}

@Composable
private fun GroupCard(group: GroupView, isMine: Boolean, onOpen: () -> Unit) {
    PopSurface(
        onClick = onOpen,
        shadow = 4.dp,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.group(group.id)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GroupTile(group.name)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = group.name, style = MaterialTheme.typography.titleMedium)
                val members = stringResource(Res.string.group_members, group.members.size)
                SecondaryText(if (isMine) "$members · ${stringResource(Res.string.group_owner)}" else members)
            }
            Icon(
                painter = painterResource(Res.drawable.ic_arrow_right),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** A group's square: the first letters of its name on a color picked by the name. */
@Composable
private fun GroupTile(name: String) {
    val colors = listOf(Palette.Lime, Palette.Violet, Palette.Orange, Palette.Pink)
    val color = colors[(name.hashCode() and Int.MAX_VALUE) % colors.size]
    val initials = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(color)
            .border(2.dp, Palette.Ink, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials.ifEmpty { "?" },
            style = MaterialTheme.typography.titleLarge,
            color = if (color == Palette.Violet) Palette.Paper else Palette.Ink,
        )
    }
}
