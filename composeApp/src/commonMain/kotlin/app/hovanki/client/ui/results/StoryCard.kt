package app.hovanki.client.ui.results

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.hovanki.client.session.Award
import app.hovanki.client.session.HiderTally
import app.hovanki.client.session.catchesBy
import app.hovanki.client.session.hiderTally
import app.hovanki.client.session.searchMillisAt
import app.hovanki.client.ui.theme.Hovanki
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role

/**
 * The player's own result: how long a hider lasted into the search ([lastedMillis], null when the search never
 * started) and their [place] among [hiders] by it, a seeker's [finds].
 */
internal data class MyResult(
    val player: PlayerView,
    val lastedMillis: Long?,
    val place: Int,
    val hiders: Int,
    val finds: Int,
)

/** [MyResult] of the viewer of a finished game; null when they are not in its list. */
internal fun GameSnapshot.myResult(): MyResult? {
    val me = players.firstOrNull { it.id == this.me.playerId } ?: return null
    val hiders = players.filter { it.role == Role.HIDER }
    val searchEnd = searchMillisAt(finishedAtMillis ?: 0L)
    fun lasted(player: PlayerView): Long? =
        if (player.status == PlayerStatus.ACTIVE) searchEnd else player.outAtMillis?.let(::searchMillisAt)
    val mine = lasted(me)
    val place = 1 + hiders.count { other -> other.id != me.id && (lasted(other) ?: 0L) > (mine ?: 0L) }
    return MyResult(
        player = me,
        lastedMillis = mine.takeIf { me.role == Role.HIDER },
        place = place,
        hiders = hiders.size,
        finds = catchesBy(me.id),
    )
}

/**
 * What the story picture of the results shows (docs/adr/0024-instagram-stories.md): the viewer's own result and
 * awards, the round's length and the hiders' numbers. Never the map, a place, a time of day or anybody else's name:
 * the picture leaves the game for everybody to see.
 */
internal data class Story(
    val result: MyResult,
    val hidersWin: Boolean,
    val roundMillis: Long?,
    val tally: HiderTally,
    val awards: List<Award>,
)

/** The viewer's [Story] of a finished game, with their own [awards] only, at most [STORY_AWARDS]; the renderer drops the ones that don't fit. */
internal fun GameSnapshot.story(awards: List<Award>): Story? {
    val result = myResult() ?: return null
    val tally = hiderTally()
    return Story(
        result = result,
        hidersWin = tally.survived > 0,
        roundMillis = searchMillisAt(finishedAtMillis ?: 0L),
        tally = tally,
        awards = awards.filter { it.playerId == result.player.id }.take(STORY_AWARDS),
    )
}

internal const val STORY_AWARDS = 2

/** The texts of a [Story] in the player's language, put together on the results screen. */
internal data class StoryTexts(
    val brand: String,
    /** «GAME OVER · 24:13». */
    val caption: String,
    val headline: String,
    val name: String,
    /** How it went for the player: «You survived! · Place 1 of 4 hiders». */
    val line: String,
    val label: String,
    /** The number that matters, big: the time a hider lasted, a seeker's finds; null: none. */
    val value: String?,
    /** The hiders' numbers: label and count. */
    val tally: List<Pair<String, Int>>,
    /** The player's awards: title and what for. */
    val awards: List<Pair<String, String>>,
    val footer: String,
)

/** The colors of the picture by the player's role: pink for a hider, green for a seeker, both on ink. */
internal data class StoryLook(val accent: Color, val onAccent: Color) {
    companion object {
        fun of(role: Role): StoryLook = when (role) {
            Role.HIDER -> StoryLook(Palette.Pink, Color.White)
            Role.SEEKER -> StoryLook(Palette.Green, Palette.Ink)
        }
    }
}

