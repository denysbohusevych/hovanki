package app.hovanki.client.ui.game

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.flash_seek
import app.hovanki.client.resources.flash_seekers_out
import app.hovanki.client.resources.hud_hiders
import app.hovanki.client.resources.hud_outside
import app.hovanki.client.resources.hud_to_edge
import app.hovanki.client.resources.hud_to_find
import app.hovanki.client.resources.hud_you_hide
import app.hovanki.client.resources.hud_you_seek
import app.hovanki.client.resources.hud_zone_done
import app.hovanki.client.resources.hud_zone_final
import app.hovanki.client.resources.hud_zone_shrinking
import app.hovanki.client.resources.hud_zone_soon
import app.hovanki.client.resources.ic_building
import app.hovanki.client.resources.ic_warning
import app.hovanki.client.resources.phase_hiding
import app.hovanki.client.resources.phase_seeking
import app.hovanki.client.session.ZoneCue
import app.hovanki.client.ui.common.CapsText
import app.hovanki.client.ui.common.CountdownRing
import app.hovanki.client.ui.common.PopBorder
import app.hovanki.client.ui.common.PopChip
import app.hovanki.client.ui.common.PopIconButton
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.PopSurface
import app.hovanki.client.ui.common.formatCountdown
import app.hovanki.client.ui.theme.Hovanki
import app.hovanki.client.ui.theme.Motion
import app.hovanki.client.ui.theme.Palette
import app.hovanki.client.ui.theme.color
import app.hovanki.client.ui.theme.onColor
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.Role
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The game's heads-up display over the map (docs/design.md, «Экраны», «Анимации и отклик»): the ink capsule with the
 * timers, chips for the role and the zone, alerts with a pulsing edge, round controls, and the catch code tiles.
 */

/** What the hider is warned about; the edge of the screen pulses in its color. */
enum class GameAlert(val color: Color, val periodMillis: Int) {
    OUT_OF_ZONE(Palette.Orange, periodMillis = 1_000),
    IN_BUILDING(Palette.Pink, periodMillis = 2_000),
}

private val HUD_MUTED = Color(0xFFB4B4BE)
private val HUD_TRACK = Color(0xFF2E2E38)

/** Zone countdown, phase timer, how many hiders are left: an ink capsule at the top of the map. */
@Composable
fun HudCapsule(state: GameUiState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(32.dp))
            .background(Palette.Ink)
            .padding(start = if (state.isZoneRunning) 8.dp else 20.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.isZoneRunning) {
            val moment = state.zoneMoment
            val urgent =
                moment.cue == ZoneCue.SOON || moment.cue == ZoneCue.COUNTDOWN || moment.cue == ZoneCue.SHRINKING
            CountdownRing(
                progress = moment.fractionLeft ?: 1f,
                text = moment.millisLeft?.let(::formatCountdown) ?: "∞",
                color = if (urgent) Palette.Pink else Palette.Lime,
                trackColor = HUD_TRACK,
                textColor = Color.White,
                size = 48.dp,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = state.phaseMillisLeft?.let(::formatCountdown) ?: "—",
                style = Hovanki.text.timer,
                color = Palette.Lime,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 92.dp).testTag(TestTags.GAME_TIMER),
            )
            CapsText(
                text = stringResource(
                    if (state.phase == GamePhase.HIDING) Res.string.phase_hiding else Res.string.phase_seeking,
                ),
                color = HUD_MUTED,
                modifier = Modifier.testTag(TestTags.phase(state.phase)),
            )
        }
        Box(modifier = Modifier.width(1.dp).height(34.dp).background(HUD_TRACK))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (state.myRole == Role.HIDER) {
                    "${state.hidersLeft}/${state.hidersTotal}"
                } else {
                    "${state.hidersLeft}"
                },
                style = Hovanki.text.timer.copy(fontSize = 19.sp, lineHeight = 22.sp),
                color = Color.White,
            )
            CapsText(
                text = stringResource(
                    if (state.myRole == Role.HIDER) Res.string.hud_hiders else Res.string.hud_to_find,
                ),
                color = HUD_MUTED,
            )
        }
    }
}

/** The role and how far the border is; under them, what the zone is doing when it is not calm. */
@Composable
fun HudChips(state: GameUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PopChip(
                text = stringResource(
                    if (state.myRole == Role.HIDER) Res.string.hud_you_hide else Res.string.hud_you_seek,
                ),
                color = state.myRole.color,
                contentColor = state.myRole.onColor,
                border = Palette.Ink,
            )
            state.metersToZoneBorder?.let { meters ->
                val outside = meters < 0
                PopChip(
                    text = if (outside) {
                        stringResource(Res.string.hud_outside, abs(meters).roundToInt())
                    } else {
                        stringResource(Res.string.hud_to_edge, meters.roundToInt())
                    },
                    color = if (outside) Palette.Orange else Palette.Ink,
                    contentColor = if (outside) Palette.Ink else Palette.Lime,
                    border = if (outside) Palette.Ink else null,
                )
            }
        }
        if (state.isZoneRunning) ZoneChip(state)
    }
}

