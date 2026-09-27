package app.hovanki.client.ui.results

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.ic_home
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
import app.hovanki.client.ui.chat.ChatIconButton
import app.hovanki.client.ui.chat.ChatPanel
import app.hovanki.client.ui.chat.ChatViewModel
import app.hovanki.client.ui.common.Avatar
import app.hovanki.client.ui.common.CapsText
import app.hovanki.client.ui.common.CommandStatus
import app.hovanki.client.ui.common.PlayerAccount
import app.hovanki.client.ui.common.PlayerAccountBadge
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.theme.Motion
import app.hovanki.client.ui.theme.Palette
import app.hovanki.client.ui.theme.color
import app.hovanki.client.ui.theme.onColor
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.UserId
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Final standings of the finished game ([snapshot] no longer changes) on lime (docs/design.md, «Итоги»), players to
 * add as friends, and the chat: the game keeps polling for it until the player goes back to the start.
 */
@Composable
fun ResultsScreen(
    snapshot: GameSnapshot,
    viewModel: ResultsViewModel = koinViewModel(),
    chat: ChatViewModel = koinViewModel(),
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val chatState by chat.uiState.collectAsStateWithLifecycle()
    if (chatState.isOpen) {
        ChatPanel(chat)
        return
    }
    val me = snapshot.me
    val hiders = snapshot.players.filter { it.role == Role.HIDER }
    val survivors = hiders.filter { it.status == PlayerStatus.ACTIVE }
    val list = PlayerList(myId = me.playerId, accounts = accounts, isBusy = isBusy, onAddFriend = viewModel::addFriend)
    // The title pops in once, when the results open.
    val pop = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, Motion.pop()) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenColumn(modifier = Modifier.weight(1f).testTag(TestTags.RESULTS_SCREEN)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f))
                ChatIconButton(unread = chatState.unread, onClick = chat::open, size = 44.dp)
            }
            PopCard(
                modifier = Modifier.fillMaxWidth(),
                color = Palette.Lime,
                borderWidth = 2.5.dp,
                shadow = 6.dp,
                shape = RoundedCornerShape(28.dp),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CapsText(stringResource(Res.string.results_title))
                Text(
                    text = stringResource(
                        if (survivors.isEmpty()) Res.string.results_seekers_win else Res.string.results_hiders_win,
                    ),
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.graphicsLayer {
                        scaleX = pop.value
                        scaleY = pop.value
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    },
                )
                if (me.role == Role.HIDER) {
                    val personal = when (me.status) {
                        PlayerStatus.ACTIVE -> Res.string.results_you_survived
                        PlayerStatus.CAUGHT -> Res.string.results_you_caught
                        PlayerStatus.ELIMINATED -> Res.string.results_you_eliminated
                    }
                    Text(text = stringResource(personal), style = MaterialTheme.typography.titleMedium)
                }
            }

            PopCard(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val groups = listOf(
                    Res.string.results_survived to survivors,
                    Res.string.results_caught to hiders.filter { it.status == PlayerStatus.CAUGHT },
                    Res.string.results_eliminated to hiders.filter { it.status == PlayerStatus.ELIMINATED },
                    Res.string.results_seekers to snapshot.players.filter { it.role == Role.SEEKER },
                ).filter { it.second.isNotEmpty() }
                groups.forEachIndexed { index, (title, players) ->
                    if (index > 0) HorizontalDivider(color = Palette.Line, thickness = 1.5.dp)
                    PlayerGroup(title, players, list)
                }
            }
            CommandStatus(
                isBusy = false,
                message = message,
                onDismiss = viewModel::dismissMessage,
                errorTag = TestTags.SOCIAL_ERROR,
            )
        }
        PopButton(
            text = stringResource(Res.string.results_back),
            onClick = viewModel::leave,
            style = PopStyle.Dark,
            height = 58.dp,
            icon = Res.drawable.ic_home,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
                .testTag(TestTags.RESULTS_BACK),
        )
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
    val youTag = stringResource(Res.string.lobby_you)
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        CapsText(stringResource(title), color = Palette.Ink2, modifier = Modifier.padding(vertical = 4.dp))
        players.forEach { player ->
            Row(
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(name = player.name, color = player.role.color, contentColor = player.role.onColor, size = 32.dp)
                Column(modifier = Modifier.weight(1f)) {
                    val name = if (player.id == list.myId) "${player.name} · $youTag" else player.name
                    Text(text = name, style = MaterialTheme.typography.titleSmall)
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
    }
}
