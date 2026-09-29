package app.hovanki.client.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.perk_cooldown
import app.hovanki.client.resources.perk_owned
import app.hovanki.client.resources.perk_pick_target
import app.hovanki.client.resources.perk_use
import app.hovanki.client.resources.perk_uses_left
import app.hovanki.client.resources.perks_none
import app.hovanki.client.resources.perks_title
import app.hovanki.client.resources.sparks_count
import app.hovanki.client.ui.common.Panel
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopChip
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.formatCountdown
import app.hovanki.client.ui.common.perkText
import app.hovanki.client.ui.common.perkTitle
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.protocol.PerkKind
import app.hovanki.shared.protocol.PerkView
import app.hovanki.shared.rules.PerkCatalog
import org.jetbrains.compose.resources.stringResource

/**
 * The perks (docs/adr/0013-quests-sparks-and-sensors.md, section 3): what the viewer may use, for sparks or from what
 * they found on the map. A perk aimed at a hider asks which one; the decoy goes to the map ([onPickDecoy]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PerksPanel(state: GameUiState, viewModel: GameViewModel, onClose: () -> Unit, onPickDecoy: () -> Unit) {
    var targeting by remember { mutableStateOf<PerkKind?>(null) }
    Panel(
        title = stringResource(Res.string.perks_title),
        onClose = onClose,
        modifier = Modifier.testTag(TestTags.PERKS_PANEL),
    ) {
        ScreenColumn {
            state.sparks?.let { SparksLine(it) }
            if (state.perks.isEmpty()) SecondaryText(stringResource(Res.string.perks_none))
            state.perks.forEach { perk ->
                PerkCard(
                    perk = perk,
                    state = state,
                    isTargeting = targeting == perk.perk,
                    onUse = {
                        val spec = PerkCatalog.spec(perk.perk)
                        when {
                            spec.needsTarget -> targeting = perk.perk

                            spec.needsPoint -> onPickDecoy()

                            else -> {
                                viewModel.usePerk(perk.perk)
                                onClose()
                            }
                        }
                    },
                    onTarget = { hider ->
                        viewModel.usePerk(perk.perk, targetId = hider)
                        targeting = null
                        onClose()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PerkCard(
    perk: PerkView,
    state: GameUiState,
    isTargeting: Boolean,
    onUse: () -> Unit,
    onTarget: (app.hovanki.shared.protocol.PlayerId) -> Unit,
) {
    PopCard(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = perkTitle(perk.perk),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            // What it costs, or what was found on the map and is free to use.
            if (perk.owned > 0) {
                PopChip(
                    text = stringResource(Res.string.perk_owned, perk.owned),
                    color = Palette.Pink,
                    contentColor = Palette.Ink,
                    border = Palette.Ink,
                )
            } else {
                PopChip(
                    text = stringResource(Res.string.sparks_count, perk.price),
                    color = Palette.Lime,
                    contentColor = Palette.Ink,
                    border = Palette.Ink,
                )
            }
        }
        SecondaryText(perkText(perk.perk))
        val notes = buildList {
            add(stringResource(Res.string.perk_uses_left, perk.usesLeft))
            perk.availableAtMillis?.let { at ->
                val left = at - state.now
                if (left > 0) add(stringResource(Res.string.perk_cooldown, formatCountdown(left)))
            }
        }
        Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        if (isTargeting) {
            Text(stringResource(Res.string.perk_pick_target), style = MaterialTheme.typography.titleSmall)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.activeHiders.forEach { hider ->
                    PopButton(
                        text = hider.name,
                        onClick = { onTarget(hider.id) },
                        enabled = !state.isBusy,
                        style = PopStyle.Seeker,
                        height = 44.dp,
                        modifier = Modifier.testTag(TestTags.claimButton(hider.id)),
                    )
                }
            }
        } else {
            PopButton(
                text = stringResource(Res.string.perk_use),
                onClick = onUse,
                enabled = perk.canUse && !state.isBusy,
                height = 44.dp,
                modifier = Modifier.testTag(TestTags.perkUse(perk.perk.name)),
            )
        }
    }
}
