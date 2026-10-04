package app.hovanki.client.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.buildings_close_action
import app.hovanki.client.resources.buildings_done
import app.hovanki.client.resources.buildings_forbidden_chip
import app.hovanki.client.resources.buildings_forbidden_text
import app.hovanki.client.resources.buildings_limit
import app.hovanki.client.resources.buildings_open_action
import app.hovanki.client.resources.buildings_open_chip
import app.hovanki.client.resources.buildings_open_text
import app.hovanki.client.resources.buildings_selected
import app.hovanki.client.resources.buildings_status_forbidden
import app.hovanki.client.resources.buildings_status_open
import app.hovanki.client.resources.buildings_tap_hint
import app.hovanki.client.resources.settings_buildings_loading
import app.hovanki.client.resources.settings_buildings_off
import app.hovanki.client.resources.settings_buildings_title
import app.hovanki.client.session.ZoneCue
import app.hovanki.client.ui.common.Panel
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.game.GameMap
import app.hovanki.client.ui.lobby.BuildingPickerState
import app.hovanki.client.ui.lobby.LobbyEvent
import app.hovanki.client.ui.lobby.LobbyUiState
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.geo.offsetFrom
import app.hovanki.shared.protocol.BuildingArea
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.rules.SettingsLimits
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The zone's buildings for the host to open some for hiding (docs/adr/0014-settings-lobby-redesign-open-buildings.md,
 * section 4): pink ones are forbidden, lime ones open. A tap picks the building there (the whole outline of adjoining
 * houses, outlined in ink); «Allow hiding» opens it for everybody right away, «Close again» takes it back.
 */
@Composable
fun BuildingsPanel(state: LobbyUiState, picker: BuildingPickerState, onEvent: (LobbyEvent) -> Unit) {
    val buildings = state.buildings
    val picked = picker.picked
    val onClose = { onEvent(LobbyEvent.Buildings.Close) }
    Panel(
        title = stringResource(Res.string.settings_buildings_title),
        onClose = onClose,
        modifier = Modifier.testTag(TestTags.BUILDINGS_PANEL),
        screen = "buildings",
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            GameMap(
                zone = state.zone,
                serverNow = { 0L },
                cue = ZoneCue.CALM,
                myLocation = null,
                myRole = if (state.amSeeker) Role.SEEKER else Role.HIDER,
                markers = emptyList(),
                buildings = buildings,
                onMapClick = { onEvent(LobbyEvent.Buildings.Pick(it)) },
                highlightedBuilding = picked,
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CountChip(
                    text = stringResource(Res.string.buildings_forbidden_chip, buildings?.buildings?.size ?: 0),
                    swatch = Palette.Pink.copy(alpha = 0.3f),
                    swatchBorder = Palette.Pink,
                )
                CountChip(
                    text = stringResource(Res.string.buildings_open_chip, buildings?.open?.size ?: 0),
                    swatch = Palette.Lime,
                    swatchBorder = Palette.Ink,
                )
            }
            if (picked == null) {
                val hint = when {
                    buildings != null -> Res.string.buildings_tap_hint
                    state.buildingsState == BuildingsState.UNAVAILABLE -> Res.string.settings_buildings_off
                    else -> Res.string.settings_buildings_loading
                }
                Text(
                    text = stringResource(hint),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 36.dp, start = 20.dp, end = 20.dp)
                        .background(Palette.Ink, RoundedCornerShape(18.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                PickedBuilding(
                    building = picked,
                    isOpen = picker.isPickedOpen,
                    openCount = buildings?.open?.size ?: 0,
                    busy = picker.isToggling,
                    onToggle = { onEvent(LobbyEvent.Buildings.Toggle) },
                    onDone = onClose,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/** The picked building: its size, whether hiding is allowed there, and the one action. */
@Composable
private fun PickedBuilding(
    building: BuildingArea,
    isOpen: Boolean,
    openCount: Int,
    busy: Boolean,
    onToggle: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (width, depth) = sizeOf(building)
    PopCard(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(24.dp),
        borderWidth = 2.5.dp,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(Res.string.buildings_selected, width, depth),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    if (isOpen) Res.string.buildings_status_open else Res.string.buildings_status_forbidden,
                ),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .background(if (isOpen) Palette.Lime else Palette.Pink, RoundedCornerShape(12.dp))
                    .border(2.dp, Palette.Ink, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        SecondaryText(
            stringResource(if (isOpen) Res.string.buildings_open_text else Res.string.buildings_forbidden_text),
        )
        val atLimit = !isOpen && openCount >= SettingsLimits.MAX_OPEN_BUILDINGS
        PopButton(
            text = stringResource(if (isOpen) Res.string.buildings_close_action else Res.string.buildings_open_action),
            onClick = onToggle,
            enabled = !busy && !atLimit,
            style = if (isOpen) PopStyle.Outline else PopStyle.Primary,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.BUILDINGS_TOGGLE),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryText(
                stringResource(Res.string.buildings_limit, openCount, SettingsLimits.MAX_OPEN_BUILDINGS),
                modifier = Modifier.weight(1f),
            )
            PopButton(
                text = stringResource(Res.string.buildings_done),
                onClick = onDone,
                style = PopStyle.Dark,
                height = 40.dp,
                modifier = Modifier.testTag(TestTags.BUILDINGS_DONE),
            )
        }
    }
}

/** A count over the map with its color. */
@Composable
private fun CountChip(text: String, swatch: Color, swatchBorder: Color) {
    Row(
        modifier = Modifier
            .background(Palette.Paper, RoundedCornerShape(12.dp))
            .border(2.dp, Palette.Ink, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(
            modifier = Modifier
                .size(14.dp)
                .background(swatch, RoundedCornerShape(3.dp))
                .border(1.5.dp, swatchBorder, RoundedCornerShape(3.dp)),
        )
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
}

/** A building's width and depth in whole meters, from its outline's box. */
private fun sizeOf(building: BuildingArea): Pair<Int, Int> {
    val origin = building.outline.firstOrNull() ?: return 0 to 0
    val offsets = building.outline.map { it.offsetFrom(origin) }
    val width = offsets.maxOf { it.eastMeters } - offsets.minOf { it.eastMeters }
    val depth = offsets.maxOf { it.northMeters } - offsets.minOf { it.northMeters }
    return width.roundToInt() to depth.roundToInt()
}
