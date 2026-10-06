package app.hovanki.client.ui.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.explain_activity
import app.hovanki.client.resources.explain_activity_text
import app.hovanki.client.resources.explain_buildings
import app.hovanki.client.resources.explain_buildings_text
import app.hovanki.client.resources.explain_center
import app.hovanki.client.resources.explain_center_text
import app.hovanki.client.resources.explain_checkpoints
import app.hovanki.client.resources.explain_checkpoints_text
import app.hovanki.client.resources.explain_glow
import app.hovanki.client.resources.explain_glow_text
import app.hovanki.client.resources.explain_open_building
import app.hovanki.client.resources.explain_open_building_text
import app.hovanki.client.resources.explain_open_game
import app.hovanki.client.resources.explain_open_game_text
import app.hovanki.client.resources.explain_perks
import app.hovanki.client.resources.explain_perks_text
import app.hovanki.client.resources.explain_pickups
import app.hovanki.client.resources.explain_pickups_text
import app.hovanki.client.resources.explain_pocket
import app.hovanki.client.resources.explain_pocket_text
import app.hovanki.client.resources.explain_precision
import app.hovanki.client.resources.explain_precision_text
import app.hovanki.client.resources.explain_proximity
import app.hovanki.client.resources.explain_proximity_text
import app.hovanki.client.resources.explain_quests
import app.hovanki.client.resources.explain_quests_text
import app.hovanki.client.resources.explain_radar
import app.hovanki.client.resources.explain_radar_text
import app.hovanki.client.resources.explain_running
import app.hovanki.client.resources.explain_sense
import app.hovanki.client.resources.explain_sense_text
import app.hovanki.client.resources.explain_shape
import app.hovanki.client.resources.explain_shape_text
import app.hovanki.client.resources.explain_shrink
import app.hovanki.client.resources.explain_shrink_text
import app.hovanki.client.resources.hud_radar_burning
import app.hovanki.client.resources.hud_radar_hot
import app.hovanki.client.resources.hud_radar_warm
import app.hovanki.client.resources.reason_glow
import app.hovanki.client.resources.settings_shape_circle
import app.hovanki.client.resources.settings_shape_streets
import app.hovanki.client.ui.common.rememberReduceMotion
import app.hovanki.client.ui.theme.Palette
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * What a «?» shows instead of a paragraph (docs/adr/0014-settings-lobby-redesign-open-buildings.md, section 2.6): a
 * short loop drawn on a Canvas in the palette's colors, a title and one sentence. [loopMillis]: one round of the loop.
 */
enum class Explainer(val title: StringResource, val text: StringResource, val loopMillis: Int) {
    SHAPE(Res.string.explain_shape, Res.string.explain_shape_text, 4_000),
    SHRINK(Res.string.explain_shrink, Res.string.explain_shrink_text, 4_500),
    GLOW(Res.string.explain_glow, Res.string.explain_glow_text, 4_000),
    BUILDINGS(Res.string.explain_buildings, Res.string.explain_buildings_text, 5_000),
    OPEN_BUILDING(Res.string.explain_open_building, Res.string.explain_open_building_text, 5_000),
    CENTER(Res.string.explain_center, Res.string.explain_center_text, 5_000),
    OPEN_GAME(Res.string.explain_open_game, Res.string.explain_open_game_text, 4_000),
    RADAR(Res.string.explain_radar, Res.string.explain_radar_text, 4_000),
    SENSE(Res.string.explain_sense, Res.string.explain_sense_text, 4_000),
    PROXIMITY(Res.string.explain_proximity, Res.string.explain_proximity_text, 4_500),
    POCKET(Res.string.explain_pocket, Res.string.explain_pocket_text, 4_000),
    PRECISION(Res.string.explain_precision, Res.string.explain_precision_text, 4_000),
    ACTIVITY(Res.string.explain_activity, Res.string.explain_activity_text, 3_000),
    QUESTS(Res.string.explain_quests, Res.string.explain_quests_text, 4_000),
    PERKS(Res.string.explain_perks, Res.string.explain_perks_text, 3_000),
    CHECKPOINTS(Res.string.explain_checkpoints, Res.string.explain_checkpoints_text, 4_000),
    PICKUPS(Res.string.explain_pickups, Res.string.explain_pickups_text, 4_000),
}

