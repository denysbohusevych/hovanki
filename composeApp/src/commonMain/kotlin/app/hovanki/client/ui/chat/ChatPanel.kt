package app.hovanki.client.ui.chat

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_cancel
import app.hovanki.client.resources.action_dismiss
import app.hovanki.client.resources.chat_block
import app.hovanki.client.resources.chat_empty
import app.hovanki.client.resources.chat_hint
import app.hovanki.client.resources.chat_input_label
import app.hovanki.client.resources.chat_open
import app.hovanki.client.resources.chat_report
import app.hovanki.client.resources.chat_send
import app.hovanki.client.resources.chat_team
import app.hovanki.client.resources.chat_title
import app.hovanki.client.resources.chat_to_all
import app.hovanki.client.resources.chat_to_team
import app.hovanki.client.resources.ic_chat
import app.hovanki.client.resources.ic_send
import app.hovanki.client.resources.lobby_you
import app.hovanki.client.resources.player_guest
import app.hovanki.client.session.ChatLine
import app.hovanki.client.ui.common.Banner
import app.hovanki.client.ui.common.CommandStatus
import app.hovanki.client.ui.common.Panel
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.describe
import app.hovanki.shared.rules.ChatRules
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Opens the chat; shows how many messages are unread ([TestTags.CHAT_UNREAD]). */
@Composable
fun ChatButton(unread: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = onClick, modifier = modifier.testTag(TestTags.CHAT_OPEN)) {
        BadgedBox(
            badge = {
                if (unread > 0) {
                    Badge(modifier = Modifier.testTag(TestTags.CHAT_UNREAD)) {
                        Text(if (unread > MAX_BADGE) "$MAX_BADGE+" else unread.toString())
                    }
                }
            },
        ) {
            Icon(painter = painterResource(Res.drawable.ic_chat), contentDescription = null)
        }
        Spacer(Modifier.width(12.dp))
        Text(stringResource(Res.string.chat_open))
    }
}

/**
 * The game's chat, full screen over the lobby, game or results screen: messages (oldest at the top, the list follows
 * new ones), «everyone» or «my team», the input. A long press on another player's message offers to report it or to
 * block its sender. Back and the close button return to the screen.
 */
@Composable
fun ChatPanel(viewModel: ChatViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val lines = state.lines
    // Follow the conversation: the newest message in view whenever one arrives.
    LaunchedEffect(lines.lastOrNull()?.seq) {
        // Item 0 is the hint above the messages.
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size)
    }

    Panel(
        title = stringResource(Res.string.chat_title),
        onClose = viewModel::close,
        modifier = Modifier.testTag(TestTags.CHAT_PANEL),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    SecondaryText(
                        text = stringResource(if (lines.isEmpty()) Res.string.chat_empty else Res.string.chat_hint),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                items(lines, key = { it.seq }) { line ->
                    ChatMessage(
                        line = line,
                        isSelected = line.seq == state.selectedSeq,
                        canBlock = state.canBlock && line.senderUserId != null,
                        isBusy = isBusy,
                        onLongPress = { viewModel.select(line) },
                        onReport = { viewModel.report(line.seq) },
                        onBlock = { line.senderUserId?.let(viewModel::block) },
                        onCancel = viewModel::cancelSelection,
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.error?.let { error ->
                    Banner(
                        text = error.describe(),
                        modifier = Modifier.testTag(TestTags.BANNER_ERROR),
                        isError = true,
                        actionLabel = stringResource(Res.string.action_dismiss),
                        onAction = viewModel::dismissMessage,
                    )
                }
                CommandStatus(
                    isBusy = false,
                    message = message,
                    onDismiss = viewModel::dismissMessage,
                    errorTag = TestTags.SOCIAL_ERROR,
                )
                if (state.hasTeamChannel) ChannelPicker(toTeam = viewModel.toTeam, onSelect = viewModel::selectChannel)
                MessageInput(viewModel)
            }
        }
    }
}

/** «Everyone» or «my team»: the server picks the team channel from the player's role. */
@Composable
private fun ChannelPicker(toTeam: Boolean, onSelect: (team: Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !toTeam,
            onClick = { onSelect(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            modifier = Modifier.testTag(TestTags.CHAT_TO_ALL),
        ) {
            Text(stringResource(Res.string.chat_to_all))
        }
        SegmentedButton(
            selected = toTeam,
            onClick = { onSelect(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            modifier = Modifier.testTag(TestTags.CHAT_TO_TEAM),
        ) {
            Text(stringResource(Res.string.chat_to_team))
        }
    }
}

@Composable
private fun MessageInput(viewModel: ChatViewModel) {
    val text = viewModel.text
    val canSend = !viewModel.isSending && ChatRules.clean(text).isNotEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = viewModel::onTextChange,
            label = { Text(stringResource(Res.string.chat_input_label)) },
            supportingText = { Text("${text.length}/${ChatRules.MAX_LENGTH}") },
            maxLines = 4,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { viewModel.send() }),
            modifier = Modifier.weight(1f).testTag(TestTags.CHAT_INPUT),
        )
        IconButton(
            onClick = viewModel::send,
            enabled = canSend,
            modifier = Modifier.testTag(TestTags.CHAT_SEND),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_send),
                contentDescription = stringResource(Res.string.chat_send),
            )
        }
    }
}

/**
 * One message: the player's own on the right, others' on the left with the sender («guest» without an account) and
 * whether only the team sees it. A long press on another player's message shows its actions below it.
 */
@Composable
private fun ChatMessage(
    line: ChatLine,
    isSelected: Boolean,
    canBlock: Boolean,
    isBusy: Boolean,
    onLongPress: () -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
    onCancel: () -> Unit,
) {
    val sender = if (line.isMine) {
        stringResource(Res.string.lobby_you)
    } else {
        val name = line.senderName ?: "?"
        if (line.isGuest) "$name (${stringResource(Res.string.player_guest)})" else name
    }
    val header = if (line.isTeam) "$sender · ${stringResource(Res.string.chat_team)}" else sender
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalAlignment = if (line.isMine) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = if (line.isMine) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .testTag(TestTags.chatMessage(line.seq))
                .combinedClickable(enabled = !line.isMine, onLongClick = onLongPress, onClick = {}),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = header,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (line.isTeam) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                )
                Text(text = line.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (isSelected) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                OutlinedButton(
                    onClick = onReport,
                    enabled = !isBusy,
                    modifier = Modifier.testTag(TestTags.CHAT_REPORT),
                ) {
                    Text(stringResource(Res.string.chat_report))
                }
                if (canBlock) {
                    OutlinedButton(
                        onClick = onBlock,
                        enabled = !isBusy,
                        modifier = Modifier.testTag(TestTags.CHAT_BLOCK),
                    ) {
                        Text(stringResource(Res.string.chat_block))
                    }
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        }
    }
}

/** More unread messages than this show as "99+". */
private const val MAX_BADGE = 99
