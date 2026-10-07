package app.hovanki.client.ui.achievements

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.achievement_big_company
import app.hovanki.client.resources.achievement_big_company_detail
import app.hovanki.client.resources.achievement_catches
import app.hovanki.client.resources.achievement_catches_detail
import app.hovanki.client.resources.achievement_done
import app.hovanki.client.resources.achievement_games
import app.hovanki.client.resources.achievement_games_detail
import app.hovanki.client.resources.achievement_level
import app.hovanki.client.resources.achievement_marathon
import app.hovanki.client.resources.achievement_marathon_detail
import app.hovanki.client.resources.achievement_never_found
import app.hovanki.client.resources.achievement_never_found_detail
import app.hovanki.client.resources.achievement_new
import app.hovanki.client.resources.achievement_patience
import app.hovanki.client.resources.achievement_patience_detail
import app.hovanki.client.resources.achievement_roundup
import app.hovanki.client.resources.achievement_roundup_detail
import app.hovanki.client.resources.achievement_seekers_won
import app.hovanki.client.resources.achievement_seekers_won_detail
import app.hovanki.client.resources.achievement_sprinter
import app.hovanki.client.resources.achievement_sprinter_detail
import app.hovanki.client.resources.achievement_uncatchable
import app.hovanki.client.resources.achievement_uncatchable_detail
import app.hovanki.client.resources.achievement_weekly
import app.hovanki.client.resources.achievement_weekly_detail
import app.hovanki.client.resources.achievements_count
import app.hovanki.client.resources.achievements_empty
import app.hovanki.client.resources.achievements_title
import app.hovanki.client.resources.ic_trophy
import app.hovanki.client.resources.results_achievements_guest
import app.hovanki.client.resources.results_new_achievements
import app.hovanki.client.resources.unit_min
import app.hovanki.client.ui.common.CapsText
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.history.distanceText
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.protocol.AchievementProgress
import app.hovanki.shared.rules.AchievementRules
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The names of the achievements this app knows (docs/adr/0021-achievements.md); a newer server's others are skipped. */
internal object AchievementTexts {
    private class Texts(val title: StringResource, val detail: StringResource)

    private val byId = mapOf(
        AchievementRules.GAMES to Texts(Res.string.achievement_games, Res.string.achievement_games_detail),
        AchievementRules.NEVER_FOUND to
            Texts(Res.string.achievement_never_found, Res.string.achievement_never_found_detail),
        AchievementRules.UNCATCHABLE to
            Texts(Res.string.achievement_uncatchable, Res.string.achievement_uncatchable_detail),
        AchievementRules.CATCHES to Texts(Res.string.achievement_catches, Res.string.achievement_catches_detail),
        AchievementRules.ROUNDUP to Texts(Res.string.achievement_roundup, Res.string.achievement_roundup_detail),
        AchievementRules.SEEKERS_WON to
            Texts(Res.string.achievement_seekers_won, Res.string.achievement_seekers_won_detail),
        AchievementRules.PATIENCE to Texts(Res.string.achievement_patience, Res.string.achievement_patience_detail),
        AchievementRules.MARATHON to Texts(Res.string.achievement_marathon, Res.string.achievement_marathon_detail),
        AchievementRules.SPRINTER to Texts(Res.string.achievement_sprinter, Res.string.achievement_sprinter_detail),
        AchievementRules.BIG_COMPANY to
            Texts(Res.string.achievement_big_company, Res.string.achievement_big_company_detail),
        AchievementRules.WEEKLY to Texts(Res.string.achievement_weekly, Res.string.achievement_weekly_detail),
    )

    fun isKnown(id: String): Boolean = id in byId

    @Composable
    fun title(id: String): String = byId[id]?.let { stringResource(it.title) }.orEmpty()

    /** «Finds in all games: 7 / 10», toward the next level; the value alone once every level is reached. */
    @Composable
    fun detail(progress: AchievementProgress): String {
        val texts = byId[progress.id] ?: return ""
        val next = progress.thresholds.getOrNull(progress.level)
        val value = amount(progress.id, progress.value)
        val shown = if (next == null) value else "$value / ${amount(progress.id, next)}"
        return stringResource(texts.detail, shown)
    }