/**
 * [explainer]'s loop, over the whole [modifier] size (drawn for 172 × 140 and scaled). It runs only while on screen
 * (Compose stops it with the screen); with «reduce motion» it holds one telling frame.
 */
@Composable
fun ExplainerAnimation(explainer: Explainer, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val progress = if (reduceMotion) {
        STILL_FRAME
    } else {
        rememberInfiniteTransition(label = "explainer").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(explainer.loopMillis, easing = LinearEasing), RepeatMode.Restart),
            label = "loop",
        ).value
    }
    val labels = ExplainerLabels(
        circle = stringResource(Res.string.settings_shape_circle),
        streets = stringResource(Res.string.settings_shape_streets),
        warm = stringResource(Res.string.hud_radar_warm),
        hot = stringResource(Res.string.hud_radar_hot),
        burning = stringResource(Res.string.hud_radar_burning),
        glow = stringResource(Res.string.reason_glow),
        running = stringResource(Res.string.explain_running),
    )
    val measurer = rememberTextMeasurer()
    Box(modifier = modifier.clip(RoundedCornerShape(16.dp)).background(LAND)) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val k = minOf(size.width / DESIGN_WIDTH, size.height / DESIGN_HEIGHT)
            // Centered: the design box keeps its proportions on any card.
            translate((size.width - DESIGN_WIDTH * k) / 2, (size.height - DESIGN_HEIGHT * k) / 2) {
                Sketch(this, k, measurer).draw(explainer, progress, labels)
            }
        }
    }
}

/** The words a loop writes on the map. */
private class ExplainerLabels(
    val circle: String,
    val streets: String,
    val warm: String,
    val hot: String,
    val burning: String,
    val glow: String,
    val running: String,
)

/** Draws in design units (172 × 140) scaled by [k]. */
private class Sketch(private val scope: DrawScope, private val k: Float, private val measurer: TextMeasurer) {
    private fun p(x: Float, y: Float) = Offset(x * k, y * k)

    fun draw(explainer: Explainer, t: Float, labels: ExplainerLabels) = when (explainer) {
        Explainer.SHAPE -> shape(t, labels)
        Explainer.SHRINK -> shrink(t)
        Explainer.GLOW -> glow(t, labels)
        Explainer.BUILDINGS -> buildingRule(t)
        Explainer.OPEN_BUILDING -> openBuilding(t)
        Explainer.CENTER -> center(t)
        Explainer.OPEN_GAME -> openGame(t)
        Explainer.RADAR -> radar(t, labels)
        Explainer.SENSE -> sense(t)
        Explainer.PROXIMITY -> proximity(t)
        Explainer.POCKET -> pocket(t, labels)
        Explainer.PRECISION -> precision(t)
        Explainer.ACTIVITY -> activity(t, labels)
        Explainer.QUESTS -> quests(t)
        Explainer.PERKS -> perks(t)
        Explainer.CHECKPOINTS -> checkpoints(t)
        Explainer.PICKUPS -> pickups(t)
    }

    // ---- The loops ----

    private fun shape(t: Float, labels: ExplainerLabels) {
        streets(listOf(22f, 64f, 104f, 132f), listOf(18f, 64f, 112f, 156f))
        forbidden(26f, 30f, 14f, 12f)
        forbidden(72f, 30f, 32f, 10f)
        forbidden(120f, 72f, 12f, 24f)
        forbidden(26f, 112f, 28f, 12f)
        val circle = 1f - window(t, 0.42f, 0.5f) + window(t, 0.92f, 1f)
        ring(listOf(circlePath(88f, 84f, 44f)), alpha = circle)
        val blocks = Path().apply {
            moveTo(18f * k, 64f * k)
            listOf(64f to 64f, 64f to 22f, 112f to 22f, 112f to 64f, 156f to 64f, 156f to 104f, 112f to 104f)
                .plus(listOf(112f to 132f, 64f to 132f, 64f to 104f, 18f to 104f))
                .forEach { (x, y) -> lineTo(x * k, y * k) }
            close()
        }
        ring(listOf(blocks), alpha = 1f - circle)
        chip(if (circle > 0.5f) labels.circle else labels.streets, 10f, 8f, Palette.Ink, Color.White)
    }

