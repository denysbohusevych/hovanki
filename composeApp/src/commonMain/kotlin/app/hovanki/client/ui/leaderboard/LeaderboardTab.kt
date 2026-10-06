package app.hovanki.client.ui.leaderboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.ic_info
import app.hovanki.client.resources.leaderboard_down
import app.hovanki.client.resources.leaderboard_empty
import app.hovanki.client.resources.leaderboard_empty_last_game
import app.hovanki.client.resources.leaderboard_ends_in
import app.hovanki.client.resources.leaderboard_friends
import app.hovanki.client.resources.leaderboard_last_game
import app.hovanki.client.resources.leaderboard_rules
import app.hovanki.client.resources.leaderboard_rules_button
import app.hovanki.client.resources.leaderboard_title
import app.hovanki.client.resources.leaderboard_to_next
import app.hovanki.client.resources.leaderboard_up
import app.hovanki.client.resources.leaderboard_week
import app.hovanki.client.resources.leaderboard_world
import app.hovanki.client.resources.leaderboard_you
import app.hovanki.client.resources.working
import app.hovanki.client.ui.common.Avatar
import app.hovanki.client.ui.common.BusyRow
import app.hovanki.client.ui.common.CapsText
import app.hovanki.client.ui.common.CommandStatus
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopIconButton
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.collectScreenState
import app.hovanki.client.ui.theme.Hovanki
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.protocol.LeaderboardEntry
import app.hovanki.shared.protocol.LeaderboardResponse
import app.hovanki.shared.protocol.LeaderboardScope
import app.hovanki.shared.rules.LeaderboardRules
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.abs

/**
 * «Rating» (docs/adr/0020-leaderboard.md, docs/design.md «Рейтинг»): the scopes on a white segmented bar, the top
 * three on a green podium, the rest on a white card, and the player's own line in ink with the points to the next one.
 */
@Composable
fun LeaderboardTab(viewModel: LeaderboardViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectScreenState()
    LeaderboardContent(state, viewModel::onEvent)
}

@Composable
private fun LeaderboardContent(state: LeaderboardUiState, onEvent: (LeaderboardEvent) -> Unit) {
    LaunchedEffect(Unit) { onEvent(LeaderboardEvent.Refresh) }

    ScreenColumn(modifier = Modifier.testTag(TestTags.LEADERBOARD_SCREEN)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.leaderboard_title),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.weight(1f),
            )
            PopIconButton(
                icon = Res.drawable.ic_info,
                contentDescription = stringResource(Res.string.leaderboard_rules_button),
                onClick = { onEvent(LeaderboardEvent.ToggleRules) },
                modifier = Modifier.testTag(TestTags.LEADERBOARD_RULES),
            )
        }
        if (state.showRules) {
            PopCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(
                        Res.string.leaderboard_rules,
                        LeaderboardRules.PLAYED,
                        LeaderboardRules.HIDER_PER_MINUTE,
                        LeaderboardRules.HIDER_NEVER_FOUND,
                        LeaderboardRules.SEEKER_PER_CATCH,
                        LeaderboardRules.SEEKER_WON,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        ScopeBar(state.scope, onPick = { onEvent(LeaderboardEvent.SelectScope(it)) })
        CommandStatus(
            isBusy = false,
            message = state.message,
            onDismiss = { onEvent(LeaderboardEvent.DismissMessage) },
            errorTag = TestTags.SOCIAL_ERROR,
        )
        val board = state.board
        when {
            board == null -> if (state.isLoading) BusyRow(stringResource(Res.string.working))

            board.entries.isEmpty() -> PopCard(modifier = Modifier.fillMaxWidth()) {
                SecondaryText(
                    stringResource(
                        if (board.scope == LeaderboardScope.LAST_GAME) {
                            Res.string.leaderboard_empty_last_game
                        } else {
                            Res.string.leaderboard_empty
                        },
                    ),
                )
            }

            else -> Board(board, state.nowMillis)
        }
    }
}