    /** [value] in the achievement's unit: meters as distance, seconds as minutes, the rest as a number. */
    @Composable
    private fun amount(id: String, value: Long): String = when (id) {
        AchievementRules.MARATHON, AchievementRules.SPRINTER -> distanceText(value.toDouble())
        AchievementRules.PATIENCE -> stringResource(Res.string.unit_min, value / SECONDS_PER_MINUTE)
        else -> value.toString()
    }

    private const val SECONDS_PER_MINUTE = 60L
}

/** The profile's «Achievements»: how many are reached, then every one with its level and the way to the next. */
@Composable
fun AchievementsCard(state: AchievementsUiState, modifier: Modifier = Modifier) {
    PopCard(
        modifier = modifier.fillMaxWidth().testTag(TestTags.PROFILE_ACHIEVEMENTS),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.achievements_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (state.isLoaded) {
                SecondaryText(stringResource(Res.string.achievements_count, state.reached, state.achievements.size))
            }
        }
        if (state.isLoaded && state.reached == 0) SecondaryText(stringResource(Res.string.achievements_empty))
        state.achievements.forEach { AchievementRow(it) }
    }
}

@Composable
private fun AchievementRow(progress: AchievementProgress) {
    val reached = progress.level > 0
    val levels = progress.thresholds.size
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.testTag(TestTags.achievement(progress.id)),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                .background(if (reached) Palette.Pink else Palette.Sand),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_trophy),
                contentDescription = null,
                tint = if (reached) Color.White else Palette.Ink3,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = AchievementTexts.title(progress.id), style = MaterialTheme.typography.titleSmall)
                if (progress.isNew) CapsText(stringResource(Res.string.achievement_new), color = Palette.PinkInk)
            }
            SecondaryText(AchievementTexts.detail(progress))
            ProgressBar(fraction(progress))
            val level = when {
                progress.level >= levels -> stringResource(Res.string.achievement_done)
                levels > 1 -> stringResource(Res.string.achievement_level, progress.level, levels)
                else -> null
            }
            if (level != null) CapsText(level, color = Palette.Ink2)
        }
    }
}

/** How far along to the next level, 0 to 1; full once every level is reached. */
internal fun fraction(progress: AchievementProgress): Float {
    val next = progress.thresholds.getOrNull(progress.level) ?: return 1f
    val previous = progress.thresholds.getOrNull(progress.level - 1) ?: 0L
    if (next <= previous) return 1f
    return ((progress.value - previous).toFloat() / (next - previous)).coerceIn(0f, 1f)
}

@Composable
private fun ProgressBar(fraction: Float) {
    Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.Sand)) {
        Box(
            modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                .background(Palette.Pink),
        )
    }
}

/** The results' «New achievements»: the levels this game (or an earlier one not seen yet) reached. */
@Composable
fun NewAchievementsCard(achievements: List<AchievementProgress>, modifier: Modifier = Modifier) {
    PopCard(
        modifier = modifier.fillMaxWidth().padding(top = 6.dp).testTag(TestTags.RESULTS_NEW_ACHIEVEMENTS),
        color = Palette.Ink,
        contentColor = Palette.Green,
        border = null,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CapsText(stringResource(Res.string.results_new_achievements))
        achievements.forEach { progress ->
            val levels = progress.thresholds.size
            val level = if (levels >
                1
            ) {
                " · ${stringResource(Res.string.achievement_level, progress.level, levels)}"
            } else {
                ""
            }
            Text(
                text = AchievementTexts.title(progress.id) + level,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
    }
}

/** A guest's results: what an account would collect. */
@Composable
fun GuestAchievementsHint(modifier: Modifier = Modifier) {
    PopCard(modifier = modifier.fillMaxWidth().padding(top = 6.dp).testTag(TestTags.RESULTS_ACHIEVEMENTS_GUEST)) {
        SecondaryText(stringResource(Res.string.results_achievements_guest))
    }
}