    private fun shrink(t: Float) {
        streets(listOf(30f, 100f), listOf(40f, 130f))
        val r0 = 56f
        val r1 = 41f
        val r2 = 26f
        val radius = when {
            t < 0.2f -> r0
            t < 0.33f -> lerp(r0, r1, ease(window(t, 0.2f, 0.33f)))
            t < 0.53f -> r1
            t < 0.66f -> lerp(r1, r2, ease(window(t, 0.53f, 0.66f)))
            t < 0.9f -> r2
            else -> lerp(r2, r0, ease(window(t, 0.9f, 1f)))
        }
        val next = when {
            t < 0.33f -> r1
            t < 0.66f -> r2
            else -> null
        }
        val blinking = (t in 0.1f..0.2f) || (t in 0.43f..0.53f)
        if (next != null && (blinking || radius > next + 0.5f)) {
            val band = Path().apply {
                addCircle(86f, 70f, radius)
                addCircle(86f, 70f, next)
                fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
            }
            val alpha = if (blinking) 0.15f + 0.35f * pulse(t * 10f) else 0.3f
            scope.drawPath(band, Palette.Pink.copy(alpha = alpha))
        }
        if (next != null) {
            scope.drawPath(circlePath(86f, 70f, next), Palette.Ink, style = dashed(1.8f))
        }
        val moving = (t in 0.2f..0.33f) || (t in 0.53f..0.66f)
        ring(listOf(circlePath(86f, 70f, radius)), core = if (moving) Palette.Pink else Palette.Green)
    }

    private fun glow(t: Float, labels: ExplainerLabels) {
        streets(listOf(50f, 110f), listOf(60f, 124f))
        val hiders = listOf(p(36f, 32f), p(100f, 78f), p(148f, 28f))
        val on = window(t, 0.16f, 0.2f) - window(t, 0.38f, 0.42f)
        if (on > 0f) {
            val ping = (t * 4f) % 1f
            hiders.forEach { at ->
                scope.drawCircle(Palette.Hider.copy(alpha = on * (1f - ping) * 0.6f), 7f * k * (1f + 1.6f * ping), at)
                dot(at, Palette.Hider, alpha = on)
            }
            chip(labels.glow, 60f, 116f, Palette.Orange, Palette.Ink, alpha = on)
        }
        val marks = window(t, 0.38f, 0.44f) - window(t, 0.94f, 1f)
        if (marks > 0f) hiders.forEach { mark(it, marks) }
    }

    private fun buildingRule(t: Float) {
        streets(listOf(116f), listOf(24f))
        house(92f, 34f, 58f, 56f, open = false)
        val walk = ease(window(t, 0f, 0.25f))
        val at = p(lerp(43f, 121f, walk), lerp(114f, 62f, walk))
        val counting = t in 0.28f..0.78f
        if (counting) {
            val left = 1f - window(t, 0.28f, 0.78f)
            scope.drawArc(
                color = Palette.Pink,
                startAngle = -90f,
                sweepAngle = 360f * left,
                useCenter = false,
                topLeft = Offset(at.x - 13f * k, at.y - 13f * k),
                size = Size(26f * k, 26f * k),
                style = Stroke(3f * k),
            )
        }
        val seen = window(t, 0.79f, 0.82f) - window(t, 0.97f, 1f)
        if (seen > 0f) {
            scope.drawCircle(Palette.Hider.copy(alpha = seen * 0.25f), 16f * k, at)
            eye(at, seen)
        } else {
            dot(at, Palette.Hider)
        }
    }