/** The scopes: white, the picked one ink. */
@Composable
private fun ScopeBar(selected: LeaderboardScope, onPick: (LeaderboardScope) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Paper)
            .padding(5.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LeaderboardScope.entries.forEach { scope ->
            val isSelected = scope == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isSelected) Palette.Ink else Palette.Paper)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onPick(scope) })
                    .testTag(TestTags.leaderboardScope(scope.name)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(scope.title()),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) Color.White else Palette.Ink,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Board(board: LeaderboardResponse, nowMillis: Long) {
    Podium(board, nowMillis)
    val rest = board.entries.drop(PODIUM)
    if (rest.isNotEmpty()) {
        PopCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(0.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            rest.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(color = Palette.Line, thickness = 1.dp)
                EntryRow(entry)
            }
        }
    }
    board.me?.let { me -> MyLine(me, board.nextAbove, board.rankChange) }
}

/** The top three on green: the first in the middle, pink and bigger; the week and when it ends on top. */
@Composable
private fun Podium(board: LeaderboardResponse, nowMillis: Long) {
    PopCard(
        modifier = Modifier.fillMaxWidth(),
        color = Palette.Green,
        shadow = 4.dp,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CapsText(
                if (board.scope == LeaderboardScope.LAST_GAME) {
                    stringResource(Res.string.leaderboard_last_game)
                } else {
                    stringResource(Res.string.leaderboard_week, isoWeek(board.weekStartMillis))
                },
                modifier = Modifier.weight(1f),
            )
            val left = board.weekEndMillis - nowMillis
            if (board.scope != LeaderboardScope.LAST_GAME && left > 0 && nowMillis > 0) {
                Text(
                    text = stringResource(
                        Res.string.leaderboard_ends_in,
                        (left / DAY_MILLIS).toInt(),
                        (left % DAY_MILLIS / HOUR_MILLIS).toInt(),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.Ink)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        val top = board.entries.take(PODIUM)
        Row(verticalAlignment = Alignment.Bottom) {
            PodiumPlace(top.getOrNull(1), Modifier.weight(1f))
            PodiumPlace(top.getOrNull(0), Modifier.weight(1f).offset(y = (-14).dp))
            PodiumPlace(top.getOrNull(2), Modifier.weight(1f))
        }
    }
}

@Composable
private fun PodiumPlace(entry: LeaderboardEntry?, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (entry == null) return@Column
        val first = entry.rank == 1
        Box {
            Avatar(
                name = entry.nickname,
                size = if (first) 76.dp else 60.dp,
                color = if (first) Palette.Pink else Palette.Paper,
                contentColor = if (first) Color.White else Palette.Ink,
            )
            Text(
                text = "#${entry.rank}",
                style = MaterialTheme.typography.labelMedium,
                color = Palette.Green,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 8.dp, y = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.Ink)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        Text(
            text = if (entry.isMe) stringResource(Res.string.leaderboard_you) else entry.nickname,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(text = entry.points.toString(), style = Hovanki.text.code.copy(fontSize = 18.sp, lineHeight = 22.sp))
    }
}

/** A line from the fourth on; the player's own is light green. */
@Composable
private fun EntryRow(entry: LeaderboardEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (entry.isMe) Palette.GreenLight else Palette.Paper)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RankNumber(entry.rank, Palette.Ink3)
        // White on white takes the other white: the background's.
        Avatar(name = entry.nickname, size = 40.dp, color = Palette.Fog)
        Text(
            text = if (entry.isMe) stringResource(Res.string.leaderboard_you) else entry.nickname,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(text = entry.points.toString(), style = Hovanki.text.code.copy(fontSize = 18.sp, lineHeight = 22.sp))
    }
}

/** The player's own line in ink: the place, the points, how they moved this week and how far the next one is. */
@Composable
private fun MyLine(me: LeaderboardEntry, next: LeaderboardEntry?, rankChange: Int?) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = if (rankChange != null && rankChange != 0) 10.dp else 0.dp)) {
        PopCard(
            modifier = Modifier.fillMaxWidth().testTag(TestTags.LEADERBOARD_ME),
            color = Palette.Ink,
            contentColor = Color.White,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RankNumber(me.rank, Palette.Green)
                Avatar(
                    name = me.nickname,
                    size = 40.dp,
                    color = Palette.Ink,
                    contentColor = Color.White,
                    border = Palette.Green,
                )
                Text(
                    text = stringResource(Res.string.leaderboard_you),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = me.points.toString(),
                    style = Hovanki.text.code.copy(fontSize = 24.sp, lineHeight = 28.sp),
                    color = Palette.Green,
                )
            }
            if (next != null) {
                val gap = next.points - me.points
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val fraction = if (next.points > 0) me.points.toFloat() / next.points else 0f
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color.White.copy(alpha = 0.22f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(Palette.Green),
                        )
                    }
                    Text(
                        text = stringResource(Res.string.leaderboard_to_next, gap, next.nickname),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp),
                    )
                }
            }
        }
        if (rankChange != null && rankChange != 0) {
            Text(
                text = stringResource(
                    if (rankChange > 0) Res.string.leaderboard_up else Res.string.leaderboard_down,
                    abs(rankChange),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-12).dp, y = (-12).dp)
                    .rotate(TAG_TILT)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.Pink)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun RankNumber(rank: Int, color: Color, width: Dp = 32.dp) {
    Text(
        text = rank.toString(),
        style = Hovanki.text.code.copy(fontSize = 20.sp, lineHeight = 24.sp),
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = width),
    )
}

