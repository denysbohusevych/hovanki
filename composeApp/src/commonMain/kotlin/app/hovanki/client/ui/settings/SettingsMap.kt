package app.hovanki.client.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.ic_crosshair
import app.hovanki.client.resources.ic_pause
import app.hovanki.client.resources.ic_play
import app.hovanki.client.resources.lobby_streets_loading
import app.hovanki.client.resources.preview_final
import app.hovanki.client.resources.preview_glow
import app.hovanki.client.resources.preview_hider
import app.hovanki.client.resources.preview_hiding
import app.hovanki.client.resources.preview_seeking
import app.hovanki.client.resources.preview_shrinking
import app.hovanki.client.resources.preview_soon
import app.hovanki.client.resources.settings_center
import app.hovanki.client.resources.settings_center_done
import app.hovanki.client.resources.settings_center_hint
import app.hovanki.client.resources.settings_chip_circle
import app.hovanki.client.resources.settings_chip_streets
import app.hovanki.client.resources.settings_km
import app.hovanki.client.resources.settings_map_after_save
import app.hovanki.client.resources.settings_map_buildings_after_save
import app.hovanki.client.resources.settings_map_no_streets
import app.hovanki.client.resources.settings_meters
import app.hovanki.client.resources.settings_play_game
import app.hovanki.client.resources.settings_play_shrink
import app.hovanki.client.resources.settings_play_stop
import app.hovanki.client.session.DraftZone
import app.hovanki.client.session.DraftZoneState
import app.hovanki.client.session.ZoneCue
import app.hovanki.client.session.momentAt
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.formatCountdown
import app.hovanki.client.ui.game.GameMap
import app.hovanki.client.ui.game.MapMarker
import app.hovanki.client.ui.game.ZoneTimeline
import app.hovanki.client.ui.lobby.LobbyUiState
import app.hovanki.client.ui.lobby.LobbyViewModel
import app.hovanki.client.ui.theme.Hovanki
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.protocol.ZoneCircle
import app.hovanki.shared.protocol.ZoneSchedule
import app.hovanki.shared.protocol.ZoneShape
import app.hovanki.shared.rules.Glow
import app.hovanki.shared.rules.SettingsLimits
import app.hovanki.shared.rules.stateAt
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The draft on the real map (docs/adr/0014-settings-lobby-redesign-open-buildings.md, section 2.2): the zone the host is
 * choosing, where it will be, and the zone's buildings (pink, open ones lime). The camera follows the draft. A zone by
 * streets is drawn once the server built it, a draft's too, before it is saved (section 2.3; its circles meanwhile); the
 * buildings of a new zone come with saving. «Shrink» plays the zone's stages, «Play the game» the whole game with
 * made-up hiders ([elapsed], sped up). «Center» moves the zone: the host pans the map under a pin.
 */