    private fun openBuilding(t: Float) {
        scope.drawRect(PARK, Offset.Zero, Size(DESIGN_WIDTH * k, DESIGN_HEIGHT * k))
        path(listOf(10f to 128f, 60f to 90f, 110f to 110f), Color.White, 4f)
        val opened = window(t, 0.36f, 0.4f) - window(t, 0.97f, 1f)
        house(92f, 34f, 58f, 56f, open = opened > 0.5f)
        val tap = window(t, 0.3f, 0.4f)
        if (tap in 0.01f..0.99f) {
            val ripple = (4f + 20f * tap) * k
            scope.drawCircle(Palette.Ink.copy(alpha = 1f - tap), ripple, p(121f, 62f), style = Stroke(3f * k))
        }
        val walk = ease(window(t, 0.48f, 0.75f))
        val at = p(lerp(43f, 121f, walk), lerp(114f, 62f, walk))
        dot(at, Palette.Hider)
        val ok = window(t, 0.77f, 0.8f) - window(t, 0.97f, 1f)
        if (ok > 0f) check(p(138f, 44f), ok)
    }

    private fun center(t: Float) {
        val pan = when {
            t < 0.1f -> Offset.Zero
            t < 0.35f -> lerpOffset(Offset.Zero, Offset(-30f, 18f), ease(window(t, 0.1f, 0.35f)))
            t < 0.45f -> Offset(-30f, 18f)
            t < 0.7f -> lerpOffset(Offset(-30f, 18f), Offset(24f, -12f), ease(window(t, 0.45f, 0.7f)))
            t < 0.8f -> Offset(24f, -12f)
            else -> lerpOffset(Offset(24f, -12f), Offset.Zero, ease(window(t, 0.8f, 1f)))
        }
        scope.translate(pan.x * k, pan.y * k) {
            streets(listOf(-20f, 20f, 72f, 126f, 170f), listOf(-40f, -10f, 52f, 112f, 170f, 210f))
            scope.drawRect(PARK, p(60f, 80f), Size(44f * k, 38f * k))
            forbidden(0f, 30f, 40f, 10f)
            forbidden(62f, 28f, 42f, 14f)
            forbidden(122f, 84f, 40f, 26f)
        }
        ring(listOf(circlePath(86f, 72f, 46f)))
        pin(p(86f, 72f))
    }

    private fun openGame(t: Float) {
        val route = listOf(20f to 110f, 50f to 60f, 90f to 120f, 150f to 30f)
        val curve = Path().apply {
            moveTo(route[0].first * k, route[0].second * k)
            cubicTo(
                route[1].first * k,
                route[1].second * k,
                route[2].first * k,
                route[2].second * k,
                route[3].first * k,
                route[3].second * k,
            )
        }
        scope.drawPath(curve, Palette.Ink.copy(alpha = 0.4f), style = dashed(1.5f))
        val late = t - SPECTATOR_LAG
        if (late >= 0f) mark(bezier(route, late), 1f)
        dot(bezier(route, t), Palette.Hider)
        chip("1:00", 8f, 8f, Palette.Ink, Palette.Green)
    }

    private fun radar(t: Float, labels: ExplainerLabels) {
        scope.drawLine(Color.White, p(0f, 70f), p(DESIGN_WIDTH, 70f), 10f * k)
        val hider = p(140f, 70f)
        scope.drawCircle(Palette.Hider.copy(alpha = 0.25f), 8f * k, hider)
        scope.drawCircle(Palette.Hider, 8f * k, hider, style = dashed(2f))
        val seeker = p(lerp(28f, 108f, ease(window(t, 0.05f, 0.8f))), 70f)
        val ping = (t * 3f) % 1f
        val wave = 9f * k * (1f + 2.2f * ping)
        scope.drawCircle(Palette.Seeker.copy(alpha = 1f - ping), wave, seeker, style = Stroke(3f * k))
        dot(seeker, Palette.Seeker)
        val (text, color) = when {
            t < 0.33f -> labels.warm to Palette.Sand
            t < 0.66f -> labels.hot to Palette.Orange
            else -> labels.burning to Palette.Pink
        }
        chip(text, 60f, 12f, color, Palette.Ink)
    }

    private fun sense(t: Float) {
        scope.drawLine(Color.White, p(0f, 70f), p(DESIGN_WIDTH, 70f), 10f * k)
        dot(p(lerp(20f, 96f, ease(window(t, 0.05f, 0.7f))), 70f), Palette.Seeker)
        val buzzing = t in 0.55f..0.9f
        val shake = if (buzzing) sin(t * 160f) * 3f else 0f
        phone(p(132f + shake, 70f), Palette.Hider, Palette.PinkLight)
        if (buzzing) {
            listOf(-1f, 1f).forEach { side ->
                arc(p(132f + side * 24f, 70f), 10f, if (side < 0) 120f else -60f)
                arc(p(132f + side * 31f, 70f), 15f, if (side < 0) 120f else -60f)
            }
        }
    }