@Composable
private fun ZoneChip(state: GameUiState) {
    val moment = state.zoneMoment
    val left = moment.millisLeft?.let(::formatCountdown).orEmpty()
    val (text, color) = when (moment.cue) {
        ZoneCue.CALM -> return
        ZoneCue.SOON, ZoneCue.COUNTDOWN -> stringResource(Res.string.hud_zone_soon, left) to Palette.Pink
        ZoneCue.SHRINKING -> stringResource(Res.string.hud_zone_shrinking, left) to Palette.Pink
        ZoneCue.SHRUNK -> stringResource(Res.string.hud_zone_done) to Palette.Lime
        ZoneCue.FINAL -> stringResource(Res.string.hud_zone_final) to Palette.Lime
    }
    // In the last seconds the chip ticks with the countdown.
    val tick = remember { Animatable(1f) }
    val seconds = moment.millisLeft?.let { (it + 999) / 1000 }
    LaunchedEffect(seconds, moment.cue) {
        if (moment.cue == ZoneCue.COUNTDOWN || moment.cue == ZoneCue.SHRUNK) {
            tick.snapTo(1.15f)
            tick.animateTo(1f, Motion.pop())
        }
    }
    PopChip(
        text = text,
        color = color,
        contentColor = Palette.Ink,
        border = Palette.Ink,
        modifier = Modifier.graphicsLayer {
            scaleX = tick.value
            scaleY = tick.value
        },
    )
}

/** A warning under the capsule: [text] (tagged [tag] for UI automation) on the alert's color. */
@Composable
fun AlertPill(alert: GameAlert, text: String, tag: String, modifier: Modifier = Modifier) {
    PopSurface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive },
        shape = RoundedCornerShape(18.dp),
        color = alert.color,
        shadow = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painter = painterResource(
                    if (alert == GameAlert.IN_BUILDING) Res.drawable.ic_building else Res.drawable.ic_warning,
                ),
                contentDescription = null,
                tint = Palette.Ink,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                color = Palette.Ink,
                modifier = Modifier.weight(1f).testTag(tag),
            )
        }
    }
}

/** The edge of the screen glowing and pulsing in the alert's color; draws only, never takes touches. */
@Composable
fun EdgeVignette(alert: GameAlert, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val strength = if (reduceMotion) {
        0.8f
    } else {
        val transition = rememberInfiniteTransition()
        val value by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(alert.periodMillis / 2, easing = FastOutSlowInEasing),
                RepeatMode.Reverse,
            ),
        )
        value
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        val glow = 90.dp.toPx()
        val color = alert.color
        val edge = color.copy(alpha = 0.55f * strength)
        drawRect(Brush.verticalGradient(listOf(edge, Color.Transparent), endY = glow), size = Size(size.width, glow))
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, edge), startY = size.height - glow, endY = size.height),
            topLeft = Offset(0f, size.height - glow),
            size = Size(size.width, glow),
        )
        drawRect(Brush.horizontalGradient(listOf(edge, Color.Transparent), endX = glow), size = Size(glow, size.height))
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, edge), startX = size.width - glow, endX = size.width),
            topLeft = Offset(size.width - glow, 0f),
            size = Size(glow, size.height),
        )
        val stroke = 6.dp.toPx()
        drawRect(
            color.copy(alpha = strength),
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(stroke),
        )
    }
}

/** A round control at the bottom of the map with a small label under it. */
@Composable
fun RoundControl(
    icon: DrawableResource,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PopStyle = PopStyle.Dark,
    size: Dp = 56.dp,
    badge: Int = 0,
    badgeTag: String? = null,
    buttonModifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PopIconButton(
            icon = icon,
            contentDescription = label,
            onClick = onClick,
            style = style,
            size = size,
            iconSize = size * 0.42f,
            badge = badge,
            badgeTag = badgeTag,
            modifier = buttonModifier,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.Ink)
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clearAndSetSemantics {},
        )
    }
}

/**
 * The digits of a catch code as tiles. For accessibility and UI automation the row is one element with the whole
 * code as its text (tagged [tag]). A new code flips in digit by digit.
 */