private fun LeaderboardScope.title(): StringResource = when (this) {
    LeaderboardScope.LAST_GAME -> Res.string.leaderboard_last_game
    LeaderboardScope.FRIENDS -> Res.string.leaderboard_friends
    LeaderboardScope.WORLD -> Res.string.leaderboard_world
}

/**
 * The ISO week of the week starting at [weekStartMillis] (a Monday in Kyiv): the week of its Thursday, whatever the
 * offset, so the date of that Thursday noon in UTC is enough.
 */
internal fun isoWeek(weekStartMillis: Long): Int {
    val thursday = (weekStartMillis + THURSDAY_NOON_MILLIS).floorDiv(DAY_MILLIS)
    val (_, dayOfYear) = yearAndDayOfYear(thursday)
    return (dayOfYear - 1) / DAYS_PER_WEEK + 1
}

/** The year and the day of the year (1-based) of [epochDay], days since 1970-01-01 (proleptic Gregorian). */
private fun yearAndDayOfYear(epochDay: Long): Pair<Int, Int> {
    var year = 1970 + (epochDay / DAYS_PER_YEAR_AVERAGE).toInt()
    while (daysBeforeYear(year) > epochDay) year--
    while (daysBeforeYear(year + 1) <= epochDay) year++
    return year to (epochDay - daysBeforeYear(year)).toInt() + 1
}

/** Days from 1970-01-01 to January 1st of [year]. */
private fun daysBeforeYear(year: Int): Long {
    val y = year - 1L
    val days = y * 365 + y / 4 - y / 100 + y / 400
    return days - DAYS_TO_1970
}

private const val PODIUM = 3
private const val TAG_TILT = 4f
private const val HOUR_MILLIS = 3_600_000L
private const val DAY_MILLIS = 24 * HOUR_MILLIS
private const val THURSDAY_NOON_MILLIS = 3 * DAY_MILLIS + 12 * HOUR_MILLIS
private const val DAYS_PER_WEEK = 7
private const val DAYS_PER_YEAR_AVERAGE = 365.2425

/** Days from 0001-01-01 to 1970-01-01. */
private const val DAYS_TO_1970 = 719_162L