@Composable
internal fun SettingsMap(
    state: LobbyUiState,
    viewModel: LobbyViewModel,
    draft: GameSettings,
    elapsed: State<Long?>,
    modifier: Modifier = Modifier,
) {
    val preview = viewModel.preview
    val moving = viewModel.isMovingCenter
    val saved = state.zone.schedule
    val isSavedZone = draft.zone == saved && draft.zoneShape == state.zoneShape
    val draftZone by viewModel.draftZone.collectAsStateWithLifecycle()
    val draftStreets = draftZone?.takeIf { draft.zoneShape == ZoneShape.STREETS && it.zone == draft.zone }
    val streets = if (isSavedZone) state.streets else draftStreets?.streets
    val zoneStart = when (preview) {
        null -> null
        SettingsPreview.SHRINK -> 0L
        SettingsPreview.GAME -> draft.hidingSeconds * 1000L
    }
    val timeline = remember(draft.zone, streets, zoneStart) { ZoneTimeline(draft.zone, zoneStart, streets) }
    val fit = remember(timeline) { timeline.shapeAt(0L).extent }
    val origin = viewModel.zoneOrigin(state.gameId) ?: saved.initial.center
    val now = elapsed.value ?: 0L
    val zoneElapsed = zoneStart?.let { now - it }?.takeIf { it >= 0 }
    val cue = draft.zone.momentAt(zoneElapsed).cue
    val hiderName = stringResource(Res.string.preview_hider)
    val markers = if (preview == SettingsPreview.GAME) demoHiders(draft, now, hiderName) else emptyList()

    Box(modifier = modifier) {
        GameMap(
            zone = timeline,
            serverNow = { elapsed.value ?: 0L },
            cue = cue,
            myLocation = null,
            myRole = if (state.amSeeker) Role.SEEKER else Role.HIDER,
            markers = markers,
            buildings = state.buildings,
            fitTo = fit.takeIf { !moving },
            cameraArea = if (moving) {
                ZoneCircle(origin, SettingsLimits.MAX_CENTER_MOVE_METERS + draft.zone.initial.radiusMeters)
            } else {
                null
            },
            onCameraIdle = if (moving) viewModel::moveDraftCenter else null,
            animateZone = preview != null,
            modifier = Modifier.fillMaxSize(),
        )

        if (preview != null) {
            PreviewHud(preview, draft, now, cue, Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
        } else {
            MapChip(
                text = if (draft.zoneShape == ZoneShape.CIRCLE) {
                    stringResource(Res.string.settings_chip_circle, distanceText(draft.zone.initial.radiusMeters))
                } else {
                    stringResource(Res.string.settings_chip_streets, distanceText(draft.zone.initial.radiusMeters))
                },
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            )
        }
        if (moving) {
            Pin(Modifier.align(Alignment.Center).offset(y = (-PIN_HEIGHT / 2)))
            MapChip(
                text = stringResource(Res.string.settings_center_hint),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp, start = 16.dp, end = 16.dp),
            )
        } else if (preview == null) {
            draftNote(isSavedZone, draft, saved, draftStreets)?.let { note ->
                MapChip(
                    text = stringResource(note),
                    color = Palette.Paper,
                    contentColor = Palette.Ink,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 60.dp),
                )
            }
        }
        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val kind = if (viewModel.settingsTab == SettingsTab.TIME) SettingsPreview.GAME else SettingsPreview.SHRINK
            if (kind == SettingsPreview.GAME || draft.zone.stages.isNotEmpty()) {
                PopButton(
                    text = when {
                        preview == kind -> stringResource(Res.string.settings_play_stop)
                        kind == SettingsPreview.GAME -> stringResource(Res.string.settings_play_game)
                        else -> stringResource(Res.string.settings_play_shrink)
                    },
                    onClick = { viewModel.togglePreview(kind) },
                    style = if (kind == SettingsPreview.GAME) PopStyle.Primary else PopStyle.Outline,
                    height = 40.dp,
                    icon = if (preview == kind) Res.drawable.ic_pause else Res.drawable.ic_play,
                    modifier = Modifier.testTag(TestTags.SETTINGS_PREVIEW),
                )
            }
            if (viewModel.settingsTab == SettingsTab.ZONE) {
                PopButton(
                    text = stringResource(if (moving) Res.string.settings_center_done else Res.string.settings_center),
                    onClick = viewModel::toggleMovingCenter,
                    style = if (moving) PopStyle.Primary else PopStyle.Outline,
                    height = 40.dp,
                    icon = Res.drawable.ic_crosshair,
                    modifier = Modifier.testTag(TestTags.SETTINGS_CENTER),
                )
            }
        }
    }
}

/**
 * What the map can't show of the draft yet: the blocks while the server builds them, the buildings of a new zone until
 * it is saved. Null: the map shows the draft as it will be.
 */
private fun draftNote(
    isSavedZone: Boolean,
    draft: GameSettings,
    saved: ZoneSchedule,
    streets: DraftZone?,
): StringResource? = when {
    isSavedZone -> null

    draft.zoneShape == ZoneShape.STREETS -> when (streets?.state) {
        null, DraftZoneState.LOADING -> Res.string.lobby_streets_loading
        DraftZoneState.READY -> Res.string.settings_map_buildings_after_save.takeIf { draft.zone != saved }
        DraftZoneState.NO_STREETS -> Res.string.settings_map_no_streets
        DraftZoneState.UNKNOWN -> Res.string.settings_map_after_save
    }

    draft.zone != saved -> Res.string.settings_map_buildings_after_save

    else -> null
}

