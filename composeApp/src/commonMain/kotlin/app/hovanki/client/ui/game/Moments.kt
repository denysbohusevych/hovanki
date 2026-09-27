package app.hovanki.client.ui.game

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.catch_confirmed
import app.hovanki.client.resources.caught_hint
import app.hovanki.client.resources.caught_title
import app.hovanki.client.resources.hint_seeker_hiding
import app.hovanki.client.resources.ic_eye_off
import app.hovanki.client.resources.seeker_wait_title
import app.hovanki.client.resources.start_hide
import app.hovanki.client.resources.start_wait
import app.hovanki.client.ui.common.Haptic
import app.hovanki.client.ui.common.formatCountdown
import app.hovanki.client.ui.common.rememberHaptics
import app.hovanki.client.ui.theme.Hovanki
import app.hovanki.client.ui.theme.Motion
import app.hovanki.client.ui.theme.Palette
import app.hovanki.client.ui.theme.color
import app.hovanki.client.ui.theme.onColor
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/*
 * Moments of the round that take the screen for a second or two (docs/design.md, «Фазы игры», «Находка»): the 3-2-1
 * at the start, the seeker waiting out the hiding phase, a confirmed catch. None of them takes touches: the round
 * goes on underneath, and the test tags under them stay where they are.
 */

/**
 * 3-2-1 over everything at the very start of the hiding phase, then «Hide!» or «Wait!». Follows server time, so the
 * phones count in step; skipped when the round screen opens later (a restart) and with reduced motion.
 */
@Composable
fun StartCountdown(hidingElapsedMillis: Long?, role: Role, reduceMotion: Boolean) {
    // Decided once, when the round screen opens: only a fresh start counts down.
    val startedAt = remember { hidingElapsedMillis?.takeIf { it in 0 until COUNTDOWN_WINDOW_MILLIS } }
    if (startedAt == null || reduceMotion) return
    // 3, 2, 1, then 0 for «Hide!»/«Wait!»; null when done.
    var step by remember { mutableStateOf<Int?>(null) }
    val play = rememberHaptics()
    LaunchedEffect(Unit) {
        var elapsed: Long = startedAt
        for (current in COUNTDOWN_FROM downTo 0) {
            val stepEnd = (COUNTDOWN_FROM + 1 - current) * COUNTDOWN_STEP_MILLIS
            if (elapsed >= stepEnd) continue
            step = current
            play(if (current == 0) Haptic.SUCCESS else Haptic.TICK)
            delay(stepEnd - elapsed)
            elapsed = stepEnd
        }
        step = null
    }
    var shown by remember { mutableIntStateOf(COUNTDOWN_FROM) }
    step?.let { shown = it }
    AnimatedVisibility(visible = step != null, enter = fadeIn(), exit = fadeOut(tween(Motion.SCREEN_MILLIS))) {
        val go = shown == 0
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (go) role.color.copy(alpha = 0.94f) else Palette.Ink.copy(alpha = 0.88f)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = shown,
                transitionSpec = {
                    (scaleIn(initialScale = 0.4f, animationSpec = Motion.pop()) + fadeIn()) togetherWith fadeOut()
                },
            ) { value ->
                if (value == 0) {
                    Text(
                        text = stringResource(if (role == Role.HIDER) Res.string.start_hide else Res.string.start_wait),
                        style = MaterialTheme.typography.displayLarge,
                        color = role.onColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    Text(
                        text = value.toString(),
                        style = Hovanki.text.timer.copy(fontSize = 180.sp, lineHeight = 190.sp),
                        color = Palette.Lime,
                    )
                }
            }
        }
    }
}

