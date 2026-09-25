package app.hovanki.client.ui.lobby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_leave
import app.hovanki.client.resources.lobby_code_hint
import app.hovanki.client.resources.lobby_code_title
import app.hovanki.client.resources.lobby_host
import app.hovanki.client.resources.lobby_pick_seekers
import app.hovanki.client.resources.lobby_players
import app.hovanki.client.resources.lobby_seeker
import app.hovanki.client.resources.lobby_start
import app.hovanki.client.resources.lobby_waiting
import app.hovanki.client.resources.lobby_you
import app.hovanki.client.ui.common.LoadingScreen
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SessionBanners
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LobbyScreen(viewModel: LobbyViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val state = uiState
    if (state == null) {
        LoadingScreen()
        return
    }

    ScreenColumn {
        JoinCodeCard(state.joinCode)
        SessionBanners(
            connectionStatus = state.connectionStatus,
            isSharingLocation = state.isSharingLocation,
            error = state.error,
            onDismissError = viewModel::dismissError,
            onLocationPermissionGranted = viewModel::onLocationPermissionGranted,
        )

        Text(
            text = stringResource(Res.string.lobby_players, state.players.size),
            style = MaterialTheme.typography.titleMedium,
        )
        if (state.isHost) {
            Text(
                text = stringResource(Res.string.lobby_pick_seekers),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.players.forEach { player ->
            PlayerRow(
                player = player,
                canPickRoles = state.isHost,
                onToggleSeeker = { viewModel.toggleSeeker(player.id) },
            )
        }

        if (state.isHost) {
            Button(
                onClick = viewModel::start,
                enabled = state.canStart && !state.isStarting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.lobby_start))
            }
        } else {
            Text(text = stringResource(Res.string.lobby_waiting), style = MaterialTheme.typography.bodyLarge)
        }
        TextButton(onClick = viewModel::leave, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.action_leave))
        }
    }
}

@Composable
private fun JoinCodeCard(joinCode: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = stringResource(Res.string.lobby_code_title), style = MaterialTheme.typography.labelLarge)
            Text(
                text = joinCode,
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp,
            )
            Text(
                text = stringResource(Res.string.lobby_code_hint),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PlayerRow(player: LobbyPlayer, canPickRoles: Boolean, onToggleSeeker: () -> Unit) {
    val youTag = stringResource(Res.string.lobby_you)
    val hostTag = stringResource(Res.string.lobby_host)
    val tags = listOfNotNull(youTag.takeIf { player.isMe }, hostTag.takeIf { player.isHost })
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = player.name, style = MaterialTheme.typography.bodyLarge)
            if (tags.isNotEmpty()) {
                Text(
                    text = tags.joinToString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (canPickRoles) {
            Text(
                text = stringResource(Res.string.lobby_seeker),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(end = 8.dp),
            )
            Switch(checked = player.isSeeker, onCheckedChange = { onToggleSeeker() })
        }
    }
}