    private fun proximity(t: Float) {
        scope.drawLine(Color.White, p(0f, 78f), p(DESIGN_WIDTH, 78f), 10f * k)
        val hider = p(130f, 78f)
        scope.drawCircle(Palette.Hider.copy(alpha = 0.1f), 24f * k, hider)
        scope.drawCircle(Palette.Ink, 24f * k, hider, style = dashed(1.8f))
        dot(hider, Palette.Hider)
        val x = when {
            t < 0.35f -> lerp(30f, 80f, ease(window(t, 0f, 0.35f)))
            t < 0.55f -> 80f
            t < 0.8f -> lerp(80f, 118f, ease(window(t, 0.55f, 0.8f)))
            else -> 118f
        }
        dot(p(x, 78f), Palette.Seeker)
        if (t in 0.36f..0.55f) chip("×", 72f, 20f, Palette.Pink, Palette.Ink)
        if (t > 0.82f) check(p(86f, 32f), 1f)
    }

    private fun pocket(t: Float, labels: ExplainerLabels) {
        dot(p(36f, 76f), Palette.Seeker)
        val inPocket = ease(window(t, 0.4f, 0.55f)) - ease(window(t, 0.92f, 1f))
        // The pocket: the phone slides into it, the screen goes dark.
        val pocket = Path().apply {
            moveTo(108f * k, 70f * k)
            lineTo(156f * k, 70f * k)
            lineTo(156f * k, 104f * k)
            cubicTo(156f * k, 124f * k, 108f * k, 124f * k, 108f * k, 104f * k)
            close()
        }
        val screen = if (inPocket > 0.5f) Palette.Ink else Palette.PinkLight
        phone(p(132f, lerp(56f, 92f, inPocket)), Palette.Hider, screen)
        scope.drawPath(pocket, Palette.Sand)
        scope.drawPath(pocket, Palette.Ink, style = Stroke(2.5f * k))
        val (text, color) = if (inPocket > 0.5f) labels.warm to Palette.Sand else labels.hot to Palette.Orange
        chip(text, 18f, 18f, color, Palette.Ink)
    }

    private fun precision(t: Float) {
        phone(p(40f, 70f), Palette.Ink, Palette.Green)
        phone(p(132f, 70f), Palette.Ink, Palette.Green)
        val near = ease(window(t, 0.1f, 0.85f))
        val meters = lerp(12f, 2f, near).toInt()
        val angle = lerp(-25f, 0f, near) + sin(t * 12f) * 4f
        scope.rotate(angle, p(86f, 70f)) {
            scope.drawLine(Palette.Ink, p(66f, 70f), p(106f, 70f), 4f * k, cap = StrokeCap.Round)
            val head = Path().apply {
                moveTo(112f * k, 70f * k)
                lineTo(100f * k, 62f * k)
                lineTo(100f * k, 78f * k)
                close()
            }
            scope.drawPath(head, Palette.Ink)
        }
        chip("$meters m", 70f, 16f, Palette.Green, Palette.Ink)
    }

    private fun activity(t: Float, labels: ExplainerLabels) {
        scope.drawLine(Color.White, p(0f, 90f), p(DESIGN_WIDTH, 90f), 10f * k)
        val x = lerp(20f, 150f, t)
        listOf(10f, 20f, 30f).forEach { back ->
            val y = 86f + back / 10f
            val trail = Palette.Hider.copy(alpha = 0.5f)
            scope.drawLine(trail, p(x - back - 6f, y), p(x - back, y), 2.5f * k, StrokeCap.Round)
        }
        dot(p(x, 90f), Palette.Hider)
        if (t > 0.15f) chip(labels.running, 60f, 24f, Palette.Green, Palette.Ink)
    }

