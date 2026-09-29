package app.hovanki.client.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.quests_approve
import app.hovanki.client.resources.quests_deadline
import app.hovanki.client.resources.quests_done
import app.hovanki.client.resources.quests_done_by
import app.hovanki.client.resources.quests_failed
import app.hovanki.client.resources.quests_mark_done
import app.hovanki.client.resources.quests_none
import app.hovanki.client.resources.quests_pending
import app.hovanki.client.resources.quests_progress
import app.hovanki.client.resources.quests_refuse
import app.hovanki.client.resources.quests_title
import app.hovanki.client.resources.quests_to_review
import app.hovanki.client.resources.sparks_count
import app.hovanki.client.ui.common.Panel
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopChip
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.audienceTitle
import app.hovanki.client.ui.common.formatCountdown
import app.hovanki.client.ui.common.questText
import app.hovanki.client.ui.common.questTitle
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.protocol.QuestKind
import app.hovanki.shared.protocol.QuestStatus
import app.hovanki.shared.protocol.QuestView
import org.jetbrains.compose.resources.stringResource

/**
 * The quests of the round (docs/adr/0013-quests-sparks-and-sensors.md): the viewer's, with their progress and what
 * they give; «Done!» on the host's own quests, and for the host the players waiting for an answer.
 */
@Composable
fun QuestsPanel(state: GameUiState, viewModel: GameViewModel, onClose: () -> Unit) {
    Panel(
        title = stringResource(Res.string.quests_title),
        onClose = onClose,
        modifier = Modifier.testTag(TestTags.QUESTS_PANEL),
    ) {
        ScreenColumn {
            state.sparks?.let { SparksLine(it) }
            if (state.quests.isEmpty()) SecondaryText(stringResource(Res.string.quests_none))
            state.quests.forEach { quest -> QuestCard(quest, state, viewModel) }
        }
    }
}

/** The viewer's sparks, big. */
@Composable
internal fun SparksLine(sparks: Int) {
    Text(
        text = stringResource(Res.string.sparks_count, sparks),
        style = MaterialTheme.typography.headlineSmall,
        color = Palette.Ink,
    )
}

@Composable
private fun QuestCard(quest: QuestView, state: GameUiState, viewModel: GameViewModel) {
    val custom = quest.kind == QuestKind.CUSTOM
    PopCard(
        modifier = Modifier.fillMaxWidth().testTag(TestTags.quest(quest.id.value)),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (custom) quest.text.orEmpty() else questTitle(quest.kind),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            PopChip(
                text = stringResource(Res.string.sparks_count, quest.sparks),
                color = Palette.Lime,
                contentColor = Palette.Ink,
                border = Palette.Ink,
            )
        }
        val text = if (custom) audienceTitle(quest.audience) else questText(quest.kind)
        if (text != null) SecondaryText(text)
        when (quest.status) {
            QuestStatus.ACTIVE -> {
                val parts = buildList {
                    if (quest.target > 1) add(stringResource(Res.string.quests_progress, quest.progress, quest.target))
                    quest.deadlineMillis?.let { deadline ->
                        add(stringResource(Res.string.quests_deadline, formatCountdown(deadline - state.now)))
                    }
                }
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            }

            QuestStatus.DONE -> StatusChip(stringResource(Res.string.quests_done), Palette.Lime)

            QuestStatus.FAILED -> StatusChip(stringResource(Res.string.quests_failed), Palette.Stale)

            QuestStatus.PENDING_REVIEW -> StatusChip(stringResource(Res.string.quests_pending), Palette.Sand)
        }
        if (custom && quest.status == QuestStatus.ACTIVE && !state.isHost) {
            PopButton(
                text = stringResource(Res.string.quests_mark_done),
                onClick = { viewModel.questDone(quest.id) },
                enabled = !state.isBusy,
                height = 44.dp,
                modifier = Modifier.testTag(TestTags.questDone(quest.id.value)),
            )
        }
        if (state.isHost) {
            quest.pending.forEach { playerId ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(Res.string.quests_to_review, state.names[playerId].orEmpty()),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    PopButton(
                        text = stringResource(Res.string.quests_approve),
                        onClick = { viewModel.reviewQuest(quest.id, playerId, approved = true) },
                        enabled = !state.isBusy,
                        height = 40.dp,
                        modifier = Modifier.testTag(TestTags.questApprove(quest.id.value, playerId)),
                    )
                    PopButton(
                        text = stringResource(Res.string.quests_refuse),
                        onClick = { viewModel.reviewQuest(quest.id, playerId, approved = false) },
                        enabled = !state.isBusy,
                        style = PopStyle.Outline,
                        height = 40.dp,
                        modifier = Modifier.testTag(TestTags.questRefuse(quest.id.value, playerId)),
                    )
                }
            }
        }
        if (quest.doneBy.isNotEmpty()) {
            SecondaryText(
                stringResource(Res.string.quests_done_by, quest.doneBy.joinToString { state.names[it].orEmpty() }),
            )
        }
    }
}

@Composable
private fun StatusChip(text: String, color: androidx.compose.ui.graphics.Color) {
    PopChip(text = text, color = color, contentColor = Palette.Ink, border = Palette.Ink)
}
