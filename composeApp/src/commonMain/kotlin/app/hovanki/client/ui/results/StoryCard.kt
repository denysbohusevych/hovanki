package app.hovanki.client.ui.results

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
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
import app.hovanki.client.session.catchesBy
import app.hovanki.client.session.lengthMeters
import app.hovanki.client.session.searchMillisAt
import app.hovanki.client.ui.theme.Hovanki
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.geo.offsetFrom
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.TrackPoint
import app.hovanki.shared.protocol.TracksResponse
import kotlin.math.hypot
import kotlin.math.max

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

/** Which ways the story's scheme draws: only the player's own (on green) or everybody's (on ink). */
internal enum class StoryStyle { MINE, EVERYONE }

/** A point of the scheme: east and down from the zone's center, in units where the scheme's circle fits in 1. */
internal data class SchemePoint(val x: Float, val y: Float)

/** The kinds of ways on the scheme, drawn last to first: the player's own on top. */
internal enum class SchemeLine {
    MINE,

    /** The seeker who found the player, until then. */
    FOUND_ME,
    HIDER,
    SEEKER,
}

internal class SchemePath(val kind: SchemeLine, val points: List<SchemePoint>)

/**
 * The ways of a round as a scheme (docs/adr/0024-instagram-stories.md): shapes only, relative to the zone's center, on
 * a made-up grid of streets, never the real map. [zoneRadius]: the first zone's circle in the same units. [start]: where
 * the player's way began. [marks]: where the player was found, or where they found somebody.
 */
internal class StoryScheme(
    val paths: List<SchemePath>,
    val zoneRadius: Float,
    val start: SchemePoint,
    val marks: List<SchemePoint>,
)

/**
 * What the story picture of the results shows (docs/adr/0024-instagram-stories.md): the player's own result, their way
 * as a scheme, the one who found them and their first award. No map, place, date or time of day.
 */
internal data class Story(
    val result: MyResult,
    val players: Int,
    val roundMillis: Long?,
    val scheme: StoryScheme?,
    val distanceMeters: Double?,
    /** The seeker who found the player. */
    val foundBy: String?,
    val award: Award?,
)

/** The viewer's [Story] of a finished game: their own first award of [awards], their way once [tracks] are loaded. */
internal fun GameSnapshot.story(awards: List<Award>, tracks: TracksResponse? = null): Story? {
    val result = myResult() ?: return null
    val me = result.player
    val mine = tracks?.tracks?.firstOrNull { it.playerId == me.id }?.points.orEmpty()
    return Story(
        result = result,
        players = counts?.players ?: players.size,
        roundMillis = searchMillisAt(finishedAtMillis ?: 0L),
        scheme = scheme(tracks),
        distanceMeters = mine.takeIf { it.size >= 2 }?.lengthMeters(),
        foundBy = me.caughtBy?.takeIf { me.status == PlayerStatus.CAUGHT }
            ?.let { id -> players.firstOrNull { it.id == id }?.name },
        award = awards.firstOrNull { it.playerId == me.id },
    )
}