/** The seeker during the hiding phase: the map dimmed, «wait, no peeking» and the time left, big. */
@Composable
fun SeekerWaitLayer(millisLeft: Long?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(Palette.Ink.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_eye_off),
                contentDescription = null,
                tint = Palette.Orange,
                modifier = Modifier.size(44.dp),
            )
            Text(
                text = stringResource(Res.string.seeker_wait_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Text(
                text = millisLeft?.let(::formatCountdown) ?: "—",
                style = Hovanki.text.timer.copy(fontSize = 76.sp, lineHeight = 84.sp),
                color = Palette.Lime,
            )
            Text(
                text = stringResource(Res.string.hint_seeker_hiding),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The seeker's claim confirmed: a lime flash with «Got them!» and confetti. Only catches confirmed while the screen
 * is open count, not the ones already there when it opens.
 */
@Composable
fun CatchCelebration(confirmed: Set<CatchId>, reduceMotion: Boolean) {
    var seen by remember { mutableStateOf(confirmed) }
    var celebrations by remember { mutableIntStateOf(0) }
    LaunchedEffect(confirmed) {
        if (!seen.containsAll(confirmed)) celebrations++
        seen = seen + confirmed
    }
    var flashing by remember { mutableStateOf(false) }
    LaunchedEffect(celebrations) {
        if (celebrations == 0) return@LaunchedEffect
        flashing = true
        delay(CELEBRATION_FLASH_MILLIS)
        flashing = false
    }
    AnimatedVisibility(visible = flashing, enter = fadeIn(tween(Motion.FAST_MILLIS)), exit = fadeOut(tween(500))) {
        Box(
            modifier = Modifier.fillMaxSize().background(Palette.Lime.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) {
            val scale = remember { Animatable(if (reduceMotion) 1f else 0.5f) }
            LaunchedEffect(Unit) { scale.animateTo(1f, Motion.pop()) }
            Text(
                text = stringResource(Res.string.catch_confirmed),
                style = MaterialTheme.typography.displayLarge,
                color = Palette.Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp).scaled(scale.value),
            )
        }
    }
    if (celebrations > 0 && !reduceMotion) Confetti(key = celebrations)
}

/** The hider was caught while watching: a soft violet «You were found» for a moment, no confetti. */
@Composable
fun CaughtLayer(status: PlayerStatus) {
    var previous by remember { mutableStateOf(status) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(status) {
        if (previous == PlayerStatus.ACTIVE && status == PlayerStatus.CAUGHT) {
            visible = true
            delay(CAUGHT_MILLIS)
            visible = false
        }
        previous = status
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(400)), exit = fadeOut(tween(600))) {
        Box(
            modifier = Modifier.fillMaxSize().background(Palette.Violet.copy(alpha = 0.94f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.caught_title),
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(Res.string.caught_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Circles and little waves in the palette's colors bursting from the middle of the screen and falling
 * (docs/design.md, `celebrate`). Drawn on a [Canvas]; runs once per [key].
 */
@Composable
fun Confetti(key: Int, modifier: Modifier = Modifier) {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { progress.animateTo(1f, tween(CONFETTI_MILLIS, easing = LinearEasing)) }
    val particles = remember(key) {
        val random = Random(key)
        List(CONFETTI_PARTICLES) { ConfettiParticle.random(random) }
    }
    if (progress.value >= 1f) return
    Canvas(modifier = modifier.fillMaxSize()) {
        val seconds = progress.value * CONFETTI_MILLIS / 1000f
        val unit = size.minDimension
        val origin = Offset(size.width / 2, size.height * 0.42f)
        val alpha = if (progress.value < 0.7f) 1f else (1f - progress.value) / 0.3f
        for (particle in particles) {
            val position = Offset(
                x = origin.x + particle.velocityX * unit * seconds,
                y = origin.y + particle.velocityY * unit * seconds + 0.5f * GRAVITY * unit * seconds * seconds,
            )
            val color = particle.color.copy(alpha = alpha)
            val particleSize = particle.sizeDp.dp.toPx()
            translate(position.x, position.y) {
                rotate(particle.spinDegrees * seconds + particle.angleDegrees, pivot = Offset.Zero) {
                    if (particle.isWave) {
                        drawPath(
                            path = wavePath(particleSize * 2.2f, particleSize * 0.35f),
                            color = color,
                            style = Stroke(width = particleSize * 0.32f, cap = StrokeCap.Round),
                        )
                    } else {
                        drawCircle(color = color, radius = particleSize / 2, center = Offset.Zero)
                    }
                }
            }
        }
    }
}

private class ConfettiParticle(
    /** Screen sizes (the shorter side) per second. */
    val velocityX: Float,
    val velocityY: Float,
    val color: Color,
    val sizeDp: Float,
    val isWave: Boolean,
    val angleDegrees: Float,
    val spinDegrees: Float,
) {
    companion object {
        private val COLORS = listOf(Palette.Lime, Palette.Violet, Palette.Orange, Palette.Pink, Palette.Ink)

        fun random(random: Random): ConfettiParticle {
            // Mostly upwards, fanning out.
            val angle = PI + random.nextDouble() * PI
            val speed = 0.55 + random.nextDouble() * 0.85
            return ConfettiParticle(
                velocityX = (cos(angle) * speed).toFloat(),
                velocityY = (sin(angle) * speed).toFloat(),
                color = COLORS[random.nextInt(COLORS.size)],
                sizeDp = 7f + random.nextFloat() * 6f,
                isWave = random.nextInt(3) == 0,
                angleDegrees = random.nextFloat() * 360f,
                spinDegrees = (random.nextFloat() - 0.5f) * 720f,
            )
        }
    }
}

/** A short squiggle centered on the origin: two waves along x. */
private fun wavePath(length: Float, amplitude: Float): Path = Path().apply {
    val steps = 16
    for (i in 0..steps) {
        val x = -length / 2 + length * i / steps
        val y = amplitude * sin(2 * PI * 2 * i / steps).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
}

private fun Modifier.scaled(scale: Float): Modifier = graphicsLayer {
    scaleX = scale
    scaleY = scale
}

private const val COUNTDOWN_FROM = 3
private const val COUNTDOWN_STEP_MILLIS = 1_000L

/** The round screen opening later than this into the hiding phase does not count down. */
private const val COUNTDOWN_WINDOW_MILLIS = 2_500L
private const val CELEBRATION_FLASH_MILLIS = 1_300L
private const val CAUGHT_MILLIS = 2_800L
private const val CONFETTI_MILLIS = 1_400
private const val CONFETTI_PARTICLES = 70

/** Screen sizes (the shorter side) per second squared. */
private const val GRAVITY = 1.9f