/** The capsule over the map while a preview plays: what happens and how long for, like the game's HUD. */
@Composable
private fun PreviewHud(preview: SettingsPreview, draft: GameSettings, now: Long, cue: ZoneCue, modifier: Modifier) {
    val (label, value, color) = when (preview) {
        SettingsPreview.SHRINK -> {
            val radius = draft.zone.stateAt(now).current.radiusMeters
            val size = distanceText(radius)
            when (cue) {
                ZoneCue.SHRINKING -> Triple(stringResource(Res.string.preview_shrinking), size, Palette.Pink)

                ZoneCue.SOON, ZoneCue.COUNTDOWN -> Triple(stringResource(Res.string.preview_soon), size, Palette.Pink)

                ZoneCue.FINAL -> Triple(stringResource(Res.string.preview_final), size, Palette.Lime)

                ZoneCue.CALM, ZoneCue.SHRUNK -> Triple(
                    stringResource(Res.string.settings_play_shrink),
                    size,
                    Palette.Lime,
                )
            }
        }

        SettingsPreview.GAME -> {
            val hiding = draft.hidingSeconds * 1000L
            val end = hiding + draft.seekingSeconds * 1000L
            val glow = Glow.openAt(draft, hiding, now)
            val left = formatCountdown(end - now)
            when {
                now < hiding -> Triple(
                    stringResource(Res.string.preview_hiding),
                    formatCountdown(hiding - now),
                    Palette.VioletLight,
                )

                glow != null -> Triple(stringResource(Res.string.preview_glow), left, Palette.Orange)

                cue == ZoneCue.SHRINKING -> Triple(stringResource(Res.string.preview_shrinking), left, Palette.Pink)

                else -> Triple(stringResource(Res.string.preview_seeking), left, Palette.Lime)
            }
        }
    }
    Row(
        modifier = modifier
            .background(Palette.Ink, RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = Hovanki.text.caps.copy(fontSize = 10.sp, lineHeight = 13.sp),
            color = Color(0xFFB4B4BE),
            modifier = Modifier.widthIn(max = 120.dp),
        )
        Text(
            text = value,
            style = Hovanki.text.timer.copy(fontSize = 20.sp, lineHeight = 22.sp),
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 64.dp),
        )
    }
}

/** A dark label over the map. */
@Composable
private fun MapChip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.Ink,
    contentColor: Color = Color.White,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = contentColor,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(color, RoundedCornerShape(12.dp))
            .border(if (color == Palette.Ink) 0.dp else 2.dp, Palette.Ink, RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp, vertical = 7.dp),
    )
}

/** Where the zone's center goes: a lime pin whose tip is the map's middle. */
@Composable
private fun Pin(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(PIN_WIDTH, PIN_HEIGHT)) {
        val w = size.width
        val h = size.height
        val head = Offset(w / 2, w / 2)
        val body = Path().apply {
            moveTo(w / 2, h)
            lineTo(w * 0.1f, w * 0.7f)
            lineTo(w * 0.9f, w * 0.7f)
            close()
        }
        drawPath(body, Palette.Lime)
        drawPath(body, Palette.Ink, style = Stroke(2.5.dp.toPx()))
        drawCircle(Palette.Lime, w / 2 - 1.5.dp.toPx(), head)
        drawCircle(Palette.Ink, w / 2 - 1.5.dp.toPx(), head, style = Stroke(2.5.dp.toPx()))
        drawCircle(Palette.Ink, w / 6, head)
    }
}

/**
 * Made-up hiders for «Play the game»: the host's phone knows nobody's position in the lobby. Seen while a glow is on,
 * then where the last glow left them.
 */
private fun demoHiders(draft: GameSettings, now: Long, name: String): List<MapMarker> {
    val hiding = draft.hidingSeconds * 1000L
    if (now < hiding) return emptyList()
    val glow = Glow.lastStarted(draft, hiding, now) ?: return emptyList()
    val open = glow.isOpenAt(now)
    val center = draft.zone.initial.center
    val radius = draft.zone.stateAt(now - hiding).current.radiusMeters
    return DEMO_SPOTS.mapIndexed { index, (share, degrees) ->
        val angle = degrees * kotlin.math.PI / 180
        MapMarker(
            id = PlayerId("demo-$index"),
            name = "$name ${index + 1}",
            point = center.moveBy(radius * share * cos(angle), radius * share * sin(angle)),
            accuracyMeters = 0.0,
            reason = VisibilityReason.GLOW,
            markAgeMillis = if (open) null else now - glow.endMillis,
        )
    }
}

/** «500 m», «1.2 km» («1,2 км» where the comma is the decimal sign). */
@Composable
internal fun distanceText(meters: Double): String = if (meters >= 1_000) {
    val tenths = (meters / 100).roundToInt()
    val point = if (Locale.current.language == "en") "." else ","
    val number = if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}$point${tenths % 10}"
    stringResource(Res.string.settings_km, number)
} else {
    stringResource(Res.string.settings_meters, meters.roundToInt())
}

/** Where the made-up hiders stand: a share of the zone's radius and a direction. */
private val DEMO_SPOTS = listOf(0.35 to 30.0, 0.6 to 150.0, 0.5 to 250.0, 0.7 to 320.0)
private val PIN_WIDTH = 30.dp
private val PIN_HEIGHT = 42.dp
