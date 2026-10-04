package app.hovanki.client.ui.lobby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.catchcode.QrCodeImage
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_dismiss
import app.hovanki.client.resources.board_audience
import app.hovanki.client.resources.board_audience_all
import app.hovanki.client.resources.board_audience_hiders
import app.hovanki.client.resources.board_audience_seekers
import app.hovanki.client.resources.board_code
import app.hovanki.client.resources.board_code_hint
import app.hovanki.client.resources.board_custom_add
import app.hovanki.client.resources.board_custom_hint
import app.hovanki.client.resources.board_custom_quests
import app.hovanki.client.resources.board_custom_text
import app.hovanki.client.resources.board_empty
import app.hovanki.client.resources.board_hint
import app.hovanki.client.resources.board_later_half
import app.hovanki.client.resources.board_name
import app.hovanki.client.resources.board_perk
import app.hovanki.client.resources.board_place
import app.hovanki.client.resources.board_placed
import app.hovanki.client.resources.board_remove
import app.hovanki.client.resources.board_sparks
import app.hovanki.client.resources.board_tap_map
import app.hovanki.client.resources.board_title
import app.hovanki.client.resources.sparks_count
import app.hovanki.client.resources.working
import app.hovanki.client.session.ZoneCue
import app.hovanki.client.ui.common.Banner
import app.hovanki.client.ui.common.BusyRow
import app.hovanki.client.ui.common.Panel
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.PopSurface
import app.hovanki.client.ui.common.PopTextField
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.audienceTitle
import app.hovanki.client.ui.common.describe
import app.hovanki.client.ui.common.itemKindTitle
import app.hovanki.client.ui.common.perkTitle
import app.hovanki.client.ui.game.GameMap
import app.hovanki.client.ui.game.toMapItem
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.protocol.Audience
import app.hovanki.shared.protocol.BoardItem
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.ItemKind
import app.hovanki.shared.protocol.PerkKind
import app.hovanki.shared.protocol.QuestKind
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.rules.CheckpointPayload
import app.hovanki.shared.rules.QuestCatalog
import org.jetbrains.compose.resources.stringResource