    private fun quests(t: Float) {
        path(listOf(20f to 118f, 70f to 108f, 120f to 88f), Color.White, 8f)
        flag(p(128f, 88f))
        val walk = ease(window(t, 0.05f, 0.6f))
        dot(p(lerp(26f, 120f, walk), lerp(116f, 88f, walk)), Palette.Hider)
        val spark = window(t, 0.62f, 0.95f)
        if (spark > 0f && spark < 1f) {
            chip("+3", 124f, 60f - 24f * spark, Palette.Green, Palette.Ink, alpha = 1f - spark * 0.5f)
        }
    }

    private fun perks(t: Float) {
        listOf(Palette.Pink, Palette.Green, Palette.Paper).forEachIndexed { index, color ->
            val start = index * 0.12f
            val pop = window(t, start, start + 0.12f)
            val scale = if (pop < 1f) 0.6f + 0.55f * sin(pop * PI.toFloat()) + 0.4f * pop else 1f
            val at = p(40f + index * 46f, 70f)
            scope.drawCircle(color, 20f * k * scale, at)
            scope.drawCircle(Palette.Ink, 20f * k * scale, at, style = Stroke(2.5f * k))
        }
    }

    private fun checkpoints(t: Float) {
        scope.drawRect(Palette.Ink, p(131f, 88f), Size(6f * k, 36f * k))
        scope.drawRoundRect(Color.White, p(108f, 36f), Size(52f * k, 52f * k), CornerRadius(6f * k))
        val qr = Size(52f * k, 52f * k)
        scope.drawRoundRect(Palette.Ink, p(108f, 36f), qr, CornerRadius(6f * k), style = Stroke(2.5f * k))
        listOf(p(116f, 44f), p(140f, 44f), p(116f, 68f)).forEach {
            scope.drawRect(Palette.Ink, it, Size(12f * k, 12f * k))
        }
        scope.drawRect(Palette.Ink, p(140f, 68f), Size(5f * k, 5f * k))
        phone(p(lerp(30f, 76f, ease(window(t, 0.05f, 0.55f))), 64f), Palette.Ink, Palette.Green)
        val scan = window(t, 0.58f, 0.78f)
        if (scan > 0f && scan < 1f) scope.drawRect(Palette.Green, p(104f, 38f + 46f * scan), Size(60f * k, 4f * k))
        if (t > 0.8f) chip("+2", 116f, 12f, Palette.Green, Palette.Ink)
    }

    private fun pickups(t: Float) {
        streets(listOf(96f), listOf(56f))
        val taken = t > 0.6f
        if (!taken) {
            scope.drawCircle(Palette.Pink, 8f * k, p(130f, 60f))
            scope.drawCircle(Palette.Ink, 8f * k, p(130f, 60f), style = Stroke(2f * k))
        }
        val walk = ease(window(t, 0.05f, 0.6f))
        dot(p(lerp(24f, 128f, walk), lerp(118f, 62f, walk)), Palette.Hider)
        val sparkle = window(t, 0.6f, 0.85f)
        if (sparkle > 0f && sparkle < 1f) {
            repeat(6) { i ->
                val angle = i * PI.toFloat() / 3f
                val r = (10f + 16f * sparkle) * k
                val at = Offset(130f * k + cos(angle) * r, 60f * k + sin(angle) * r)
                scope.drawCircle(Palette.Pink.copy(alpha = 1f - sparkle), 3f * k, at)
            }
        }
    }

    // ---- Pieces ----

    private fun streets(rows: List<Float>, columns: List<Float>) {
        rows.forEach { y -> scope.drawLine(Color.White, p(-60f, y), p(DESIGN_WIDTH + 60f, y), 7f * k) }
        columns.forEach { x -> scope.drawLine(Color.White, p(x, -60f), p(x, DESIGN_HEIGHT + 60f), 7f * k) }
    }

    private fun forbidden(x: Float, y: Float, w: Float, h: Float) {
        scope.drawRect(Palette.Pink.copy(alpha = 0.3f), p(x, y), Size(w * k, h * k))
        scope.drawRect(Palette.Pink, p(x, y), Size(w * k, h * k), style = Stroke(1f * k))
    }