/** The scheme of the viewer's way and the others'; null without at least two points of the viewer's own. */
internal fun GameSnapshot.scheme(tracks: TracksResponse?): StoryScheme? {
    val me = players.firstOrNull { it.id == this.me.playerId } ?: return null
    val byPlayer = tracks?.tracks?.associate { it.playerId to it.points }.orEmpty()
    val mine = byPlayer[me.id]?.takeIf { it.size >= 2 } ?: return null
    val zone = settings.zone.initial
    fun place(point: TrackPoint): Pair<Double, Double> {
        val offset = point.point.offsetFrom(zone.center)
        return offset.eastMeters / zone.radiusMeters to -offset.northMeters / zone.radiusMeters
    }
    // The player's whole way fits, the zone's circle too.
    val farthest = mine.maxOf { point -> place(point).let { (x, y) -> hypot(x, y) } }
    val scale = 1.0 / max(1.0, farthest)
    fun scheme(points: List<TrackPoint>): List<SchemePoint> = thin(points).map {
        val (x, y) = place(it)
        SchemePoint((x * scale).toFloat(), (y * scale).toFloat())
    }
    val foundMe = me.caughtBy?.takeIf { me.status == PlayerStatus.CAUGHT }
    val others = players.filter { it.id != me.id }.mapNotNull { player ->
        val points = byPlayer[player.id]?.takeIf { it.size >= 2 } ?: return@mapNotNull null
        when {
            player.id == foundMe -> {
                val until = me.outAtMillis ?: Long.MAX_VALUE
                points.filter { it.atMillis <= until }.takeIf { it.size >= 2 }
                    ?.let { SchemePath(SchemeLine.FOUND_ME, scheme(it)) }
            }

            player.role == Role.HIDER -> SchemePath(SchemeLine.HIDER, scheme(points))

            else -> SchemePath(SchemeLine.SEEKER, scheme(points))
        }
    }
    val marks = when {
        me.role == Role.HIDER && me.status == PlayerStatus.CAUGHT -> listOf(mine.last())

        // A hider's way ends where they were found.
        me.role == Role.SEEKER -> players.filter { it.caughtBy == me.id }
            .mapNotNull { found -> byPlayer[found.id]?.lastOrNull() }

        else -> emptyList()
    }
    return StoryScheme(
        paths = others + SchemePath(SchemeLine.MINE, scheme(mine)),
        zoneRadius = scale.toFloat(),
        start = scheme(listOf(mine.first())).first(),
        marks = marks.map { scheme(listOf(it)).first() },
    )
}

/** At most [SCHEME_POINTS] points of a way, its first and last kept. */
private fun thin(points: List<TrackPoint>): List<TrackPoint> {
    if (points.size <= SCHEME_POINTS) return points
    val step = (points.size - 1).toDouble() / (SCHEME_POINTS - 1)
    return List(SCHEME_POINTS) { points[(it * step).toInt().coerceAtMost(points.size - 1)] }
}

internal const val SCHEME_POINTS = 160

/** The texts of a [Story] in the player's language, put together on the results screen. */
internal data class StoryTexts(
    val brand: String,
    /** «6 players · 24:13». */
    val corner: String,
    /** «My time hiding», «My finds». */
    val label: String,
    /** The number that matters, big: the time a hider lasted, a seeker's finds. */
    val value: String?,
    /** «2 of 4». */
    val place: String?,
    val award: String?,
    val cta: String,
    val start: String,
    val me: String,
    /** «Found me: Max». */
    val foundMe: String?,
    /** «Found here: Max». */
    val foundHere: String?,
    /** «Others: 4». */
    val others: String?,
    val distance: String?,
)