/** Draws a story picture: 1080 × 1920 pixels, Instagram's 9:16. */
internal class StoryRenderer(
    private val measurer: TextMeasurer,
    private val display: FontFamily,
    private val text: FontFamily,
) {
    fun render(texts: StoryTexts, look: StoryLook): ImageBitmap {
        val bitmap = ImageBitmap(STORY_WIDTH_PX, STORY_HEIGHT_PX)
        val size = Size(STORY_WIDTH_PX.toFloat(), STORY_HEIGHT_PX.toFloat())
        // The picture is laid out on 360 × 640 dp, the size of a phone's screen.
        val density = Density(STORY_WIDTH_PX / STORY_WIDTH_DP, fontScale = 1f)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), size) { story(texts, look) }
        return bitmap
    }

    private fun DrawScope.story(texts: StoryTexts, look: StoryLook) {
        drawRect(Palette.Ink)
        // Two blobs of the role's color behind the text.
        drawCircle(look.accent, radius = 130.dp.toPx(), center = Offset(size.width - 10.dp.toPx(), 20.dp.toPx()))
        drawCircle(
            look.accent.copy(alpha = 0.3f),
            radius = 100.dp.toPx(),
            center = Offset(-20.dp.toPx(), size.height - 90.dp.toPx()),
        )
        val left = MARGIN.dp.toPx()
        val width = size.width - 2 * left
        // Instagram puts the author's name over the top of a story: the text starts below it.
        var y = 48.dp.toPx()

        y += put(texts.brand, display(22, FontWeight.ExtraBold), Palette.Green, left, y, width) + 4.dp.toPx()
        y += put(texts.caption.uppercase(), caps(12), MUTED, left, y, width) + 28.dp.toPx()
        y += put(texts.headline, display(30, FontWeight.ExtraBold, line = 36), Color.White, left, y, width, 3)
        y += 22.dp.toPx()
        y += resultCard(texts, look, left, y, width) + 20.dp.toPx()
        if (texts.tally.isNotEmpty()) y += tally(texts.tally, left, y, width) + 18.dp.toPx()

        val footer = measure(texts.footer, text(13, FontWeight.Bold), width)
        val footerTop = size.height - 40.dp.toPx() - footer.size.height
        drawText(footer, color = MUTED, topLeft = Offset(left, footerTop))
        // Awards as long as they fit above the footer.
        for ((title, detail) in texts.awards) {
            val pill = awardLayout(title, detail, width)
            if (y + pill.height > footerTop - 12.dp.toPx()) break
            award(pill, look, left, y, width)
            y += pill.height + 8.dp.toPx()
        }
    }

    /** The player's own result on the role's color: their initial, name, how it went and the number. */
    private fun DrawScope.resultCard(texts: StoryTexts, look: StoryLook, left: Float, top: Float, width: Float): Float {
        val padding = 18.dp.toPx()
        val avatar = 44.dp.toPx()
        val inner = width - 2 * padding
        val beside = inner - avatar - 12.dp.toPx()
        val name = measure(texts.name, text(18, FontWeight.ExtraBold), beside, 1)
        val label = measure(texts.label.uppercase(), caps(11), beside, 1)
        val line = measure(texts.line, text(14, FontWeight.SemiBold), inner, 2)
        val value = texts.value?.let { measure(it, display(48, FontWeight.ExtraBold, line = 52), inner, 1) }
        val head = maxOf(avatar, label.size.height + 2.dp.toPx() + name.size.height)
        val height = padding + head + 10.dp.toPx() + line.size.height +
            (value?.let { 4.dp.toPx() + it.size.height } ?: 0f) + padding
        drawRoundRect(
            look.accent,
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = CornerRadius(24.dp.toPx()),
        )
        var y = top + padding
        val x = left + padding
        drawCircle(Palette.Ink, radius = avatar / 2, center = Offset(x + avatar / 2, y + avatar / 2))
        val initial = measure(texts.name.trim().take(1).uppercase().ifEmpty { "?" }, display(20, FontWeight.ExtraBold))
        drawText(
            initial,
            color = look.accent,
            topLeft = Offset(x + (avatar - initial.size.width) / 2, y + (avatar - initial.size.height) / 2),
        )
        val besideX = x + avatar + 12.dp.toPx()
        val besideY = y + (head - label.size.height - 2.dp.toPx() - name.size.height) / 2
        drawText(label, color = look.onAccent.copy(alpha = 0.75f), topLeft = Offset(besideX, besideY))
        drawText(name, color = look.onAccent, topLeft = Offset(besideX, besideY + label.size.height + 2.dp.toPx()))
        y += head + 10.dp.toPx()
        drawText(line, color = look.onAccent, topLeft = Offset(x, y))
        y += line.size.height
        if (value != null) drawText(value, color = look.onAccent, topLeft = Offset(x, y + 4.dp.toPx()))
        return height
    }

    /** The hiders' numbers in a row: big digits over small labels. */
    private fun DrawScope.tally(items: List<Pair<String, Int>>, left: Float, top: Float, width: Float): Float {
        val column = width / items.size
        var height = 0f
        items.forEachIndexed { index, (label, count) ->
            val x = left + index * column
            val number = measure(count.toString(), display(32, FontWeight.ExtraBold, line = 36), column)
            val caption = measure(label.uppercase(), caps(11), column - 8.dp.toPx(), 2)
            drawText(number, color = Color.White, topLeft = Offset(x, top))
            drawText(caption, color = MUTED, topLeft = Offset(x, top + number.size.height))
            height = maxOf(height, (number.size.height + caption.size.height).toFloat())
        }
        return height
    }

    /** An award's two lines, measured. */
    private class AwardPill(val heading: TextLayoutResult, val what: TextLayoutResult, val padding: Float) {
        val height: Float get() = 2 * padding + heading.size.height + what.size.height
    }

    private fun DrawScope.awardLayout(title: String, detail: String, width: Float): AwardPill {
        val inner = width - 2 * 16.dp.toPx()
        return AwardPill(
            heading = measure(title.uppercase(), caps(11), inner, 1),
            what = measure(detail, text(15, FontWeight.ExtraBold), inner, 1),
            padding = 10.dp.toPx(),
        )
    }

    /** An award as an outlined pill: its title in the role's color and what for. */
    private fun DrawScope.award(pill: AwardPill, look: StoryLook, left: Float, top: Float, width: Float) {
        drawRoundRect(
            look.accent,
            topLeft = Offset(left, top),
            size = Size(width, pill.height),
            cornerRadius = CornerRadius(18.dp.toPx()),
            style = Stroke(width = 2.dp.toPx()),
        )
        val x = left + 16.dp.toPx()
        drawText(pill.heading, color = look.accent, topLeft = Offset(x, top + pill.padding))
        drawText(pill.what, color = Color.White, topLeft = Offset(x, top + pill.padding + pill.heading.size.height))
    }

    /** Draws [value] at ([x], [y]) within [width]; its height. */
    private fun DrawScope.put(
        value: String,
        style: TextStyle,
        color: Color,
        x: Float,
        y: Float,
        width: Float,
        maxLines: Int = 1,
    ): Float {
        val layout = measure(value, style, width, maxLines)
        drawText(layout, color = color, topLeft = Offset(x, y))
        return layout.size.height.toFloat()
    }

    private fun DrawScope.measure(
        value: String,
        style: TextStyle,
        width: Float = size.width,
        maxLines: Int = Int.MAX_VALUE,
    ): TextLayoutResult = measurer.measure(
        text = value,
        style = style,
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines,
        constraints = Constraints(maxWidth = width.toInt().coerceAtLeast(1)),
        layoutDirection = layoutDirection,
        density = this,
    )

    private fun display(size: Int, weight: FontWeight, line: Int = size + 4) =
        TextStyle(fontFamily = display, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

    private fun text(size: Int, weight: FontWeight) =
        TextStyle(fontFamily = text, fontWeight = weight, fontSize = size.sp, lineHeight = (size + 6).sp)

    private fun caps(size: Int) = TextStyle(
        fontFamily = text,
        fontWeight = FontWeight.ExtraBold,
        fontSize = size.sp,
        lineHeight = (size + 4).sp,
        letterSpacing = 0.08.em,
    )

    private companion object {
        const val MARGIN = 28
        val MUTED = Color(0xFFB4B4BE)
    }
}

/** The renderer with the app's fonts, which the screen has already loaded. */
@Composable
internal fun rememberStoryRenderer(): StoryRenderer {
    val measurer = rememberTextMeasurer()
    val fonts = Hovanki.fonts
    return remember(measurer, fonts) { StoryRenderer(measurer, fonts.display, fonts.text) }
}

internal const val STORY_WIDTH_PX = 1080
internal const val STORY_HEIGHT_PX = 1920
private const val STORY_WIDTH_DP = 360f