@Composable
fun CodeTiles(code: String, tag: String, modifier: Modifier = Modifier, tileWidth: Dp = 62.dp) {
    Row(
        modifier = modifier.clearAndSetSemantics {
            testTag = tag
            text = AnnotatedString(code)
        },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        code.forEachIndexed { index, digit ->
            Box(
                modifier = Modifier
                    .width(tileWidth)
                    .height(tileWidth * 1.15f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Palette.Lime)
                    .border(PopBorder, Palette.Ink, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = digit,
                    transitionSpec = {
                        val delay = index * DIGIT_STAGGER_MILLIS
                        (
                            slideInVertically(tween(Motion.BASE_MILLIS, delay)) { it } +
                                fadeIn(tween(Motion.BASE_MILLIS, delay))
                            )
                            .togetherWith(
                                slideOutVertically(tween(Motion.BASE_MILLIS, delay)) { -it } +
                                    fadeOut(tween(Motion.BASE_MILLIS, delay)),
                            )
                    },
                ) { shown ->
                    Text(text = shown.toString(), style = Hovanki.text.code.copy(fontSize = 34.sp), color = Palette.Ink)
                }
            }
        }
    }
}

private const val DIGIT_STAGGER_MILLIS = 40
private const val SHAKE_STEP_MILLIS = 50

/**
 * Typing a catch code: [digits] tiles over an invisible field (tagged [tag]); the next tile to fill has a lime
 * frame. Each new [shakes] (a wrong code) shakes the tiles.
 */
@Composable
fun CodeInput(
    value: String,
    onValueChange: (String) -> Unit,
    digits: Int,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shakes: Int = 0,
) {
    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakes) {
        if (shakes == 0) return@LaunchedEffect
        for (offset in listOf(12f, -10f, 8f, -6f, 3f, 0f)) shake.animateTo(offset, tween(SHAKE_STEP_MILLIS))
    }
    BasicTextField(
        value = value,
        onValueChange = { typed -> onValueChange(typed.filter(Char::isDigit).take(digits)) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = modifier.testTag(tag).graphicsLayer { translationX = shake.value * density },
        decorationBox = { field ->
            Box {
                // The real field stays for the cursor and the keyboard, but only the tiles are seen.
                Box(modifier = Modifier.size(1.dp).graphicsLayer { alpha = 0f }) { field() }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(digits) { index ->
                        val char = value.getOrNull(index)
                        val isNext = index == value.length
                        Box(
                            modifier = Modifier
                                .width(58.dp)
                                .height(64.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (char != null) Palette.Sand else Palette.Paper)
                                .border(
                                    width = if (isNext) 3.dp else 2.dp,
                                    color = if (isNext) Palette.Ink else Palette.Ink3,
                                    shape = RoundedCornerShape(16.dp),
                                )
                                .then(if (isNext) Modifier.background(Palette.Lime.copy(alpha = 0.35f)) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = char?.toString().orEmpty(),
                                style = Hovanki.text.code.copy(fontSize = 28.sp),
                                color = Palette.Ink,
                            )
                        }
                    }
                }
            }
        },
    )
}

/**
 * The moment the seekers go out: seekers get «Go seek!» over the whole screen, hiders a pink «The seekers are out!»
 * at the top. Draws only: taps go through to the game.
 */
@Composable
fun PhaseFlash(phase: GamePhase, role: Role, reduceMotion: Boolean) {
    var previous by rememberSaveable { mutableStateOf(phase) }
    var flash by remember { mutableStateOf<Role?>(null) }
    LaunchedEffect(phase) {
        if (previous == GamePhase.HIDING && phase == GamePhase.SEEKING) {
            flash = role
            delay(if (role == Role.SEEKER) SEEK_FLASH_MILLIS else HIDER_FLASH_MILLIS)
            flash = null
        }
        previous = phase
    }
    AnimatedVisibility(
        visible = flash == Role.SEEKER,
        enter = if (reduceMotion) fadeIn() else fadeIn() + scaleIn(initialScale = 0.6f, animationSpec = Motion.pop()),
        exit = fadeOut(),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Palette.Lime.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(Res.string.flash_seek),
                style = MaterialTheme.typography.displayLarge,
                color = Palette.Ink,
            )
        }
    }
    AnimatedVisibility(
        visible = flash == Role.HIDER,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(16.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            PopSurface(color = Palette.Pink, shape = RoundedCornerShape(20.dp), shadow = 4.dp) {
                Text(
                    text = stringResource(Res.string.flash_seekers_out),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Palette.Ink,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
        }
    }
}

private const val SEEK_FLASH_MILLIS = 1_600L
private const val HIDER_FLASH_MILLIS = 3_000L
