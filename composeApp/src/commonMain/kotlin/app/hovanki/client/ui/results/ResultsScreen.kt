package app.hovanki.client.ui.results

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.lobby_you
import app.hovanki.client.resources.results_back
import app.hovanki.client.resources.results_caught
import app.hovanki.client.resources.results_eliminated
import app.hovanki.client.resources.results_hiders_win
import app.hovanki.client.resources.results_seekers
import app.hovanki.client.resources.results_seekers_win
import app.hovanki.client.resources.results_survived
import app.hovanki.client.resources.results_title
import app.hovanki.client.resources.results_you_caught
import app.hovanki.client.resources.results_you_eliminated
import app.hovanki.client.resources.results_you_survived
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Final standings. Stateless: the snapshot of a finished game no longer changes. */
@Composable
fun ResultsScreen(snapshot: GameSnapshot, onBackToStart: () -> Unit) {
    val me = snapshot.me
    val hiders = snapshot.players.filter { it.role == Role.HIDER }
    val survivors = hiders.filter { it.status == PlayerStatus.ACTIVE }

    ScreenColumn {
        Text(text = stringResource(Res.string.results_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            text = stringResource(
                if (survivors.isEmpty()) Res.string.results_seekers_win else Res.string.results_hiders_win,
            ),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (me.role == Role.HIDER) {
            val personal = when (me.status) {
                PlayerStatus.ACTIVE -> Res.string.results_you_survived
                PlayerStatus.CAUGHT -> Res.string.results_you_caught
                PlayerStatus.ELIMINATED -> Res.string.results_you_eliminated
            }
            Text(text = stringResource(personal), style = MaterialTheme.typography.bodyLarge)
        }

        PlayerGroup(Res.string.results_survived, survivors, me.playerId)
        PlayerGroup(Res.string.results_caught, hiders.filter { it.status == PlayerStatus.CAUGHT }, me.playerId)
        PlayerGroup(Res.string.results_eliminated, hiders.filter { it.status == PlayerStatus.ELIMINATED }, me.playerId)
        PlayerGroup(Res.string.results_seekers, snapshot.players.filter { it.role == Role.SEEKER }, me.playerId)

        Button(onClick = onBackToStart, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.results_back))
        }
    }
}

@Composable
private fun PlayerGroup(title: StringResource, players: List<PlayerView>, myId: PlayerId) {
    if (players.isEmpty()) return
    val youTag = stringResource(Res.string.lobby_you)
    Text(text = stringResource(title), style = MaterialTheme.typography.titleMedium)
    players.forEach { player ->
        val name = if (player.id == myId) "${player.name} ($youTag)" else player.name
        Text(text = name, style = MaterialTheme.typography.bodyLarge)
    }
}
