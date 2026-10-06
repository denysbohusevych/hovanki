package app.hovanki.client.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hovanki.client.ui.theme.Hovanki
import app.hovanki.client.ui.theme.Motion
import app.hovanki.client.ui.theme.Palette
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/*
 * The app's own components in the «Dopamine» style (docs/design.md, «Форма и глубина», «Компоненты»): ink outlines,
 * hard shadows without blur, and a press that moves the element onto its shadow.
 */

/** Thick outline of tactile elements (buttons, action cards, fields). */
val PopBorder = 2.5.dp

/** A hard shadow: [shape] offset down-right by [offset], no blur. Drawn outside the bounds, behind the content. */
fun Modifier.hardShadow(shape: Shape, offset: Dp = 4.dp, color: Color = Palette.Ink): Modifier = if (offset <= 0.dp) {
    this
} else {
    drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        val px = offset.toPx()
        translate(px, px) { drawOutline(outline, color) }
    }
}

/**
 * A surface in the style: [shape] filled with [color], an optional [border] and hard [shadow]. With [onClick] it is
 * pressable: it sinks onto its shadow (or, without one, shrinks a little) under the finger.
 */
@Composable
fun PopSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    color: Color = Palette.Paper,
    contentColor: Color = Palette.Ink,
    border: Color? = Palette.Ink,
    borderWidth: Dp = PopBorder,
    shadow: Dp = 0.dp,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    role: Role = Role.Button,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val pressed = isPressed && onClick != null && enabled
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (pressed) tween(Motion.PRESS_MILLIS) else Motion.release(),
    )
    val clickable = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .hardShadow(shape, shadow)
            .then(clickable)
            .graphicsLayer {
                if (shadow > 0.dp) {
                    val shift = shadow.toPx() * press
                    translationX = shift
                    translationY = shift
                } else {
                    val scale = 1f - PRESS_SHRINK * press
                    scaleX = scale
                    scaleY = scale
                }
            }
            .clip(shape)
            .background(color)
            .then(if (border != null) Modifier.border(borderWidth, border, shape) else Modifier),
        contentAlignment = contentAlignment,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

private const val PRESS_SHRINK = 0.03f

/** Colors of [PopButton] and friends (docs/design.md, «Компоненты»). */
enum class PopStyle(val container: Color, val content: Color, val border: Color?, val shadow: Boolean) {
    /** The screen's main action: green, outlined, with a hard shadow. One per screen. */
    Primary(Palette.Green, Palette.Ink, Palette.Ink, shadow = true),

    /** Ink with green text. */
    Dark(Palette.Ink, Palette.Green, null, shadow = false),
    Outline(Palette.Paper, Palette.Ink, Palette.Ink, shadow = false),

    /** A secondary action on a light background, without an outline. */
    Quiet(Palette.Sand, Palette.Ink, null, shadow = false),
    Hider(Palette.Hider, Color.White, Palette.Ink, shadow = false),

    /** Ink with green text, like [Dark], but outlined: it stands on light screens and on the map. */
    Seeker(Palette.Seeker, Palette.Green, Palette.Ink, shadow = false),
    Pink(Palette.Pink, Color.White, Palette.Ink, shadow = false),

    /** Destructive or against the flow (dispute, block). */
    Danger(Palette.Paper, Palette.PinkInk, Palette.PinkInk, shadow = false),

    /** Help in an SOS: the emergency number (docs/adr/0019-pause-and-sos.md). */
    Sos(Palette.Sos, Color.White, Palette.Ink, shadow = true),
}

/** A button in the style; [content] is laid out in a row, centered, with the style's colors and text. */
@Composable
fun PopButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: PopStyle = PopStyle.Primary,
    height: Dp = 56.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val border = if (enabled) style.border else Palette.Ink3.copy(alpha = 0.4f)
    val haptics = rememberHaptics()
    PopSurface(
        modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = height),
        shape = RoundedCornerShape(if (height >= 52.dp) 18.dp else 14.dp),
        color = if (enabled) style.container else Palette.Sand,
        contentColor = if (enabled) style.content else Palette.Ink3,
        border = border,
        shadow = if (enabled && style.shadow) 4.dp else 0.dp,
        onClick = {
            // The main actions answer with a light tap.
            if (style.shadow) haptics(Haptic.TAP)
            onClick()
        },
        enabled = enabled,
        contentAlignment = Alignment.Center,
    ) {
        ProvideTextStyle(textStyle) {
            Row(
                modifier = Modifier.padding(contentPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/** A [PopButton] with just [text], optionally after an [icon]. */
@Composable
fun PopButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: PopStyle = PopStyle.Primary,
    height: Dp = 56.dp,
    icon: DrawableResource? = null,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
) {
    PopButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        style = style,
        height = height,
        textStyle = textStyle,
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
        Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A round (or rounded) button with an [icon] only; [contentDescription] is what a screen reader says. A [badge] above
 * 0 shows a count in the corner, tagged [badgeTag].
 */
@Composable
fun PopIconButton(
    icon: DrawableResource,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PopStyle = PopStyle.Outline,
    size: Dp = 48.dp,
    iconSize: Dp = 22.dp,
    shape: Shape = CircleShape,
    enabled: Boolean = true,
    badge: Int = 0,
    badgeTag: String? = null,
) {
    Box(modifier = modifier) {
        PopSurface(
            modifier = Modifier
                .size(size)
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ),
            shape = shape,
            color = if (enabled) style.container else Palette.Sand,
            contentColor = if (enabled) style.content else Palette.Ink3,
            border = style.border,
            borderWidth = 2.dp,
            shadow = if (enabled && style.shadow) 3.dp else 0.dp,
            onClick = onClick,
            enabled = enabled,
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(iconSize))
        }
        if (badge > 0) {
            CountBadge(
                count = badge,
                modifier = Modifier.align(Alignment.TopEnd).padding(start = 0.dp),
                tag = badgeTag,
            )
        }
    }
}

/** A pink counter of new things (messages, invites). */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier, tag: String? = null) {
    val text = if (count > MAX_COUNT) "$MAX_COUNT+" else count.toString()
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
            .clip(CircleShape)
            .background(Palette.Pink)
            .border(2.dp, Palette.Ink, CircleShape)
            .padding(horizontal = 5.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = Color.White, fontSize = 11.sp, style = MaterialTheme.typography.labelSmall)
    }
}