    private fun house(x: Float, y: Float, w: Float, h: Float, open: Boolean) {
        if (open) {
            scope.drawRect(Palette.Green, p(x, y), Size(w * k, h * k))
            scope.drawRect(Palette.Ink, p(x, y), Size(w * k, h * k), style = dashed(2f))
        } else {
            scope.drawRect(Palette.Pink.copy(alpha = 0.3f), p(x, y), Size(w * k, h * k))
            scope.drawRect(Palette.Pink, p(x, y), Size(w * k, h * k), style = Stroke(2f * k))
        }
    }

    private fun ring(shapes: List<Path>, alpha: Float = 1f, core: Color = Palette.Green) {
        if (alpha <= 0f) return
        shapes.forEach { shape ->
            scope.drawPath(shape, Palette.Ink.copy(alpha = alpha), style = Stroke(7f * k, join = StrokeJoin.Round))
            scope.drawPath(shape, core.copy(alpha = alpha), style = Stroke(3.5f * k, join = StrokeJoin.Round))
        }
    }

    private fun circlePath(x: Float, y: Float, r: Float) = Path().apply { addCircle(x, y, r) }

    private fun Path.addCircle(x: Float, y: Float, r: Float) =
        addOval(androidx.compose.ui.geometry.Rect(Offset((x - r) * k, (y - r) * k), Size(2 * r * k, 2 * r * k)))

    private fun dot(at: Offset, color: Color, border: Color = Color.White, alpha: Float = 1f) {
        scope.drawCircle(color.copy(alpha = alpha), 7f * k, at)
        scope.drawCircle(border.copy(alpha = alpha), 7f * k, at, style = Stroke(2.5f * k))
    }

    /** Where a hider was: grey with a dashed white border, like the map's glow marks. */
    private fun mark(at: Offset, alpha: Float) {
        scope.drawCircle(Palette.Stale.copy(alpha = 0.8f * alpha), 6f * k, at)
        scope.drawCircle(Color.White.copy(alpha = alpha), 6f * k, at, style = dashed(2f))
    }

    private fun eye(at: Offset, alpha: Float) {
        scope.drawCircle(Palette.Seeker.copy(alpha = alpha), 10f * k, at)
        scope.drawCircle(Palette.Ink.copy(alpha = alpha), 10f * k, at, style = Stroke(2f * k))
        scope.drawOval(Color.White.copy(alpha = alpha), Offset(at.x - 7f * k, at.y - 4f * k), Size(14f * k, 8f * k))
        scope.drawCircle(Palette.Ink.copy(alpha = alpha), 2f * k, at)
    }

    private fun check(at: Offset, alpha: Float) {
        scope.drawCircle(Palette.Green.copy(alpha = alpha), 10f * k, at)
        scope.drawCircle(Palette.Ink.copy(alpha = alpha), 10f * k, at, style = Stroke(2f * k))
        val tick = Path().apply {
            moveTo(at.x - 4.5f * k, at.y)
            lineTo(at.x - 1.5f * k, at.y + 3f * k)
            lineTo(at.x + 4f * k, at.y - 3.5f * k)
        }
        scope.drawPath(tick, Palette.Ink.copy(alpha = alpha), style = Stroke(2.2f * k, cap = StrokeCap.Round))
    }

    private fun pin(at: Offset) {
        val head = Offset(at.x, at.y - 19f * k)
        val body = Path().apply {
            moveTo(at.x, at.y)
            lineTo(at.x - 8f * k, at.y - 15f * k)
            lineTo(at.x + 8f * k, at.y - 15f * k)
            close()
        }
        scope.drawPath(body, Palette.Green)
        scope.drawCircle(Palette.Green, 10f * k, head)
        scope.drawCircle(Palette.Ink, 10f * k, head, style = Stroke(2.2f * k))
        scope.drawCircle(Palette.Ink, 3.5f * k, head)
    }

    private fun phone(at: Offset, body: Color, screen: Color) {
        val topLeft = Offset(at.x - 14f * k, at.y - 24f * k)
        scope.drawRoundRect(body, topLeft, Size(28f * k, 48f * k), CornerRadius(7f * k))
        scope.drawRoundRect(Palette.Ink, topLeft, Size(28f * k, 48f * k), CornerRadius(7f * k), style = Stroke(2f * k))
        val glass = Offset(at.x - 10f * k, at.y - 18f * k)
        scope.drawRoundRect(screen, glass, Size(20f * k, 34f * k), CornerRadius(3f * k))
    }