/** Draws a story picture: 1080 × 1920 pixels, Instagram's 9:16, after the designer's two mockups. */
internal class StoryRenderer(
    private val measurer: TextMeasurer,
    private val display: FontFamily,
    private val text: FontFamily,
) {
    fun render(texts: StoryTexts, scheme: StoryScheme?, role: Role, style: StoryStyle): ImageBitmap {
        val bitmap = ImageBitmap(STORY_WIDTH_PX, STORY_HEIGHT_PX)
        val size = Size(STORY_WIDTH_PX.toFloat(), STORY_HEIGHT_PX.toFloat())
        // The picture is laid out on 360 × 640 dp, the size of a phone's screen.
        val density = Density(STORY_WIDTH_PX / STORY_WIDTH_DP, fontScale = 1f)
        val look = Look.of(style, role)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), size) {
            story(texts, scheme, look)
        }
        return bitmap
    }

    /** The colors of a style: everybody's ways on ink, the player's own on green. */
    private class Look(
        val style: StoryStyle,
        val background: Color,
        val ink: Color,
        val number: Color,
        val mine: Color,
        val chip: Color,
        val onChip: Color,
        val card: Color,
        val road: Color,
        val park: Color,
        val zone: Color,
        val frame: Color,
        val cta: Color,
        val onCta: Color,
    ) {
        val everyone: Boolean get() = style == StoryStyle.EVERYONE

        companion object {
            fun of(style: StoryStyle, role: Role): Look = when (style) {
                StoryStyle.EVERYONE -> Look(
                    style = style,
                    background = Palette.Ink,
                    ink = Color.White,
                    number = Palette.Green,
                    mine = Palette.Green,
                    chip = Palette.Green,
                    onChip = Palette.Ink,
                    card = Color(0xFF19191C),
                    road = Color(0xFF242428),
                    park = Color(0xFF1C261D),
                    zone = Color(0xFF1E8A26),
                    frame = Color(0xFF2E2E33),
                    cta = Palette.Green,
                    onCta = Palette.Ink,
                )

                StoryStyle.MINE -> Look(
                    style = style,
                    background = Palette.Green,
                    ink = Palette.Ink,
                    number = Palette.Ink,
                    mine = if (role == Role.HIDER) Palette.Pink else Palette.Ink,
                    chip = Palette.Ink,
                    onChip = Palette.Green,
                    card = Color(0xFFE9E9EE),
                    road = Color.White,
                    park = Color(0xFFD6E8C6),
                    zone = Palette.Ink,
                    frame = Palette.Ink,
                    cta = Palette.Ink,
                    onCta = Color.White,
                )
            }
        }
    }

    private fun DrawScope.story(texts: StoryTexts, scheme: StoryScheme?, look: Look) {
        drawRect(look.background)
        val left = MARGIN.dp.toPx()
        val width = size.width - 2 * left
        var y = 40.dp.toPx()

        // The logo: a pink dot in a circle and the word; the game's numbers in the corner.
        val logo = 22.dp.toPx()
        val logoCenter = Offset(left + logo / 2, y + logo / 2)
        drawCircle(if (look.everyone) Palette.Green else Palette.Ink, logo / 2, logoCenter)
        drawCircle(Palette.Pink, logo * 0.24f, logoCenter)
        val brand = measure(texts.brand, display(22, FontWeight.ExtraBold, line = 24))
        val brandX = left + logo + 8.dp.toPx()
        drawText(brand, color = look.ink, topLeft = Offset(brandX, y + (logo - brand.size.height) / 2))
        val cornerWidth = width - logo - 8.dp.toPx() - brand.size.width - 12.dp.toPx()
        val corner = measure(texts.corner.uppercase(), caps(11), cornerWidth, 1)
        drawText(
            corner,
            color = look.ink.copy(alpha = 0.85f),
            topLeft = Offset(left + width - corner.size.width, y + (logo - corner.size.height) / 2),
        )
        y += logo + 30.dp.toPx()

        // «My time hiding» over the big number and the place, tilted.
        y += put(texts.label, text(15, FontWeight.ExtraBold), look.ink, left, y, width) + 2.dp.toPx()
        y += bigNumber(texts, look, left, y, width) + 18.dp.toPx()

        val ctaHeight = 46.dp.toPx()
        val ctaTop = size.height - 28.dp.toPx() - ctaHeight
        if (scheme != null) {
            val schemeHeight = 272.dp.toPx()
            scheme(scheme, texts, look, Rect(left, y, left + width, y + schemeHeight))
            y += schemeHeight + 22.dp.toPx()
        }
        texts.award?.let { award ->
            val pill = measure(award, text(15, FontWeight.ExtraBold), width - 40.dp.toPx(), 1)
            val height = pill.size.height + 2 * 10.dp.toPx()
            if (y + height < ctaTop - 12.dp.toPx()) award(pill, look, left, y, height)
        }

        drawRoundRect(
            look.cta,
            topLeft = Offset(left, ctaTop),
            size = Size(width, ctaHeight),
            cornerRadius = CornerRadius(ctaHeight / 2),
        )
        val cta = measure(texts.cta, text(16, FontWeight.ExtraBold), width - 40.dp.toPx(), 1)
        drawText(
            cta,
            color = look.onCta,
            topLeft = Offset(left + 20.dp.toPx(), ctaTop + (ctaHeight - cta.size.height) / 2),
        )
    }

    /** The big number and, beside it, the place on a tilted chip; the number shrinks until both fit. */
    private fun DrawScope.bigNumber(texts: StoryTexts, look: Look, left: Float, top: Float, width: Float): Float {
        val value = texts.value ?: return 0f
        val chip = texts.place?.let { measure(it, display(15, FontWeight.ExtraBold, line = 18), width) }
        val chipPadding = 12.dp.toPx()
        val chipWidth = chip?.let { it.size.width + 2 * chipPadding + 10.dp.toPx() } ?: 0f
        val number = NUMBER_SIZES.map { size ->
            measure(value, display(size, FontWeight.ExtraBold, line = size + 2), width * 2, 1)
        }.firstOrNull { it.size.width + chipWidth <= width }
            ?: measure(value, display(NUMBER_SIZES.last(), FontWeight.ExtraBold), width - chipWidth, 1)
        drawText(number, color = look.number, topLeft = Offset(left, top))
        if (chip != null) {
            val size = Size(chip.size.width + 2 * chipPadding, chip.size.height + 2 * 8.dp.toPx())
            val topLeft = Offset(
                left + number.size.width + 10.dp.toPx(),
                top + number.size.height * 0.6f - size.height / 2,
            )
            rotate(TILT, pivot = topLeft + Offset(size.width / 2, size.height / 2)) {
                drawRoundRect(look.chip, topLeft, size, CornerRadius(14.dp.toPx()))
                drawText(chip, color = look.onChip, topLeft = topLeft + Offset(chipPadding, 8.dp.toPx()))
            }
        }
        return number.size.height.toFloat()
    }

    /** The scheme in a rounded card: a made-up grid of streets, the zone's circle, the ways and their marks. */
    private fun DrawScope.scheme(scheme: StoryScheme, texts: StoryTexts, look: Look, card: Rect) {
        val corner = CornerRadius(26.dp.toPx())
        // On green a hard ink shadow, as the app's cards.
        if (!look.everyone) drawRoundRect(Palette.Ink, card.topLeft + Offset(0f, 5.dp.toPx()), card.size, corner)
        val outline = Path().apply { addRoundRect(RoundRect(card, corner)) }
        clipPath(outline) {
            drawRect(look.card, card.topLeft, card.size)
            val road = 14.dp.toPx()
            ROADS_X.forEach { f ->
                drawRect(look.road, Offset(card.left + card.width * f - road / 2, card.top), Size(road, card.height))
            }
            ROADS_Y.forEach { f ->
                drawRect(look.road, Offset(card.left, card.top + card.height * f - road / 2), Size(card.width, road))
            }
            drawRoundRect(
                look.park,
                Offset(card.left + card.width * 0.28f, card.top + card.height * 0.29f),
                Size(card.width * 0.44f, card.height * 0.36f),
                CornerRadius(14.dp.toPx()),
            )
            val center = card.center
            val unit = minOf(card.width, card.height) / 2 - 22.dp.toPx()
            fun at(point: SchemePoint) = Offset(center.x + point.x * unit, center.y + point.y * unit)
            drawCircle(
                look.zone,
                radius = scheme.zoneRadius * unit,
                center = center,
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())),
                ),
            )
            scheme.paths.filter { look.everyone || it.kind == SchemeLine.MINE }
                .sortedByDescending { it.kind.ordinal }
                .forEach { way(it, ::at, look) }
            val start = at(scheme.start)
            drawCircle(if (look.everyone) look.card else Color.White, 9.dp.toPx(), start)
            drawCircle(if (look.everyone) look.mine else Palette.Ink, 9.dp.toPx(), start, style = Stroke(3.5.dp.toPx()))
            scheme.marks.forEach { cross(at(it), look) }
            if (look.everyone) {
                legend(texts, look, card)
            } else {
                tag(texts.start, start, card, below = true)
                val foundHere = texts.foundHere
                val mark = scheme.marks.singleOrNull()
                if (foundHere != null && mark != null) tag(foundHere, at(mark), card, below = false)
                texts.distance?.let { distance(it, card) }
            }
        }
        drawRoundRect(look.frame, card.topLeft, card.size, corner, style = Stroke(2.5.dp.toPx()))
    }

    private fun DrawScope.way(path: SchemePath, at: (SchemePoint) -> Offset, look: Look) {
        val line = Path().apply {
            path.points.forEachIndexed { index, point ->
                val offset = at(point)
                if (index == 0) moveTo(offset.x, offset.y) else lineTo(offset.x, offset.y)
            }
        }
        fun stroke(width: Float, effect: PathEffect? = null) =
            Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect)
        when (path.kind) {
            SchemeLine.MINE -> {
                // A glow on ink, a white edge on the light scheme.
                if (look.everyone) {
                    drawPath(line, look.mine.copy(alpha = 0.22f), style = stroke(16.dp.toPx()))
                } else {
                    drawPath(line, Color.White, style = stroke(11.dp.toPx()))
                }
                drawPath(line, look.mine, style = stroke(6.dp.toPx()))
            }

            SchemeLine.FOUND_ME -> drawPath(line, Color.White, style = stroke(3.5.dp.toPx(), dots(8.dp.toPx())))

            SchemeLine.HIDER -> drawPath(line, Palette.Pink.copy(alpha = 0.45f), style = stroke(2.dp.toPx()))

            SchemeLine.SEEKER -> drawPath(line, OTHERS, style = stroke(2.dp.toPx()))
        }
    }

    /** Where the player was found, or found somebody: a cross in a circle. */
    private fun DrawScope.cross(center: Offset, look: Look) {
        val radius = 15.dp.toPx()
        drawCircle(Palette.Ink, radius, center)
        if (look.everyone) drawCircle(Color.White, radius, center, style = Stroke(3.dp.toPx()))
        val arm = radius * 0.45f
        val stroke = 3.5.dp.toPx()
        drawLine(Palette.Green, center + Offset(-arm, -arm), center + Offset(arm, arm), stroke, StrokeCap.Round)
        drawLine(Palette.Green, center + Offset(-arm, arm), center + Offset(arm, -arm), stroke, StrokeCap.Round)
    }

    /**
     * A small ink label next to a point of the scheme: under it ([below]), or beside it, right where it fits, else
     * left; kept inside the [card].
     */
    private fun DrawScope.tag(value: String, point: Offset, card: Rect, below: Boolean) {
        val label = measure(value, text(12, FontWeight.ExtraBold), 120.dp.toPx(), 2)
        val padding = 9.dp.toPx()
        val inset = 6.dp.toPx()
        val gap = 20.dp.toPx()
        val size = Size(label.size.width + 2 * padding, label.size.height + 2 * 6.dp.toPx())
        val wanted = when {
            below -> Offset(point.x - size.width / 2, point.y + gap - 6.dp.toPx())
            point.x + gap + size.width <= card.right - inset -> Offset(point.x + gap, point.y - size.height / 2)
            else -> Offset(point.x - gap - size.width, point.y - size.height / 2)
        }
        val x = wanted.x.coerceIn(card.left + inset, card.right - size.width - inset)
        val y = wanted.y.coerceIn(card.top + inset, card.bottom - size.height - inset)
        drawRoundRect(Palette.Ink, Offset(x, y), size, CornerRadius(10.dp.toPx()))
        drawText(label, color = Palette.Green, topLeft = Offset(x + padding, y + 6.dp.toPx()))
    }

    /** The way's length in the card's corner. */
    private fun DrawScope.distance(value: String, card: Rect) {
        val label = measure(value, display(15, FontWeight.ExtraBold, line = 18), card.width / 2)
        val padding = 12.dp.toPx()
        val size = Size(label.size.width + 2 * padding, label.size.height + 2 * 8.dp.toPx())
        val topLeft = Offset(card.right - 14.dp.toPx() - size.width, card.bottom - 14.dp.toPx() - size.height)
        drawRoundRect(Color.White, topLeft, size, CornerRadius(14.dp.toPx()))
        drawRoundRect(Palette.Ink, topLeft, size, CornerRadius(14.dp.toPx()), style = Stroke(2.5.dp.toPx()))
        drawText(label, color = Palette.Ink, topLeft = topLeft + Offset(padding, 8.dp.toPx()))
    }

    /** Who is who on the ink scheme: me, the one who found me, the others. */
    private fun DrawScope.legend(texts: StoryTexts, look: Look, card: Rect) {
        val items = listOfNotNull(
            SchemeLine.MINE to texts.me,
            texts.foundMe?.let { SchemeLine.FOUND_ME to it },
            texts.others?.let { SchemeLine.SEEKER to it },
        )
        val height = 32.dp.toPx()
        val inset = 12.dp.toPx()
        val top = card.bottom - inset - height
        drawRoundRect(
            LEGEND,
            Offset(card.left + inset, top),
            Size(card.width - 2 * inset, height),
            CornerRadius(12.dp.toPx()),
        )
        var x = card.left + inset + 12.dp.toPx()
        val swatch = 14.dp.toPx()
        val right = card.right - inset - 10.dp.toPx()
        val middle = top + height / 2
        for ((kind, label) in items) {
            if (x + swatch + 30.dp.toPx() > right) break
            val from = Offset(x, middle)
            val to = Offset(x + swatch, middle)
            when (kind) {
                SchemeLine.MINE -> drawLine(look.mine, from, to, 4.dp.toPx(), StrokeCap.Round)

                SchemeLine.FOUND_ME -> drawLine(
                    Color.White,
                    from,
                    to,
                    3.5.dp.toPx(),
                    StrokeCap.Round,
                    dots(5.dp.toPx()),
                )

                else -> drawLine(OTHERS, from, to, 2.dp.toPx(), StrokeCap.Round)
            }
            x += swatch + 6.dp.toPx()
            val layout = measure(label, text(11, FontWeight.ExtraBold), right - x, 1)
            drawText(layout, color = Color.White, topLeft = Offset(x, middle - layout.size.height / 2))
            x += layout.size.width + 14.dp.toPx()
        }
    }

    /** The player's first award, tilted a little. */
    private fun DrawScope.award(pill: TextLayoutResult, look: Look, left: Float, top: Float, height: Float) {
        val padding = 18.dp.toPx()
        val topLeft = Offset(left, top)
        val size = Size(pill.size.width + 2 * padding, height)
        val corner = CornerRadius(14.dp.toPx())
        val textAt = Offset(left + padding, top + (height - pill.size.height) / 2)
        rotate(TILT / 2, pivot = Offset(left + size.width / 2, top + height / 2)) {
            if (look.everyone) {
                drawRoundRect(Palette.Pink, topLeft, size, corner, style = Stroke(2.5.dp.toPx()))
                drawText(pill, color = Palette.Pink, topLeft = textAt)
            } else {
                translate(0f, 3.dp.toPx()) { drawRoundRect(Palette.Ink, topLeft, size, corner) }
                drawRoundRect(Palette.Pink, topLeft, size, corner)
                drawRoundRect(Palette.Ink, topLeft, size, corner, style = Stroke(2.5.dp.toPx()))
                drawText(pill, color = Palette.Ink, topLeft = textAt)
            }
        }
    }

    /** Draws [value] at ([x], [y]) within [width]; its height. */
    private fun DrawScope.put(value: String, style: TextStyle, color: Color, x: Float, y: Float, width: Float): Float {
        val layout = measure(value, style, width, 1)
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

    private fun dots(gap: Float) = PathEffect.dashPathEffect(floatArrayOf(0.1f, gap))

    private fun display(size: Int, weight: FontWeight, line: Int = size + 4) =
        TextStyle(fontFamily = display, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

    private fun text(size: Int, weight: FontWeight) =
        TextStyle(fontFamily = text, fontWeight = weight, fontSize = size.sp, lineHeight = (size + 5).sp)

    private fun caps(size: Int) = TextStyle(
        fontFamily = text,
        fontWeight = FontWeight.ExtraBold,
        fontSize = size.sp,
        lineHeight = (size + 4).sp,
        letterSpacing = 0.06.em,
    )

    private companion object {
        const val MARGIN = 20

        /** The mockups' chips lean a little. */
        const val TILT = -4f
        val NUMBER_SIZES = listOf(64, 56, 48, 40)

        /** The made-up streets of the scheme, as fractions of the card. */
        val ROADS_X = listOf(0.2f, 0.5f, 0.8f)
        val ROADS_Y = listOf(0.19f, 0.48f, 0.76f)
        val OTHERS = Color(0xFF8C8C95)
        val LEGEND = Color(0xEB111113)
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