private const val MAX_COUNT = 99

/** A card: white, outlined; [color] for the bold ones, [shadow] for the ones that are actions. */
@Composable
fun PopCard(
    modifier: Modifier = Modifier,
    color: Color = Palette.Paper,
    contentColor: Color = Palette.Ink,
    border: Color? = Palette.Ink,
    borderWidth: Dp = 2.dp,
    shadow: Dp = 0.dp,
    shape: Shape = RoundedCornerShape(20.dp),
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    PopSurface(
        modifier = modifier,
        shape = shape,
        color = color,
        contentColor = contentColor,
        border = border,
        borderWidth = borderWidth,
        shadow = shadow,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/** A small label: game settings in the lobby, a state next to a name. */
@Composable
fun PopChip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.Ink,
    contentColor: Color = Color.White,
    border: Color? = null,
    icon: DrawableResource? = null,
    onClick: (() -> Unit)? = null,
) {
    PopSurface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = color,
        contentColor = contentColor,
        border = border,
        borderWidth = 2.dp,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text = text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** A person's round avatar: the first letter of [name] on [color]. */
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.Sand,
    contentColor: Color = Palette.Ink,
    size: Dp = 36.dp,
    border: Color? = Palette.Ink,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .then(if (border != null) Modifier.border(2.dp, border, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().take(1).uppercase().ifEmpty { "?" },
            color = contentColor,
            style = MaterialTheme.typography.titleSmall,
            fontSize = (size.value * 0.4f).sp,
        )
    }
}

/** Colors of every text field: ink outline, white inside, ink text; errors in [Palette.PinkInk]. */
@Composable
fun popFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.Ink,
    unfocusedBorderColor = Palette.Ink,
    disabledBorderColor = Palette.Ink3.copy(alpha = 0.4f),
    focusedContainerColor = Palette.Paper,
    unfocusedContainerColor = Palette.Paper,
    disabledContainerColor = Palette.Sand,
    focusedLabelColor = Palette.Ink,
    unfocusedLabelColor = Palette.Ink2,
    cursorColor = Palette.Ink,
    focusedSupportingTextColor = Palette.Ink2,
    unfocusedSupportingTextColor = Palette.Ink2,
    errorBorderColor = Palette.PinkInk,
    errorLabelColor = Palette.PinkInk,
    errorSupportingTextColor = Palette.PinkInk,
    errorContainerColor = Palette.Paper,
)

/** Material's outlined text field in the style: rounded, ink outline, white inside. */
@Composable
fun PopTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        supportingText = supportingText,
        isError = isError,
        enabled = enabled,
        singleLine = singleLine,
        maxLines = maxLines,
        textStyle = textStyle,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = RoundedCornerShape(16.dp),
        colors = popFieldColors(),
    )
}

/**
 * A countdown as a ring: [progress] of it left (1 — all, 0 — none) as an arc of [color], [text] in the middle. Only
 * ever redrawn by its caller's ticks: no animation of its own.
 */
@Composable
fun CountdownRing(
    progress: Float,
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.Green,
    trackColor: Color = Palette.Ink2,
    textColor: Color = LocalContentColor.current,
    size: Dp = 48.dp,
    stroke: Dp = 4.dp,
) {
    val animatedColor by animateColorAsState(color, Motion.fast())
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val width = stroke.toPx()
            val inset = width / 2
            val arcSize = Size(this.size.width - width, this.size.height - width)
            drawArc(trackColor, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(width))
            drawArc(
                animatedColor,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width, cap = StrokeCap.Round),
            )
        }
        Text(text = text, color = textColor, style = MaterialTheme.typography.labelSmall)
    }
}

/** Small caps text: HUD labels, badges. */
@Composable
fun CapsText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(text = text.uppercase(), modifier = modifier, color = color, style = Hovanki.text.caps)
}