    private fun flag(at: Offset) {
        scope.drawLine(Palette.Ink, at, Offset(at.x, at.y - 40f * k), 2.5f * k)
        val cloth = Path().apply {
            moveTo(at.x, at.y - 40f * k)
            lineTo(at.x + 26f * k, at.y - 40f * k)
            lineTo(at.x + 20f * k, at.y - 32f * k)
            lineTo(at.x + 26f * k, at.y - 24f * k)
            lineTo(at.x, at.y - 24f * k)
            close()
        }
        scope.drawPath(cloth, Palette.Green)
        scope.drawPath(cloth, Palette.Ink, style = Stroke(2f * k, join = StrokeJoin.Round))
    }

    private fun arc(at: Offset, radius: Float, start: Float) {
        scope.drawArc(
            color = Palette.Ink,
            startAngle = start,
            sweepAngle = 120f,
            useCenter = false,
            topLeft = Offset(at.x - radius * k, at.y - radius * k),
            size = Size(2 * radius * k, 2 * radius * k),
            style = Stroke(2.5f * k, cap = StrokeCap.Round),
        )
    }

    private fun path(points: List<Pair<Float, Float>>, color: Color, width: Float) {
        val line = Path().apply {
            moveTo(points.first().first * k, points.first().second * k)
            points.drop(1).forEach { (x, y) -> lineTo(x * k, y * k) }
        }
        scope.drawPath(line, color, style = Stroke(width * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    /** A small label with an ink border at ([x], [y]), top left, in design units. */
    private fun chip(text: String, x: Float, y: Float, background: Color, color: Color, alpha: Float = 1f) {
        val layout = measurer.measure(
            text,
            TextStyle(color = color.copy(alpha = alpha), fontSize = (11f * k).sp, fontWeight = FontWeight.ExtraBold),
        )
        val padding = 7f * k
        val box = Size(layout.size.width + 2 * padding, layout.size.height + 3f * k)
        scope.drawRoundRect(background.copy(alpha = alpha), p(x, y), box, CornerRadius(8f * k))
        val corner = CornerRadius(8f * k)
        scope.drawRoundRect(Palette.Ink.copy(alpha = alpha), p(x, y), box, corner, style = Stroke(1.8f * k))
        scope.drawText(layout, topLeft = Offset(x * k + padding, y * k + 1.5f * k))
    }

    private fun dashed(width: Float) = Stroke(
        width = width * k,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * k, 3f * k)),
    )

    private fun bezier(points: List<Pair<Float, Float>>, t: Float): Offset {
        val u = 1f - t
        val x = u * u * u * points[0].first + 3 * u * u * t * points[1].first + 3 * u * t * t * points[2].first +
            t * t * t * points[3].first
        val y = u * u * u * points[0].second + 3 * u * u * t * points[1].second + 3 * u * t * t * points[2].second +
            t * t * t * points[3].second
        return p(x, y)
    }
}

/** How far [t] is from [from] to [to], 0 before and 1 after. */
private fun window(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

private fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

private fun lerpOffset(from: Offset, to: Offset, fraction: Float) =
    Offset(lerp(from.x, to.x, fraction), lerp(from.y, to.y, fraction))

/** Slow at both ends. */
private fun ease(fraction: Float): Float = fraction * fraction * (3 - 2 * fraction)

/** 0 → 1 → 0 once per unit of [x]. */
private fun pulse(x: Float): Float = (1 - cos(2 * PI.toFloat() * x)) / 2

private const val DESIGN_WIDTH = 172f
private const val DESIGN_HEIGHT = 140f

/** The frame a loop holds with «reduce motion»: the moment that tells most. */
private const val STILL_FRAME = 0.7f

/** How far behind the spectators' dot runs, in loops. */
private const val SPECTATOR_LAG = 0.22f

private val LAND = Color(0xFFF1F1EE)
private val PARK = Color(0xFFDDEBD2)