/**
 * The host's board (docs/adr/0013-quests-sparks-and-sensors.md, section 2.4): a map to tap where a quest point, a
 * checkpoint or a perk goes, what it is for whom and what it gives, what is placed so far (a checkpoint by code with
 * its QR code to print), and the host's own quests in words.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoardPanel(state: LobbyUiState, board: BoardPanelState, onEvent: (LobbyEvent) -> Unit) {
    val kinds = board.kinds
    val kind = board.kind
    val pick = board.pick
    Panel(
        title = stringResource(Res.string.board_title),
        onClose = { onEvent(LobbyEvent.Board.Close) },
        modifier = Modifier.testTag(TestTags.BOARD_PANEL),
        screen = "board",
    ) {
        ScreenColumn {
            SecondaryText(stringResource(Res.string.board_hint))
            PopSurface(
                modifier = Modifier.fillMaxWidth().height(MAP_HEIGHT),
                shape = RoundedCornerShape(20.dp),
                color = Palette.Paper,
            ) {
                GameMap(
                    zone = state.zone,
                    // The zone has not started: its shape is the initial one whatever the time.
                    serverNow = { 0L },
                    cue = ZoneCue.CALM,
                    myLocation = null,
                    myRole = if (state.amSeeker) Role.SEEKER else Role.HIDER,
                    markers = emptyList(),
                    buildings = null,
                    items = state.items.map { it.toMapItem(null) },
                    pickedPoint = pick,
                    onMapClick = { onEvent(LobbyEvent.Board.PickPoint(it)) },
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)),
                )
            }
            if (pick == null) SecondaryText(stringResource(Res.string.board_tap_map))

            PopCard(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    kinds.forEach { option ->
                        ShapeButton(
                            text = itemKindTitle(option),
                            selected = option == kind,
                            onClick = { onEvent(LobbyEvent.Board.PickKind(option)) },
                            modifier = Modifier.testTag(TestTags.boardKind(option.name)),
                        )
                    }
                }
                AudiencePicker(
                    audience = board.audience,
                    onPick = { onEvent(LobbyEvent.Board.PickAudience(it)) },
                )
                PopTextField(
                    value = board.name,
                    onValueChange = { onEvent(LobbyEvent.Board.EditName(it)) },
                    label = { Text(stringResource(Res.string.board_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.BOARD_NAME),
                )
                if (kind == ItemKind.PICKUP) {
                    Text(stringResource(Res.string.board_perk), style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PerkKind.entries.forEach { perk ->
                            ShapeButton(
                                text = perkTitle(perk),
                                selected = perk == board.perk,
                                onClick = { onEvent(LobbyEvent.Board.PickPerk(perk)) },
                            )
                        }
                    }
                } else {
                    val sparks = board.sparks
                    LabeledStepper(
                        label = stringResource(Res.string.board_sparks),
                        name = "board_sparks",
                        value = stringResource(Res.string.sparks_count, sparks),
                        onMinus = { onEvent(LobbyEvent.Board.EditSparks(sparks - 1)) },
                        onPlus = { onEvent(LobbyEvent.Board.EditSparks(sparks + 1)) },
                        canMinus = sparks > 1,
                        canPlus = sparks < QuestCatalog.MAX_SPARKS,
                    )
                    if (kind == ItemKind.CHECKPOINT_GEO || kind == ItemKind.CHECKPOINT_SCAN) {
                        SecondaryText(stringResource(Res.string.board_later_half))
                    }
                }
                PopButton(
                    text = stringResource(Res.string.board_place),
                    onClick = { onEvent(LobbyEvent.Board.Place) },
                    enabled = pick != null && !board.isPlacing,
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.BOARD_PLACE),
                )
                if (board.isPlacing) BusyRow(stringResource(Res.string.working))
            }
            state.error?.let { error ->
                Banner(
                    text = error.describe(),
                    modifier = Modifier.testTag(TestTags.BANNER_ERROR),
                    isError = true,
                    actionLabel = stringResource(Res.string.action_dismiss),
                    onAction = { onEvent(LobbyEvent.DismissError) },
                )
            }

            if (state.items.isEmpty()) {
                SecondaryText(stringResource(Res.string.board_empty))
            } else {
                Text(
                    text = stringResource(Res.string.board_placed, state.items.size),
                    style = MaterialTheme.typography.titleMedium,
                )
                state.items.forEach { item ->
                    ItemCard(item, state.gameId, onRemove = { onEvent(LobbyEvent.Board.Remove(item.id)) })
                }
            }

            if (state.features.quests) CustomQuests(state, board, onEvent)
        }
    }
}

@Composable
private fun AudiencePicker(audience: Audience, onPick: (Audience) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.board_audience), style = MaterialTheme.typography.bodyLarge)
        ShapeButton(
            text = stringResource(Res.string.board_audience_all),
            selected = audience == Audience.ALL,
            onClick = { onPick(Audience.ALL) },
        )
        ShapeButton(
            text = stringResource(Res.string.board_audience_hiders),
            selected = audience == Audience.HIDERS,
            onClick = { onPick(Audience.HIDERS) },
        )
        ShapeButton(
            text = stringResource(Res.string.board_audience_seekers),
            selected = audience == Audience.SEEKERS,
            onClick = { onPick(Audience.SEEKERS) },
        )
    }
}

/** An item placed: what and for whom, «Remove», and for a checkpoint by code the QR code to print. */
@Composable
private fun ItemCard(item: BoardItem, gameId: GameId, onRemove: () -> Unit) {
    PopCard(
        modifier = Modifier.fillMaxWidth().testTag(TestTags.boardItem(item.id.value)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name.ifBlank { itemKindTitle(item.kind) },
                    style = MaterialTheme.typography.titleSmall,
                )
                val gives = item.perk?.let { perkTitle(it) } ?: stringResource(Res.string.sparks_count, item.sparks)
                SecondaryText("${itemKindTitle(item.kind)} · ${audienceTitle(item.audience)} · $gives")
            }
            PopButton(
                text = stringResource(Res.string.board_remove),
                onClick = onRemove,
                style = PopStyle.Outline,
                height = 40.dp,
                modifier = Modifier.testTag(TestTags.boardItemRemove(item.id.value)),
            )
        }
        item.code?.let { code ->
            val payload = CheckpointPayload(gameId, code).encode()
            QrCodeImage(
                text = payload,
                contentDescription = stringResource(Res.string.board_code, code),
                color = Palette.Ink,
                modifier = Modifier.size(QR_SIZE).align(Alignment.CenterHorizontally),
            )
            Text(
                text = stringResource(Res.string.board_code, code),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            SecondaryText(stringResource(Res.string.board_code_hint))
        }
    }
}

/** The host's own quests in words: what to do, for whom; and the ones made so far. */
@Composable
private fun CustomQuests(state: LobbyUiState, board: BoardPanelState, onEvent: (LobbyEvent) -> Unit) {
    PopCard(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(Res.string.board_custom_quests), style = MaterialTheme.typography.titleMedium)
        SecondaryText(stringResource(Res.string.board_custom_hint))
        PopTextField(
            value = board.questText,
            onValueChange = { onEvent(LobbyEvent.Board.EditQuestText(it)) },
            label = { Text(stringResource(Res.string.board_custom_text)) },
            modifier = Modifier.fillMaxWidth().testTag(TestTags.BOARD_QUEST_TEXT),
        )
        AudiencePicker(audience = board.questAudience, onPick = { onEvent(LobbyEvent.Board.PickQuestAudience(it)) })
        PopButton(
            text = stringResource(Res.string.board_custom_add),
            onClick = { onEvent(LobbyEvent.Board.AddQuest) },
            enabled = board.questText.isNotBlank() && !board.isAddingQuest,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.BOARD_QUEST_ADD),
        )
        state.quests.filter { it.kind == QuestKind.CUSTOM }.forEach { quest ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = quest.text.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).testTag(TestTags.quest(quest.id.value)),
                )
                SecondaryText(
                    "${audienceTitle(quest.audience)} · ${stringResource(Res.string.sparks_count, quest.sparks)}",
                )
            }
        }
    }
}

private val MAP_HEIGHT = 280.dp
private val QR_SIZE = 180.dp
