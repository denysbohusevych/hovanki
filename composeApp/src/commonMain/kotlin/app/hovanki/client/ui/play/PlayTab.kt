package app.hovanki.client.ui.play

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.automation.TestTags
import app.hovanki.client.location.rememberLocationPermissionRequester
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.home_code_label
import app.hovanki.client.resources.home_create
import app.hovanki.client.resources.home_create_hint
import app.hovanki.client.resources.home_join
import app.hovanki.client.resources.home_location_note
import app.hovanki.client.resources.home_or_join
import app.hovanki.client.resources.invite_accept
import app.hovanki.client.resources.invite_dismiss
import app.hovanki.client.resources.invite_from
import app.hovanki.client.resources.invite_from_group
import app.hovanki.client.resources.invites_title
import app.hovanki.client.resources.play_hello
import app.hovanki.client.ui.common.CommandStatus
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.StartStatusBanners
import app.hovanki.shared.protocol.GameInvite
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** «Play»: invites first, then create a game here or join one by its code. */
@Composable
fun PlayTab(invites: List<GameInvite>, viewModel: PlayViewModel = koinViewModel()) {
    val account by viewModel.accountState.collectAsStateWithLifecycle()
    val status by viewModel.startStatus.collectAsStateWithLifecycle()
    val sessionError by viewModel.sessionError.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val requestLocationThenCreate = rememberLocationPermissionRequester { granted -> viewModel.createGame(granted) }
    // Asked before joining as well, so location is already on when the round starts.
    val requestLocationThenJoin = rememberLocationPermissionRequester { viewModel.joinGame() }
    var acceptedInvite by remember { mutableStateOf<GameInvite?>(null) }
    val requestLocationThenAccept = rememberLocationPermissionRequester {
        acceptedInvite?.let(viewModel::acceptInvite)
    }
    val isBusy = status.isBusy

    ScreenColumn(modifier = Modifier.testTag(TestTags.HOME_SCREEN)) {
        account.user?.let { user ->
            Text(
                text = stringResource(Res.string.play_hello, user.nickname),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        if (invites.isNotEmpty()) {
            Text(text = stringResource(Res.string.invites_title), style = MaterialTheme.typography.titleMedium)
            invites.forEach { invite ->
                InviteCard(
                    invite = invite,
                    isBusy = isBusy,
                    onAccept = {
                        acceptedInvite = invite
                        requestLocationThenAccept()
                    },
                    onDismiss = { viewModel.dismissInvite(invite) },
                )
            }
            HorizontalDivider()
        }

        Button(
            onClick = requestLocationThenCreate,
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.HOME_CREATE),
        ) {
            Text(stringResource(Res.string.home_create))
        }
        SecondaryText(stringResource(Res.string.home_create_hint))

        HorizontalDivider()
        Text(text = stringResource(Res.string.home_or_join), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = viewModel.joinCode,
            onValueChange = viewModel::onJoinCodeChange,
            label = { Text(stringResource(Res.string.home_code_label)) },
            singleLine = true,
            enabled = !isBusy,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
            ),
            modifier = Modifier.fillMaxWidth().testTag(TestTags.HOME_JOIN_CODE),
        )
        OutlinedButton(
            onClick = { if (viewModel.canJoinGame()) requestLocationThenJoin() },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.HOME_JOIN),
        ) {
            Text(stringResource(Res.string.home_join))
        }
        SecondaryText(stringResource(Res.string.home_location_note))

        StartStatusBanners(status = status, sessionError = sessionError, onDismiss = viewModel::dismissProblems)
        CommandStatus(
            isBusy = false,
            message = message,
            onDismiss = viewModel::dismissProblems,
            errorTag = TestTags.SOCIAL_ERROR,
        )
    }
}

/** An invite into a game in its lobby: join it, or hide the invite. */
@Composable
private fun InviteCard(invite: GameInvite, isBusy: Boolean, onAccept: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag(TestTags.invite(invite.joinCode)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val groupName = invite.groupName
            Text(
                text = if (groupName != null) {
                    stringResource(Res.string.invite_from_group, invite.from.nickname, groupName)
                } else {
                    stringResource(Res.string.invite_from, invite.from.nickname)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onAccept,
                    enabled = !isBusy,
                    modifier = Modifier.testTag(TestTags.inviteAccept(invite.joinCode)),
                ) {
                    Text(stringResource(Res.string.invite_accept))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(TestTags.inviteDismiss(invite.joinCode))) {
                    Text(stringResource(Res.string.invite_dismiss))
                }
            }
        }
    }
}
