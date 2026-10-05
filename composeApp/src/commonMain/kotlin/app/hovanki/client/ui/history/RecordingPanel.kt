package app.hovanki.client.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.lobby_hider
import app.hovanki.client.resources.lobby_seeker
import app.hovanki.client.resources.recording_empty
import app.hovanki.client.resources.recording_expires
import app.hovanki.client.resources.recording_title
import app.hovanki.client.resources.recording_you
import app.hovanki.client.resources.spectator_caught
import app.hovanki.client.resources.spectator_out
import app.hovanki.client.session.Replay
import app.hovanki.client.ui.common.CommandStatus
import app.hovanki.client.ui.common.Panel
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopChip
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.formatDate
import app.hovanki.client.ui.common.rememberReduceMotion
import app.hovanki.client.ui.game.ZoneTimeline
import app.hovanki.client.ui.results.ReplayCard
import app.hovanki.client.ui.theme.Palette
import app.hovanki.client.ui.theme.color
import app.hovanki.client.ui.theme.onColor
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.RecordedPlayer
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.rules.StreetZone
import org.jetbrains.compose.resources.stringResource

/**
 * A game's recording from the history (docs/adr/0011-spectators-and-recordings.md): everybody's way through the round
 * on the replay's map, with its slider, who played what and how it ended for them, and until when it is kept. Only
 * the game's players with an account get it from the server.
 */
@Composable
fun RecordingPanel(state: HistoryUiState, open: OpenRecording, onEvent: (HistoryEvent) -> Unit) {
    val recording = open.recording
    val replay = remember(recording) { Replay.of(recording) }
    val streets = remember(recording) {
        recording.streetZone?.takeIf { it.isNotEmpty() }?.let { runCatching { StreetZone(it) }.getOrNull() }
    }
    val reduceMotion = rememberReduceMotion()
    Panel(
        title = "${stringResource(Res.string.recording_title)} · ${formatDate(recording.finishedAtMillis)}",
        onClose = { onEvent(HistoryEvent.CloseRecording) },
        modifier = Modifier.testTag(TestTags.RECORDING_PANEL),
    ) {
        ScreenColumn {
            if (replay == null) {
                SecondaryText(stringResource(Res.string.recording_empty))
            } else {
                ReplayCard(
                    replay = replay,
                    zone = ZoneTimeline(recording.zone, recording.zoneStartedAtMillis, streets),
                    hidingStartMillis = recording.startedAtMillis,
                    reduceMotion = reduceMotion,
                    mapHeight = 380.dp,
                )
            }
            PopCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // Seekers first, then the hiders; within them as the server sent them.
                val players = recording.players.sortedBy { if (it.role == Role.SEEKER) 0 else 1 }
                players.forEachIndexed { index, player ->
                    if (index > 0) HorizontalDivider(color = Palette.Line, thickness = 1.5.dp)
                    RecordedPlayerRow(player)
                }
            }
            SecondaryText(stringResource(Res.string.recording_expires, formatDate(recording.expiresAtMillis)))
            CommandStatus(
                isBusy = state.isBusy,
                message = state.message,
                onDismiss = { onEvent(HistoryEvent.DismissMessage) },
            )
        }
    }
}

@Composable
private fun RecordedPlayerRow(player: RecordedPlayer) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (player.isMe) stringResource(Res.string.recording_you, player.name) else player.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        when (player.status) {
            PlayerStatus.ACTIVE -> Unit
            PlayerStatus.CAUGHT -> SecondaryText(stringResource(Res.string.spectator_caught))
            PlayerStatus.ELIMINATED -> SecondaryText(stringResource(Res.string.spectator_out))
        }
        PopChip(
            text = stringResource(if (player.role == Role.HIDER) Res.string.lobby_hider else Res.string.lobby_seeker),
            color = player.role.color,
            contentColor = player.role.onColor,
            border = Palette.Ink,
        )
    }
}
