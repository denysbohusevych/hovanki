package app.hovanki.client.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.settings_title
import app.hovanki.client.ui.common.Panel
import app.hovanki.client.ui.lobby.LobbyUiState
import app.hovanki.client.ui.lobby.LobbyViewModel
import app.hovanki.shared.protocol.GameSettings
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.TimeSource

/**
 * The host's game setup (docs/adr/0014-settings-lobby-redesign-open-buildings.md, section 2): on top the map of the
 * draft, where every change shows at once (or, on «More», how the picked extra works), then the tabs «Zone», «Time»,
 * «More», and at the bottom what changed so far with «Save». A setup that touches the map asks «What changes» first.
 * Back and the close button leave without saving.
 */
@Composable
fun SettingsPanel(state: LobbyUiState, viewModel: LobbyViewModel) {
    val draft = viewModel.draftSettings() ?: return
    val tab = viewModel.settingsTab
    val elapsed = rememberPreviewClock(viewModel.preview, draft, onEnd = viewModel::stopPreview)
    Panel(
        title = stringResource(Res.string.settings_title),
        onClose = viewModel::closeSettings,
        modifier = Modifier.testTag(TestTags.SETTINGS_PANEL),
        screen = "settings",
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                when (tab) {
                    SettingsTab.ZONE, SettingsTab.TIME -> SettingsMap(
                        state = state,
                        viewModel = viewModel,
                        draft = draft,
                        elapsed = elapsed,
                        modifier = Modifier.fillMaxWidth().height(MAP_HEIGHT),
                    )

                    SettingsTab.MORE -> ExplainerCard(
                        explainer = viewModel.focusedExtra,
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(MAP_HEIGHT - 20.dp),
                    )
                }
                SettingsTabs(
                    selected = tab,
                    onPick = viewModel::pickSettingsTab,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (tab) {
                        SettingsTab.ZONE -> ZoneTab(state, viewModel, draft)
                        SettingsTab.TIME -> TimeTab(viewModel, draft, elapsed)
                        SettingsTab.MORE -> MoreTab(state, viewModel)
                    }
                }
                SaveBar(state, viewModel)
            }
            viewModel.helpFor?.let { explainer -> HelpSheet(explainer, onClose = viewModel::closeHelp) }
            viewModel.pendingChanges?.let { changes ->
                ChangesSheet(
                    changes = changes,
                    isSaving = viewModel.isSavingSettings,
                    onSave = viewModel::confirmSave,
                    onBack = viewModel::cancelSave,
                )
            }
        }
    }
}

/**
 * How far into the [preview] the map is, in the preview's own time: the zone's schedule for the shrink, the whole game
 * (hiding, then the search) for the game. Both play sped up, in [SHRINK_PREVIEW_MILLIS] and [GAME_PREVIEW_MILLIS];
 * [onEnd] when done. Null: nothing plays.
 */
@Composable
internal fun rememberPreviewClock(preview: SettingsPreview?, draft: GameSettings, onEnd: () -> Unit): State<Long?> {
    val end by rememberUpdatedState(onEnd)
    return produceState<Long?>(null, preview, draft) {
        if (preview == null) {
            value = null
            return@produceState
        }
        val zoneMillis = draft.zone.stages.sumOf { (it.holdSeconds + it.shrinkSeconds) * 1000L }
        val (total, realMillis) = when (preview) {
            SettingsPreview.SHRINK -> zoneMillis to SHRINK_PREVIEW_MILLIS
            SettingsPreview.GAME -> (draft.hidingSeconds + draft.seekingSeconds) * 1000L to GAME_PREVIEW_MILLIS
        }
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            val played = start.elapsedNow().inWholeMilliseconds
            value = total * played.coerceAtMost(realMillis) / realMillis
            if (played >= realMillis + END_HOLD_MILLIS) break
            delay(FRAME_MILLIS)
        }
        end()
    }
}

private val MAP_HEIGHT = 250.dp
private const val SHRINK_PREVIEW_MILLIS = 8_000L
private const val GAME_PREVIEW_MILLIS = 12_000L

/** The last frame stays a moment before the preview stops. */
private const val END_HOLD_MILLIS = 1_200L
private const val FRAME_MILLIS = 50L
