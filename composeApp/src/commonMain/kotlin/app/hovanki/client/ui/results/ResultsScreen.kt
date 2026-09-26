package app.hovanki.client.ui.results

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.automation.TestTags
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
import app.hovanki.client.ui.common.CommandStatus
import app.hovanki.client.ui.common.PlayerAccount
import app.hovanki.client.ui.common.PlayerAccountBadge
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.UserId
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** Final standings of the finished game ([snapshot] no longer changes); players to add as friends. */
@Composable
fun ResultsScreen(snapshot: GameSnapshot, viewModel: ResultsViewModel = koinViewModel()) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val me = snapshot.me
    val hiders = snapshot.players.filter { it.role == Role.HIDER }
    val survivors = hiders.filter { it.status == PlayerStatus.ACTIVE }
    val list = PlayerList(myId = me.playerId, accounts = accounts, isBusy = isBusy, onAddFriend = viewModel::addFriend)

    ScreenColumn(modifier = Modifier.testTag(TestTags.RESULTS_SCREEN)) {
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

        PlayerGroup(Res.string.results_survived, survivors, list)
        PlayerGroup(Res.string.results_caught, hiders.filter { it.status == PlayerStatus.CAUGHT }, list)
        PlayerGroup(Res.string.results_eliminated, hiders.filter { it.status == PlayerStatus.ELIMINATED }, list)
        PlayerGroup(Res.string.results_seekers, snapshot.players.filter { it.role == Role.SEEKER }, list)
        CommandStatus(
            isBusy = false,
            message = message,
            onDismiss = viewModel::dismissMessage,
            errorTag = TestTags.SOCIAL_ERROR,
        )

        Button(onClick = viewModel::leave, modifier = Modifier.fillMaxWidth().testTag(TestTags.RESULTS_BACK)) {
            Text(stringResource(Res.string.results_back))
        }
    }
}

/** How the players are shown in each group: the viewer marked, accounts to add as friends. */
private class PlayerList(
    val myId: PlayerId,
    val accounts: Map<PlayerId, PlayerAccount>,
    val isBusy: Boolean,
    val onAddFriend: (UserId) -> Unit,
)

@Composable
private fun PlayerGroup(title: StringResource, players: List<PlayerView>, list: PlayerList) {
    if (players.isEmpty()) return
    val youTag = stringResource(Res.string.lobby_you)
    Text(text = stringResource(title), style = MaterialTheme.typography.titleMedium)
    players.forEach { player ->
        Column(modifier = Modifier.fillMaxWidth()) {
            val name = if (player.id == list.myId) "${player.name} ($youTag)" else player.name
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            list.accounts[player.id]?.let { account ->
                PlayerAccountBadge(
                    playerId = player.id,
                    account = account,
                    isBusy = list.isBusy,
                    onAddFriend = { account.userId?.let(list.onAddFriend) },
                )
            }
        }
    }
}
